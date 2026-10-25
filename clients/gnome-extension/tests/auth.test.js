import test from 'node:test';
import assert from 'node:assert/strict';
import {Auth} from '../lib/auth.js';
import {OAUTH} from '../lib/config.js';
import {Failure} from '../lib/errors.js';
import {FakeRuntime, httpSequence, tokens, device, grant} from './helpers.js';

for (const id of ['CLAUDE', 'ANTIGRAVITY']) {
    test(`${id} system-browser OAuth binds PKCE/state/redirect and closes listener`, async () => {
        const runtime = new FakeRuntime();
        const http = httpSequence([tokens]);
        const phases = [];
        const c = await new Auth(http, runtime).connect(id, runtime.scope(), stage => phases.push(stage));
        const url = new URL(runtime.opened[0]);
        assert.equal(url.searchParams.get('code_challenge_method'), 'S256');
        assert.match(url.searchParams.get('state'), /^[A-Za-z0-9_-]{43}$/);
        assert.equal(url.searchParams.get('redirect_uri'), runtime.received.uri);
        const fields = http.calls[0].options.body ?? http.calls[0].options.form;
        assert.equal(fields.redirect_uri, runtime.received.uri);
        assert.equal(runtime.challenge(fields.code_verifier), url.searchParams.get('code_challenge'));
        assert.equal(runtime.received.closed, true);
        assert.equal(c.accessToken, tokens.access_token);
        assert.deepEqual(phases, ['BROWSER', 'CALLBACK', 'TOKEN_EXCHANGE']);
        if (id === 'CLAUDE') {
            assert.equal(url.searchParams.get('scope'), 'user:profile');
            assert.equal(fields.state, url.searchParams.get('state'));
            assert.equal(url.hostname, 'claude.com');
        } else {
            assert.equal(url.searchParams.get('scope'), OAUTH.googleScopes.join(' '));
            assert.equal(fields.client_secret, OAUTH.googleInstalledValue);
        }
    });
    test(`${id} browser failure cleans listener and never exchanges`, async () => {
        const runtime = new FakeRuntime();
        runtime.onBrowser = () => { throw new Failure('BROWSER'); };
        const http = httpSequence([]);
        await assert.rejects(new Auth(http, runtime).connect(id, runtime.scope(), () => {}));
        assert.equal(runtime.received.closed, true);
        assert.equal(http.calls.length, 0);
    });
}
test('Codex real protocol fields, initial server interval and one final form exchange', async () => {
    const runtime = new FakeRuntime();
    const http = httpSequence([{...device, interval: ' 120 '}, grant, tokens]);
    const c = await new Auth(http, runtime).codex(runtime.scope(), () => {});
    assert.equal(c.accessToken, tokens.access_token);
    assert.deepEqual(runtime.waits, [120000]);
    assert.deepEqual(http.calls.map(x => x.endpoint), ['codexStart', 'codexPoll', 'codexToken']);
    assert.deepEqual(http.calls[1].options.body, {device_auth_id: device.device_auth_id, user_code: device.user_code});
    assert.equal(http.calls[2].options.form.redirect_uri, 'https://auth.openai.com/deviceauth/callback');
    assert.equal(http.calls[2].options.form.code_verifier, grant.code_verifier);
});
test('Codex transient failures, pending, slow_down and Retry-After retain one device code', async () => {
    const runtime = new FakeRuntime();
    const http = httpSequence([device, new Failure('NETWORK', {network: 'DNS'}),
        new Failure('HTTP', {http: 404}), new Failure('RATE', {http: 429, code: 'slow_down', retryAfterMs: 19000}), grant, tokens]);
    await new Auth(http, runtime).codex(runtime.scope(), () => {});
    assert.deepEqual(runtime.waits, [5000, 5000, 5000, 19000]);
    assert.equal(http.calls.filter(c => c.endpoint === 'codexStart').length, 1);
    assert.equal(http.calls.filter(c => c.endpoint === 'codexToken').length, 1);
});
for (const code of ['access_denied', 'expired_token', 'invalid_grant', 'invalid_client', 'unauthorized_client', 'invalid_scope', 'insufficient_scope', 'invalid_request']) {
    test(`Codex explicit ${code} is terminal even on pending-looking HTTP 403`, async () => {
        const runtime = new FakeRuntime();
        const http = httpSequence([device, new Failure('OAUTH', {http: 403, code}), grant, tokens]);
        await assert.rejects(new Auth(http, runtime).codex(runtime.scope(), () => {}), e => e.code === code);
        assert.equal(http.calls.length, 2);
    });
}
test('Codex uncertain single-use exchange is never replayed', async () => {
    const runtime = new FakeRuntime();
    const http = httpSequence([device, grant, new Failure('NETWORK', {network: 'CONNECTION'}), tokens]);
    await assert.rejects(new Auth(http, runtime).codex(runtime.scope(), () => {}));
    assert.equal(http.calls.filter(c => c.endpoint === 'codexToken').length, 1);
    assert.equal(http.calls.length, 3);
});
test('Codex bounded expiry honors retry-after without polling past lifetime', async () => {
    const runtime = new FakeRuntime();
    const http = httpSequence([{...device, expires_in: 6}, new Failure('RATE', {http: 429, retryAfterMs: 100000})]);
    await assert.rejects(new Auth(http, runtime).codex(runtime.scope(), () => {}), e => e.kind === 'EXPIRED');
    assert.deepEqual(runtime.waits, [5000, 1000]);
    assert.equal(http.calls.length, 2);
});
test('Codex explicit cancellation prevents polling and exchange', async () => {
    const runtime = new FakeRuntime();
    const http = httpSequence([device, grant, tokens]);
    runtime.onSleep = scope => scope.cancel();
    await assert.rejects(new Auth(http, runtime).codex(runtime.scope(), () => {}), e => e.kind === 'CANCELLED');
    assert.equal(http.calls.length, 1);
});
test('Codex opaque access token uses ID-token workspace; refresh preserves selection', async () => {
    const runtime = new FakeRuntime();
    const payload = Buffer.from(JSON.stringify({'https://api.openai.com/auth': {chatgpt_account_id: 'synthetic-workspace'}})).toString('base64url');
    const response = {...tokens, access_token: 'opaque', id_token: `e30.${payload}.synthetic`};
    const http = httpSequence([response, {...response, refresh_token: ''}]);
    const auth = new Auth(http, runtime);
    assert.equal(auth.credential(response).accountId, 'synthetic-workspace');
    const next = await auth.refresh('CODEX', {refreshToken: 'old', accountId: '', projectId: 'project'}, runtime.scope());
    assert.equal(next.accountId, 'synthetic-workspace');
    const selected = await auth.refresh('CODEX', {...next, accountId: 'selected'}, runtime.scope());
    assert.equal(selected.accountId, 'selected');
    assert.equal(selected.refreshToken, next.refreshToken);
    assert.equal(selected.projectId, 'project');
});
test('Claude refresh requests only the profile scope', async () => {
    const runtime = new FakeRuntime();
    const http = httpSequence([tokens]);
    await new Auth(http, runtime).refresh('CLAUDE', {refreshToken: 'old'}, runtime.scope());
    assert.equal(http.calls[0].options.body.scope, 'user:profile');
});
