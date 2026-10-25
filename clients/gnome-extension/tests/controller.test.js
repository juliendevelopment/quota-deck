import test from 'node:test';
import assert from 'node:assert/strict';
import {Controller} from '../lib/controller.js';
import {Failure} from '../lib/errors.js';
import {FakeRuntime, memoryStore} from './helpers.js';
const snapshot = {provider: 'CLAUDE', windows: [{label: 'Synthetic', remainingPercent: 37}], fetchedAt: 1234};
const credential = {accessToken: 'synthetic-access', refreshToken: 'synthetic-refresh', expiresAt: 9999999999999};
function setup(options = {}) {
    const runtime = new FakeRuntime();
    const store = memoryStore({CLAUDE: {credential, snapshot}});
    const events = [];
    const providers = {async fetch() { return {credential, snapshot}; }};
    const auth = {async refresh() { return {...credential, accessToken: 'rotated'}; }, async connect() { return credential; }};
    const controller = new Controller({runtime, store, providers, auth, changed: s => events.push(s), ...options});
    return {controller, runtime, store, events};
}
test('Startup loads secure state, then refreshes connected accounts only', async () => {
    const {controller, store} = setup();
    await controller.start();
    assert.equal(controller.states.CLAUDE.connected, true);
    assert.equal(controller.states.CLAUDE.stale, false);
    assert.equal(controller.states.CODEX.connected, false);
    assert.equal(store.saves.length, 1);
});
test('Quota failure retains last reading with stale and safe diagnostic', async () => {
    const {controller} = setup({providers: {async fetch() { throw new Error('synthetic-secret user@example.invalid'); }}});
    await controller.start();
    const s = controller.states.CLAUDE;
    assert.equal(s.snapshot, snapshot);
    assert.equal(s.stale, true);
    assert.doesNotMatch(s.error + s.diagnostic, /synthetic-secret|user@example/);
});
test('Unavailable keyring reports explicit error and never connects', async () => {
    const {controller} = setup({store: {async load() { throw new Failure('KEYRING'); }}});
    await controller.start();
    assert.equal(controller.states.CLAUDE.connected, false);
    assert.match(controller.states.CLAUDE.error, /keyring unavailable/);
    assert.equal(controller.records.size, 0);
});
test('New connection clears old account cache even if initial quotas fail', async () => {
    const {controller} = setup();
    await controller.start();
    controller.providers.fetch = async () => { throw new Failure('FORMAT'); };
    await controller.connect('CLAUDE');
    assert.equal(controller.states.CLAUDE.connected, true);
    assert.equal(controller.states.CLAUDE.snapshot, null);
    assert.match(controller.states.CLAUDE.error, /quota/);
});
test('Refresh token rotation is persisted before a failing quota request', async () => {
    const {controller, store} = setup();
    store.records.set('CLAUDE', {credential: {...credential, expiresAt: 0}, snapshot});
    controller.providers.fetch = async (_id, c) => {
        assert.equal(c.accessToken, 'rotated');
        assert.equal(store.records.get('CLAUDE').credential.accessToken, 'rotated');
        throw new Failure('FORMAT');
    };
    await controller.start();
    assert.equal(store.records.get('CLAUDE').credential.accessToken, 'rotated');
    assert.equal(controller.states.CLAUDE.snapshot, snapshot);
    assert.equal(controller.states.CLAUDE.stale, true);
});
test('Quota 401 refreshes at most once and keeps previous data if refused', async () => {
    let attempts = 0;
    let refreshes = 0;
    const {controller} = setup({providers: {async fetch() { attempts++; throw new Failure('LOGIN', {http: 401}); }},
        auth: {async refresh() { refreshes++; return credential; }}});
    await controller.start();
    assert.equal(attempts, 2);
    assert.equal(refreshes, 1);
    assert.equal(controller.states.CLAUDE.stale, true);
});
test('Local disconnect deletes only this provider and reads no other application', async () => {
    const {controller, store} = setup();
    await controller.start();
    await controller.disconnect('CLAUDE');
    assert.deepEqual(store.clears, ['CLAUDE']);
    assert.equal(controller.states.CLAUDE.connected, false);
    assert.equal(controller.states.CLAUDE.snapshot, null);
    assert.equal(controller.records.has('CLAUDE'), false);
});
test('Cancel + disconnect serializes deletion after an already submitted storage write', async () => {
    const {controller, store} = setup();
    let release;
    let entered;
    const gate = new Promise(resolve => { entered = resolve; });
    const original = store.save;
    store.save = async function (id, record) {
        entered();
        await new Promise(resolve => { release = resolve; });
        return original.call(this, id, record);
    };
    const connection = controller.connect('CLAUDE');
    await gate;
    const disconnect = controller.disconnect('CLAUDE');
    release();
    await Promise.all([connection, disconnect]);
    assert.equal(store.records.has('CLAUDE'), false);
    assert.equal(controller.states.CLAUDE.connected, false);
});
test('Disable cancels work, releases resources, emits nothing after disposal', async () => {
    const {controller, runtime, events} = setup();
    let release;
    controller.auth.connect = async (_id, scope) => {
        await new Promise(resolve => { release = resolve; });
        scope.check();
        return credential;
    };
    const pending = controller.connect('CODEX');
    await new Promise(resolve => setImmediate(resolve));
    const count = events.length;
    controller.dispose();
    release();
    await pending;
    assert.equal(events.length, count);
    assert.equal(controller.records.size, 0);
    assert.equal(controller.operations.size, 0);
    assert.equal(runtime.scopes.every(s => s.cancelled && s.cleanups.size === 0), true);
});
test('Safe view never includes stored tokens or transient device codes', async () => {
    const {controller} = setup();
    await controller.start();
    controller.deviceCode = 'synthetic-private-device-code';
    assert.doesNotMatch(JSON.stringify(controller.view()), /synthetic-access|synthetic-refresh|synthetic-private-device-code/);
});
test('OpenCode explicit key submission stores only in secure store and quota-read failure remains connected', async () => {
    const {controller, store} = setup({providers: {async fetch() { throw new Failure('HTTP', {http: 403}); }}});
    await controller.submitKey('synthetic-existing-go-key');
    assert.equal(store.records.get('OPENCODE').credential.accessToken, 'synthetic-existing-go-key');
    assert.equal(controller.states.OPENCODE.connected, true);
    assert.equal(controller.states.OPENCODE.snapshot, null);
    assert.doesNotMatch(JSON.stringify(controller.view()), /synthetic-existing-go-key/);
});
test('Malformed key is refused before storage or network', async () => {
    const {controller, store} = setup();
    await controller.submitKey('bad key');
    assert.equal(store.records.has('OPENCODE'), false);
    assert.match(controller.states.OPENCODE.error, /API key/);
});
