import {createHash, randomBytes} from 'node:crypto';
import {Failure} from '../lib/errors.js';
export class FakeRuntime {
    constructor() {
        this.time = 1792749600000;
        this.elapsed = 0;
        this.waits = [];
        this.scopes = [];
        this.opened = [];
    }
    now() { return this.time + this.elapsed; }
    monotonic() { return this.elapsed; }
    scope() {
        const runtime = this;
        const scope = {
            cancelled: false, reason: new Failure('CANCELLED'), cleanups: new Set(),
            check() { if (this.cancelled) throw this.reason; },
            own(fn) { this.cleanups.add(fn); return () => this.cleanups.delete(fn); },
            expire() { return () => {}; },
            async sleep(ms) { this.check(); runtime.waits.push(ms); runtime.elapsed += ms; runtime.onSleep?.(this); this.check(); },
            cancel(reason = new Failure('CANCELLED')) { this.cancelled = true; this.reason = reason; this.dispose(); },
            dispose() { const list = [...this.cleanups]; this.cleanups.clear(); for (const fn of list) fn(); },
        };
        this.scopes.push(scope);
        return scope;
    }
    async random() { return randomBytes(32).toString('base64url'); }
    challenge(v) { return createHash('sha256').update(v).digest('base64url'); }
    decodeBase64(v) { return Buffer.from(v, 'base64url').toString('utf8'); }
    async openBrowser(uri, scope) { scope.check(); this.opened.push(uri); await this.onBrowser?.(uri); }
    receiver(id, _state, scope) {
        const result = {uri: `http://${id === 'CLAUDE' ? 'localhost' : '127.0.0.1'}:12345/${id === 'CLAUDE' ? 'callback' : 'oauth-callback'}`,
            receive: Promise.resolve('synthetic-code'), closed: false,
            close() { this.closed = true; }};
        scope.own(() => result.close());
        this.received = result;
        return result;
    }
}
export function httpSequence(values) {
    const calls = [];
    return {calls, async request(endpoint, options, scope) {
        scope.check();
        calls.push({endpoint, options});
        const next = values.shift();
        if (next instanceof Error)
            throw next;
        if (typeof next === 'function')
            return next(endpoint, options, scope);
        if (next === undefined)
            throw new Error('Fixture sequence exhausted');
        return next;
    }};
}
export const tokens = {access_token: 'synthetic-access', refresh_token: 'synthetic-refresh', expires_in: 3600};
export const grant = {authorization_code: 'synthetic-grant', code_verifier: 'synthetic-verifier'};
export const device = {device_auth_id: 'synthetic-device', user_code: 'SYNTHETIC-CODE', interval: '5'};
export function memoryStore(initial = {}) {
    const records = new Map(Object.entries(initial));
    return {records, saves: [], clears: [], async load(id) { return records.get(id) ?? null; },
        async save(id, record) { this.saves.push({id, record}); records.set(id, record); },
        async clear(id) { this.clears.push(id); records.delete(id); }};
}
