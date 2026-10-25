import {UUID, requireProvider} from '../lib/config.js';
import {Failure} from '../lib/errors.js';
import {invoke} from './runtime.js';

export class Store {
    constructor() {
        this.secret = null;
        this.schema = null;
    }

    async ensure(scope) {
        scope.check();
        try {
            if (!this.secret) {
                const Secret = (await import('gi://Secret?version=1')).default;
                scope.check();
                this.secret = Secret;
                this.schema = new Secret.Schema('be.julien.QuotaDeck.Gnome', Secret.SchemaFlags.NONE,
                    {application: Secret.SchemaAttributeType.STRING, provider: Secret.SchemaAttributeType.STRING});
            }
            const Secret = this.secret;
            const service = await invoke(cb => Secret.Service.get(Secret.ServiceFlags.OPEN_SESSION, scope.cancellable, cb), r => Secret.Service.get_finish(r));
            const collection = await invoke(cb => Secret.Collection.for_alias(service, 'default', Secret.CollectionFlags.NONE, scope.cancellable, cb), r => Secret.Collection.for_alias_finish(r));
            scope.check();
            if (!collection || collection.get_locked())
                throw new Failure('KEYRING');
        } catch {
            scope.check();
            throw new Failure('KEYRING');
        }
    }

    attributes(id) {
        return {application: UUID, provider: requireProvider(id)};
    }

    async lookup(id, scope) {
        const Secret = this.secret;
        return invoke(cb => Secret.password_lookup(this.schema, this.attributes(id), scope.cancellable, cb), r => Secret.password_lookup_finish(r));
    }

    async load(id, scope) {
        await this.ensure(scope);
        let raw;
        try {
            raw = await this.lookup(id, scope);
        } catch {
            scope.check();
            throw new Failure('KEYRING');
        }
        scope.check();
        if (raw === null)
            return null;
        try {
            const record = JSON.parse(raw);
            if (record.version !== 1 || typeof record.credential?.accessToken !== 'string' || !record.credential.accessToken)
                throw new Failure('STORAGE');
            if (record.snapshot && (record.snapshot.provider !== id || !Array.isArray(record.snapshot.windows) || !Number.isFinite(record.snapshot.fetchedAt)))
                throw new Failure('STORAGE');
            return {credential: record.credential, snapshot: record.snapshot ?? null};
        } catch {
            throw new Failure('STORAGE');
        }
    }

    async save(id, record, scope) {
        await this.ensure(scope);
        const raw = JSON.stringify({version: 1, credential: record.credential, snapshot: record.snapshot ?? null});
        const Secret = this.secret;
        try {
            const saved = await invoke(cb => Secret.password_store(this.schema, this.attributes(id), Secret.COLLECTION_DEFAULT,
                `QuotaDeck GNOME · ${id}`, raw, scope.cancellable, cb), r => Secret.password_store_finish(r));
            scope.check();
            if (!saved || await this.lookup(id, scope) !== raw)
                throw new Failure('KEYRING');
        } catch {
            scope.check();
            throw new Failure('KEYRING');
        }
    }

    async clear(id, scope) {
        await this.ensure(scope);
        const Secret = this.secret;
        try {
            await invoke(cb => Secret.password_clear(this.schema, this.attributes(id), scope.cancellable, cb), r => Secret.password_clear_finish(r));
            scope.check();
            if (await this.lookup(id, scope) !== null)
                throw new Failure('KEYRING');
        } catch {
            scope.check();
            throw new Failure('KEYRING');
        }
    }

    dispose() {
        this.schema = null;
        this.secret = null;
    }
}
