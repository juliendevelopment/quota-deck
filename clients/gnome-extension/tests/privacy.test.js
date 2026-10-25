import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync, readdirSync} from 'node:fs';
import {Failure, diagnostic, httpFailure, retryAfter} from '../lib/errors.js';
import {ENDPOINTS} from '../lib/config.js';
const marker = 'synthetic-private-secret@example.invalid';
test('Diagnostics reject raw errors, URL queries, account data and unrecognized codes', () => {
    const e = new Failure('NETWORK', {http: 400, code: marker, endpoint: `https://host/${marker}?code=${marker}`, network: marker});
    e.message = marker;
    assert.doesNotMatch(diagnostic(marker, marker, e, marker), /synthetic-private-secret|https:/);
    assert.doesNotMatch(diagnostic('CLAUDE', 'TOKEN_EXCHANGE', new Error(marker), '50.6'), /synthetic-private-secret/);
    assert.equal(diagnostic('CLAUDE', 'TOKEN_EXCHANGE', e).split('\n').length, 8);
});
test('HTTP allowlists nested errors and discards descriptions and arbitrary raw bodies', () => {
    const e = httpFailure(403, {error: {code: 'access_denied', message: marker}, error_description: marker}, 'codexPoll');
    assert.equal(e.code, 'access_denied');
    assert.equal(e.kind, 'DENIED');
    assert.doesNotMatch(e.message + diagnostic('CODEX', 'DEVICE_POLL', e), /synthetic-private-secret/);
    assert.equal(httpFailure(401, {error: marker}, 'claudeUsage').code, null);
});
test('Retry-After supports seconds/date with finite bound and no raw strings', () => {
    assert.equal(retryAfter('19', 1000), 19000);
    assert.equal(retryAfter('Thu, 01 Jan 1970 00:01:00 GMT', 1000), 59000);
    assert.equal(retryAfter('999999999'), 86400000);
    assert.equal(retryAfter(marker), 0);
});
test('GSettings schema contains only non-secret appearance/refresh preferences', () => {
    const schema = readFileSync(new URL('../schemas/org.gnome.shell.extensions.quotadeck.gschema.xml', import.meta.url), 'utf8');
    assert.deepEqual([...schema.matchAll(/<key name="([^"]+)"/g)].map(m => m[1]),
        ['refresh-minutes', 'automatic-refresh', 'panel-provider', 'show-identity']);
    assert.doesNotMatch(schema, /token|cookie|password|account-id|cache|device-code/);
});
test('Runtime destination allowlist has quota/auth endpoints only, all HTTPS', () => {
    for (const url of Object.values(ENDPOINTS)) {
        assert.equal(new URL(url).protocol, 'https:');
        assert.doesNotMatch(url, /generate|chat\/completions|onboard|billing|createProject/i);
    }
});
test('Native production modules never print raw exceptions or read external app credentials', () => {
    for (const directory of ['lib', 'native', 'ui']) {
        for (const name of readdirSync(new URL(`../${directory}/`, import.meta.url))) {
            const source = readFileSync(new URL(`../${directory}/${name}`, import.meta.url), 'utf8');
            assert.doesNotMatch(source, /console\.|logError\(|print\(|spawn_sync|_sync\(|\/\.(?:claude|codex|hermes)(?:\/|['"])|cookies\.sqlite|localStorage/);
        }
    }
});
