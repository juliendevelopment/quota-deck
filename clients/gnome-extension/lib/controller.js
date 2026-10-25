import {IDS, requireProvider} from './config.js';
import {Failure, safeFailure, diagnostic} from './errors.js';

const empty = id => ({id, connected: false, loading: false, snapshot: null, stale: false,
    error: null, diagnostic: null, stage: 'DISCONNECTED'});

// Single owner in Shell: preferences call this module over session D-Bus.
// Credentials never leave this module except for the keyring/provider adapters.
export class Controller {
    constructor({store, auth, providers, runtime, changed = () => {}, shellVersion = ''}) {
        Object.assign(this, {store, auth, providers, runtime, changed, shellVersion});
        this.states = Object.fromEntries(IDS.map(id => [id, empty(id)]));
        this.records = new Map();
        this.operations = new Map();
        this.deviceCode = '';
        this.disposed = false;
    }

    view() {
        return IDS.map(id => ({...this.states[id]}));
    }

    emit() {
        if (!this.disposed)
            this.changed(this.view());
    }

    async start() {
        await Promise.all(IDS.map(id => this.run(id, async scope => {
            this.states[id].stage = 'STORAGE';
            const record = await this.store.load(id, scope);
            scope.check();
            if (record) {
                this.records.set(id, record);
                Object.assign(this.states[id], {connected: true, snapshot: record.snapshot ?? null,
                    stale: Boolean(record.snapshot), stage: 'CONNECTED'});
            }
        })));
        if (!this.disposed)
            await this.refreshAll();
    }

    run(id, action) {
        requireProvider(id);
        if (this.disposed)
            return Promise.resolve();
        if (this.operations.has(id))
            return this.operations.get(id).promise;
        const scope = this.runtime.scope();
        const state = this.states[id];
        state.loading = true;
        state.error = null;
        state.diagnostic = null;
        const operation = {scope, promise: null};
        this.operations.set(id, operation);
        operation.promise = (async () => {
            try {
                await action(scope);
                scope.check();
            } catch (error) {
                if (!this.disposed) {
                    const failure = scope.cancelled ? scope.reason : safeFailure(error);
                    state.error = failure.message;
                    state.diagnostic = diagnostic(id, state.stage, failure, this.shellVersion);
                    state.stale = Boolean(state.snapshot);
                    if (failure.kind === 'CANCELLED')
                        state.stage = 'CANCELLED';
                }
            } finally {
                scope.dispose();
                if (this.operations.get(id) === operation)
                    this.operations.delete(id);
                if (id === 'CODEX')
                    this.deviceCode = '';
                state.loading = false;
                this.emit();
            }
        })();
        this.emit();
        return operation.promise;
    }

    async save(id, record, scope) {
        scope.check();
        this.states[id].stage = 'STORAGE';
        await this.store.save(id, record, scope);
        scope.check();
        this.records.set(id, record);
        Object.assign(this.states[id], {connected: true, snapshot: record.snapshot ?? null});
    }

    refresh(id) {
        return this.run(id, async scope => {
            let record = this.records.get(id);
            if (!record)
                return;
            let credential = record.credential;
            const refresh = async () => {
                this.states[id].stage = 'REFRESH';
                credential = await this.auth.refresh(id, credential, scope);
                // Persist rotation BEFORE quota I/O; quota failure must not lose a new refresh token.
                record = {...record, credential};
                await this.save(id, record, scope);
            };
            let refreshed = false;
            if (id !== 'OPENCODE' && credential.expiresAt <= this.runtime.now() + 60000) {
                await refresh();
                refreshed = true;
            }
            this.states[id].stage = 'QUOTA';
            let result;
            try {
                result = await this.providers.fetch(id, credential, scope);
            } catch (error) {
                if (!(error instanceof Failure) || error.http !== 401 || refreshed || id === 'OPENCODE')
                    throw error;
                await refresh();
                this.states[id].stage = 'QUOTA';
                result = await this.providers.fetch(id, credential, scope);
            }
            await this.save(id, result, scope);
            Object.assign(this.states[id], {stale: false, stage: 'CONNECTED'});
        });
    }

    refreshAll() {
        return Promise.all(IDS.filter(id => this.records.has(id)).map(id => this.refresh(id)));
    }

    connect(id) {
        requireProvider(id);
        if (id === 'OPENCODE')
            return Promise.resolve(); // Key submission is a separate explicit user action.
        const promise = this.run(id, async scope => {
            // Probe secure storage before browser consent, never fall back to a local file.
            await this.store.load(id, scope);
            scope.check();
            const credential = await this.auth.connect(id, scope, (stage, code = '') => {
                if (scope.cancelled || this.disposed)
                    return;
                if (stage)
                    this.states[id].stage = stage;
                if (id === 'CODEX')
                    this.deviceCode = code;
                this.emit();
            });
            // Old account cache must not be attached to a newly connected account.
            await this.save(id, {credential, snapshot: null}, scope);
            Object.assign(this.states[id], {stale: false, stage: 'CONNECTED'});
        });
        return promise.then(() => {
            if (!this.disposed && !this.states[id].error)
                return this.refresh(id);
        });
    }

    submitKey(key) {
        return this.run('OPENCODE', async scope => {
            this.states.OPENCODE.stage = 'KEY_ENTRY';
            if (typeof key !== 'string' || key.length < 8 || key.length > 4096 || /\s/.test(key))
                throw new Failure('KEY');
            await this.store.load('OPENCODE', scope);
            const credential = {accessToken: key, refreshToken: '', expiresAt: 0, accountId: '', projectId: ''};
            await this.save('OPENCODE', {credential, snapshot: null}, scope);
            this.states.OPENCODE.stage = 'CONNECTED';
        }).then(() => {
            if (!this.disposed && !this.states.OPENCODE.error)
                return this.refresh('OPENCODE');
        });
    }

    cancel(id) {
        requireProvider(id);
        this.operations.get(id)?.scope.cancel();
    }

    async disconnect(id) {
        requireProvider(id);
        const pending = this.operations.get(id);
        pending?.scope.cancel();
        // Serialize deletion AFTER any already submitted secure-store write.
        await pending?.promise;
        return this.run(id, async scope => {
            this.states[id].stage = 'STORAGE';
            await this.store.clear(id, scope);
            scope.check();
            this.records.delete(id);
            this.states[id] = empty(id);
        });
    }

    dispose() {
        this.disposed = true;
        for (const {scope} of this.operations.values())
            scope.cancel();
        this.operations.clear();
        this.records.clear();
        this.deviceCode = '';
        this.states = Object.fromEntries(IDS.map(id => [id, empty(id)]));
        this.changed = () => {};
    }
}
