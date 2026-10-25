import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import * as P from '../lib/parsers.js';
import {percentage, resetLabel, panelText, readingLabel} from '../lib/display.js';
const f = JSON.parse(readFileSync(new URL('./fixtures/providers.json', import.meta.url)));

test('Claude converts utilization, preserves null, names and resets', () => {
    const s = P.claude(f.claude, f.now);
    assert.deepEqual(s.windows.map(w => w.remainingPercent), [75, null, 0, 50]);
    assert.equal(s.windows[3].label, 'Model <User Name>');
    assert.equal(s.windows[0].resetAt, '2026-10-24T10:00:00.000Z');
    assert.equal(s.fetchedAt, f.now);
});
test('Claude enabled extra usage is read, never toggled', () => {
    assert.equal(P.claude({extra_usage: {is_enabled: true}}).windows[0].remainingPercent, null);
    assert.throws(() => P.claude({extra_usage: {is_enabled: false}}));
});
test('Codex reads additional, review, credits and unknown windows', () => {
    const s = P.codex(f.codex, f.now);
    assert.equal(s.plan, 'plus');
    assert.deepEqual(s.windows.map(w => w.remainingPercent), [36, null, 90, 100, null]);
    assert.equal(s.windows[2].label, 'User Workspace · Session');
    assert.equal(s.windows[0].resetAt, new Date(1792836000000).toISOString());
    assert.equal(s.windows[4].detail, 'Reported balance: 12.50');
});
test('Google parses nested fractions, disabled buckets and model names', () => {
    const s = P.antigravitySummary(f.antigravity);
    assert.equal(s[0].remainingPercent, 42);
    assert.equal(s[1].remainingPercent, null);
    assert.equal(s[1].detail, 'Not available on this plan');
    assert.equal(P.antigravityModels(f.models)[0].label, 'User Model');
});
test('Google summary maximum is verified but model fallback maximum is not', () => {
    const s = P.antigravity(null, f.models, 'Pro', f.now);
    assert.equal(s.windows[0].remainingPercent, null);
    assert.match(s.notice, /unverified/);
    const verified = P.antigravity({groups: [{buckets: [{remainingFraction: 1}]}]}, null, 'Pro');
    assert.equal(verified.windows[0].remainingPercent, 100);
});
test('Google partial fallback retains known, missing and invalid fractions', () => {
    const data = {models: {a: {quotaInfo: {remainingFraction: 0.4}}, b: {quotaInfo: {}}, c: {quotaInfo: {remainingFraction: 2}}}};
    assert.deepEqual(P.antigravity(null, data, null).windows.map(w => w.remainingPercent), [40, null, null]);
});
test('OpenCode direct 0.5 means 0.5 percent, and relative resets use reading time', () => {
    const s = P.opencode(f.opencode, f.now);
    assert.deepEqual(s.windows.map(w => w.remainingPercent), [99.5, 25, null]);
    assert.equal(s.windows[0].resetAt, new Date(f.now + 60000).toISOString());
});
for (const [name, parse] of [['Claude', P.claude], ['Codex', P.codex], ['OpenCode', P.opencode]]) {
    test(`${name} rejects absent quota instead of fabricating it`, () => {
        for (const value of [null, [], {}, 'body'])
            assert.throws(() => parse(value));
    });
}
for (const value of [null, undefined, '', ' ', true, false, -1, 101, NaN, Infinity, 'unknown']) {
    test(`Invalid utilization ${String(value)} remains unknown`, () => {
        assert.equal(P.claude({five_hour: {utilization: value}}).windows[0].remainingPercent, null);
    });
}
test('Timestamps accept ISO, epoch seconds/milliseconds, reject invalid values', () => {
    assert.equal(P.timestamp('1792836000'), P.timestamp(1792836000000));
    assert.equal(P.timestamp('2026-10-24T10:00:00Z'), P.timestamp(1792836000));
    for (const value of [null, -1, true, '', 'yesterday', Infinity, 'NaN', 1e30])
        assert.equal(P.timestamp(value), null);
});
test('Display explicitly distinguishes unknown, zero, reset pending and stale', () => {
    assert.equal(percentage(null), 'Not reported');
    assert.equal(percentage(0), '0% remaining');
    assert.equal(resetLabel(null), 'Reset not reported');
    assert.equal(resetLabel('2026-10-23T09:00:00Z', f.now), 'Reset pending');
    const state = {id: 'CLAUDE', connected: true, stale: true, snapshot: P.claude(f.claude, f.now)};
    assert.match(panelText(state), /75%\*/);
    assert.match(readingLabel(state), /^Stale reading:/);
    assert.equal(readingLabel({connected: true}), 'Connected · Quota unavailable');
});
