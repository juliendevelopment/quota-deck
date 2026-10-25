// Native GJS + real libsoup fixture transport. All accounts/bodies are synthetic.
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import Soup from 'gi://Soup?version=3.0';
import {Runtime, invoke} from '../native/runtime.js';
import {Http, networkFailure} from '../native/http.js';
import {Loopback, callbackQuery} from '../native/loopback.js';
import {Bridge, BridgeClient} from '../native/bridge.js';
import {Auth} from '../lib/auth.js';
import {Providers} from '../lib/providers.js';
import {diagnostic} from '../lib/errors.js';
import {BUS_PATH} from '../lib/ipc.js';

let count = 0;
function assert(value, message) { if (!value) throw new Error(message); }
async function test(name, action) {
    await action();
    print(`ok ${++count} - ${name}`);
}
const runtime = new Runtime();
const server = new Soup.Server();
const requests = [];
let responses = [];
server.add_handler(null, (_server, msg, path) => {
    requests.push({path, headers: msg.get_request_headers(), body: new TextDecoder().decode(msg.get_request_body().flatten().get_data())});
    const response = responses.shift() ?? {status: 500};
    msg.set_status(response.status ?? 200, null);
    if (response.location)
        msg.get_response_headers().replace('Location', response.location);
    if (response.retry)
        msg.get_response_headers().replace('Retry-After', response.retry);
    msg.set_response('application/json', Soup.MemoryUse.COPY, new TextEncoder().encode(response.raw ?? JSON.stringify(response.data ?? {})));
});
server.listen_local(0, Soup.ServerListenOptions.IPV4_ONLY);
const base = `http://127.0.0.1:${server.get_uris()[0].get_port()}`;
const labels = ['claudeUsage', 'codexUsage', 'opencodeUsage', 'googleLoad', 'googleSummary', 'googleModels', 'claudeToken', 'googleToken', 'codexStart', 'codexPoll', 'codexToken'];
const endpoints = Object.fromEntries(labels.map(label => [label, `${base}/${label}`]));
const http = new Http(endpoints, {allowLoopback: true});
const fixtureFile = Gio.File.new_for_path(GLib.build_filenamev([GLib.path_get_dirname(GLib.filename_from_uri(import.meta.url)[0]), 'fixtures/providers.json']));
const fixture = JSON.parse(new TextDecoder().decode(fixtureFile.load_contents(null)[1]));
const session = new Soup.Session();
async function get(uri) {
    const msg = Soup.Message.new('GET', uri);
    const bytes = await invoke(cb => session.send_and_read_async(msg, GLib.PRIORITY_DEFAULT, null, cb), r => session.send_and_read_finish(r));
    return {status: msg.status_code, body: new TextDecoder().decode(bytes.toArray()), headers: msg.get_response_headers()};
}
try {
    await test('OS random and RFC 7636 PKCE S256 vector', async () => {
        const scope = runtime.scope();
        const a = await runtime.random(scope);
        const b = await runtime.random(scope);
        assert(/^[A-Za-z0-9_-]{43}$/.test(a) && a !== b, 'CSPRNG output');
        assert(runtime.challenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk') === 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM', 'RFC PKCE');
        scope.dispose();
    });
    for (const [id, key] of [['CLAUDE', 'claude'], ['CODEX', 'codex'], ['OPENCODE', 'opencode']]) {
        await test(`${id} native asynchronous quota transport and parser`, async () => {
            responses = [{data: fixture[key]}];
            const scope = runtime.scope();
            const result = await new Providers(http).fetch(id, {accessToken: 'synthetic-access', accountId: 'synthetic-workspace'}, scope);
            assert(result.snapshot.windows.length > 0, 'Provider windows');
            assert(requests.at(-1).headers.get_one('Authorization') === 'Bearer synthetic-access', 'Bearer header');
            assert(!requests.at(-1).headers.get_one('Cookie'), 'No cookies');
            scope.dispose();
        });
    }
    await test('Google native loadCodeAssist + quota summary', async () => {
        responses = [{data: {cloudaicompanionProject: 'synthetic-project'}}, {data: fixture.antigravity}];
        const scope = runtime.scope();
        const {snapshot} = await new Providers(http).fetch('ANTIGRAVITY', {accessToken: 'synthetic-access'}, scope);
        assert(snapshot.windows[0].remainingPercent === 42, 'Google percentage');
        assert(JSON.parse(requests.at(-1).body).project === 'synthetic-project', 'Existing project');
        scope.dispose();
    });
    await test('Credential-bearing redirects are not followed', async () => {
        const before = requests.length;
        responses = [{status: 302, location: `${base}/should-not-receive-token`}];
        const scope = runtime.scope();
        let error;
        try { await http.request('claudeUsage', {headers: {Authorization: 'Bearer synthetic-private-secret'}}, scope); } catch (e) { error = e; }
        assert(error?.http === 302 && requests.length === before + 1, 'No redirected request');
        scope.dispose();
    });
    await test('Native HTTP failure exposes only allowlisted diagnostics and Retry-After', async () => {
        responses = [{status: 429, retry: '19', data: {error: {code: 'slow_down', message: 'synthetic-private-secret'}}}];
        const scope = runtime.scope();
        let error;
        try { await http.request('codexPoll', {}, scope); } catch (e) { error = e; }
        assert(error?.code === 'slow_down' && error.retryAfterMs === 19000, 'Safe metadata');
        assert(!diagnostic('CODEX', 'DEVICE_POLL', error).includes('synthetic-private-secret'), 'No raw diagnostic');
        scope.dispose();
    });
    await test('Oversized native response is bounded during streaming', async () => {
        const small = new Http(endpoints, {allowLoopback: true, maxBytes: 100});
        responses = [{raw: JSON.stringify({body: 'x'.repeat(500)})}];
        const scope = runtime.scope();
        let error;
        try { await small.request('claudeUsage', {}, scope); } catch (e) { error = e; }
        assert(error?.kind === 'FORMAT', 'Size failure');
        scope.dispose();
        small.dispose();
    });
    for (const id of ['CLAUDE', 'ANTIGRAVITY']) {
        await test(`${id} real IPv4 callback validates path/state, rejects replay, safe browser page`, async () => {
            const scope = runtime.scope();
            const receiver = new Loopback(id, 'synthetic-state', scope);
            const invalid = await get(`${receiver.uri}?code=synthetic-code&state=wrong`);
            assert(invalid.status === 400 && !receiver.done, 'Invalid state does not consume listener');
            const duplicate = await get(`${receiver.uri}?code=synthetic-code&state=synthetic-state&state=synthetic-state`);
            assert(duplicate.status === 400 && !receiver.done, 'Duplicate state rejected');
            const response = await get(`${receiver.uri}?code=synthetic-code&state=synthetic-state`);
            assert(await receiver.receive === 'synthetic-code', 'Correct callback code');
            assert(response.status === 200 && response.body.includes('does not yet confirm'), 'Truthful browser page');
            assert(!response.body.includes('synthetic-code') && !response.body.includes('synthetic-state'), 'No reflection');
            assert(response.headers.get_one('Cache-Control') === 'no-store', 'No cache');
            assert((await get(`${receiver.uri}?code=replay&state=synthetic-state`)).status === 400, 'Single use');
            receiver.close();
            assert(scope.cleanups.size === 0, 'Listener released');
            scope.dispose();
        });
    }
    await test('Native OAuth callback denial is terminal and never reflects provider text', async () => {
        const scope = runtime.scope();
        const receiver = new Loopback('ANTIGRAVITY', 'synthetic-state', scope);
        const response = await get(`${receiver.uri}?state=synthetic-state&error=access_denied&error_description=synthetic-private-secret`);
        const failure = await receiver.receive.catch(e => e);
        assert(failure.kind === 'DENIED' && response.status === 400, 'Explicit denial');
        assert(!response.body.includes('synthetic-private-secret'), 'No description reflection');
        receiver.close();
        scope.dispose();
    });
    await test('Typed native DNS/TLS/timeout/restriction categories never include raw exceptions', async () => {
        for (const [domain, code, expected] of [
            [Gio.resolver_error_quark(), Gio.ResolverError.NOT_FOUND, 'DNS'],
            [Gio.tls_error_quark(), Gio.TlsError.BAD_CERTIFICATE, 'TLS'],
            [Gio.io_error_quark(), Gio.IOErrorEnum.TIMED_OUT, 'TIMEOUT'],
            [Gio.io_error_quark(), Gio.IOErrorEnum.PERMISSION_DENIED, 'RESTRICTED'],
        ]) {
            const failure = networkFailure(new GLib.Error(domain, code, 'synthetic-private-secret'), 'claudeUsage');
            assert(failure.network === expected, 'Typed category');
            assert(!failure.message.includes('synthetic-private-secret'), 'Safe network message');
        }
    });
    await test('Native callback cancellation releases listener and timer immediately', async () => {
        const scope = runtime.scope();
        const receiver = new Loopback('ANTIGRAVITY', 'state', scope);
        scope.cancel();
        let error;
        try { await receiver.receive; } catch (e) { error = e; }
        assert(error?.kind === 'CANCELLED' && receiver.closed && receiver.timeout === 0 && scope.cleanups.size === 0, 'Full listener cleanup');
    });
    await test('Native scope cancellation and expiry reject sleeps and remove sources', async () => {
        const scope = runtime.scope();
        const pending = scope.sleep(60000).catch(e => e);
        scope.cancel();
        assert((await pending).kind === 'CANCELLED' && scope.cleanups.size === 0, 'Sleep cancelled');
        const expires = runtime.scope();
        expires.expire(5);
        assert((await expires.sleep(60000).catch(e => e)).kind === 'EXPIRED', 'Bounded expiry');
        assert(expires.cleanups.size === 0, 'Expiry sources removed');
    });
    for (const id of ['CLAUDE', 'ANTIGRAVITY']) {
        await test(`${id} real callback then native JSON/form single-use token exchange`, async () => {
            responses = [{data: {access_token: 'synthetic-opaque-access', refresh_token: 'synthetic-refresh', expires_in: 3600}}];
            const r = new Runtime();
            r.openBrowser = async uri => {
                const params = callbackQuery(GLib.Uri.parse(uri, GLib.UriFlags.NONE).get_query());
                await get(`${params.redirect_uri}?code=synthetic-code&state=${encodeURIComponent(params.state)}`);
            };
            const scope = r.scope();
            const result = await new Auth(http, r).connect(id, scope, () => {});
            assert(result.accessToken === 'synthetic-opaque-access', 'Native token');
            const request = requests.at(-1);
            assert(id === 'CLAUDE' ? JSON.parse(request.body).scope === undefined : request.body.includes('code_verifier='), 'Protocol body');
            assert(scope.cleanups.size === 0, 'Receiver not retained');
            scope.dispose();
        });
    }
    await test('Native Codex polling waits server interval and final exchange is not retried', async () => {
        const before = requests.length;
        responses = [{data: {device_auth_id: 'synthetic-device', user_code: 'SYNTHETIC-CODE', interval: 5}},
            {data: {authorization_code: 'synthetic-grant', code_verifier: 'synthetic-verifier'}},
            {status: 503, data: {error: 'temporarily_unavailable', private: 'synthetic-private-secret'}}];
        const r = new Runtime();
        r.openBrowser = async () => {};
        const scope = r.scope();
        const start = r.monotonic();
        let error;
        try { await new Auth(http, r).codex(scope, () => {}); } catch (e) { error = e; }
        assert(error?.http === 503, 'Final exchange failure');
        assert(r.monotonic() - start >= 4990, 'Server interval respected');
        assert(requests.length === before + 3, 'No single-use replay');
        assert(requests.at(-1).body.includes('code_verifier=synthetic-verifier'), 'Form exchange');
        scope.dispose();
    });
    await test('In-flight native HTTP cancellation releases sources before completion', async () => {
        const scope = runtime.scope();
        responses = [{data: fixture.claude}];
        const pending = http.request('claudeUsage', {}, scope).catch(e => e);
        scope.cancel();
        assert(scope.cleanups.size === 0, 'Sources removed at cancellation');
        assert((await pending).kind === 'CANCELLED', 'Request cancellation surfaced safely');
    });
    await test('Real session D-Bus bridge round-trip has no credential getter and unexports', async () => {
        const fake = {deviceCode: '', view: () => [{id: 'CLAUDE', connected: false}], refresh: id => { fake.refreshed = id; }};
        const bridge = new Bridge(fake);
        const client = new BridgeClient();
        await client.start(() => {}, Gio.DBus.session.get_unique_name());
        assert(JSON.parse(await client.call('GetState'))[0].id === 'CLAUDE', 'D-Bus state');
        await client.call('Refresh', 'CLAUDE');
        assert(fake.refreshed === 'CLAUDE', 'D-Bus action');
        bridge.changed();
        bridge.dispose();
        let rejected = false;
        try { await client.call('GetState'); } catch { rejected = true; }
        assert(rejected, 'D-Bus path removed');
        assert(BUS_PATH === '/be/julien/QuotaDeck', 'Scoped path');
        client.dispose();
    });
    print(`Native GJS integration: ${count} passed; no real provider account used.`);
} finally {
    http.dispose();
    session.abort();
    server.disconnect();
}
