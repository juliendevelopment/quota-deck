import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {Providers} from '../lib/providers.js';
import {Failure} from '../lib/errors.js';
import {FakeRuntime, httpSequence} from './helpers.js';
const f = JSON.parse(readFileSync(new URL('./fixtures/providers.json', import.meta.url)));
const credential = {accessToken: 'synthetic-access', accountId: 'synthetic-workspace'};
for (const [id, fixture, endpoint] of [['CLAUDE', 'claude', 'claudeUsage'], ['CODEX', 'codex', 'codexUsage'], ['OPENCODE', 'opencode', 'opencodeUsage']]) {
    test(`${id} real quota request has bearer auth and no cookies/model calls`, async () => {
        const http = httpSequence([f[fixture]]);
        const result = await new Providers(http, () => f.now).fetch(id, credential, new FakeRuntime().scope());
        assert.equal(result.snapshot.provider, id);
        assert.equal(http.calls[0].endpoint, endpoint);
        assert.equal(http.calls[0].options.headers.Authorization, 'Bearer synthetic-access');
        assert.equal(http.calls[0].options.headers.Cookie, undefined);
        assert.equal(http.calls[0].options.body, undefined);
        if (id === 'CODEX')
            assert.equal(http.calls[0].options.headers['ChatGPT-Account-Id'], credential.accountId);
        if (id === 'CLAUDE')
            assert.equal(http.calls[0].options.headers['anthropic-beta'], 'oauth-2025-04-20');
    });
}
test('Google reads initialized project and summary without onboarding', async () => {
    const http = httpSequence([{cloudaicompanionProject: {id: 'synthetic-project'}, currentTier: {name: 'User Tier'}}, f.antigravity]);
    const result = await new Providers(http).fetch('ANTIGRAVITY', credential, new FakeRuntime().scope());
    assert.equal(result.snapshot.plan, 'User Tier');
    assert.equal(result.credential.projectId, 'synthetic-project');
    assert.deepEqual(http.calls.map(c => c.endpoint), ['googleLoad', 'googleSummary']);
    assert.deepEqual(http.calls[1].options.body, {project: 'synthetic-project'});
});
test('Google requires initialization, never creates a project', async () => {
    const http = httpSequence([{}]);
    await assert.rejects(new Providers(http).fetch('ANTIGRAVITY', credential, new FakeRuntime().scope()), e => e.kind === 'INITIALIZE');
    assert.equal(http.calls.length, 1);
});
test('Google explicit unsupported-client refusal is terminal', async () => {
    const http = httpSequence([{ineligibleTiers: [{reasonCode: 'GOOGLE_TOS_NOT_SUPPORTED_BY_CLIENT'}]}]);
    await assert.rejects(new Providers(http).fetch('ANTIGRAVITY', credential, new FakeRuntime().scope()), e => e.kind === 'CLIENT');
    assert.equal(http.calls.length, 1);
});
test('Google unsupported summary can use read-only model fallback, hiding all maxima', async () => {
    const http = httpSequence([{cloudaicompanionProject: 'synthetic-project'}, new Failure('HTTP', {http: 404}), f.models]);
    const {snapshot} = await new Providers(http).fetch('ANTIGRAVITY', credential, new FakeRuntime().scope());
    assert.equal(snapshot.windows[0].remainingPercent, null);
    assert.match(snapshot.notice, /unverified/);
});
for (const status of [401, 403, 429, 500]) {
    test(`Google HTTP ${status} is not bypassed using another endpoint`, async () => {
        const http = httpSequence([{cloudaicompanionProject: 'synthetic-project'}, new Failure('HTTP', {http: status}), f.models]);
        await assert.rejects(new Providers(http).fetch('ANTIGRAVITY', credential, new FakeRuntime().scope()));
        assert.equal(http.calls.length, 2);
    });
}
