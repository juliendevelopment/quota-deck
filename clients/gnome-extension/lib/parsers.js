import {Failure} from './errors.js';

export const object = value => value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null;
export const text = value => typeof value === 'string' && value.length ? value : null;
export function number(value) {
    if (typeof value !== 'number' && (typeof value !== 'string' || !value.trim()))
        return null;
    const n = Number(value);
    return Number.isFinite(n) ? n : null;
}
export const percent = value => value !== null && Number.isFinite(value) && value >= 0 && value <= 100 ? value : null;
export const remaining = value => percent(number(value)) === null ? null : 100 - Number(value);
export function timestamp(value) {
    if (value === null || value === undefined || value === '')
        return null;
    const n = number(value);
    const ms = n === null ? (typeof value === 'string' && /^\d{4}-\d\d-\d\dT/.test(value) ? Date.parse(value) : NaN) : n < 0 ? NaN : n > 100000000000 ? n : n * 1000;
    return Number.isFinite(ms) && Math.abs(ms) <= 8640000000000000 ? new Date(ms).toISOString() : null;
}
const window = (label, remainingPercent = null, resetAt = null, detail = null) => ({label, remainingPercent, resetAt, detail});
function snapshot(provider, windows, now, plan = null, notice = null) {
    if (!windows.length)
        throw new Failure('FORMAT');
    return {provider, plan, identity: null, windows, fetchedAt: now, notice};
}
export function claude(data, now = Date.now()) {
    if (!object(data))
        throw new Failure('FORMAT');
    const windows = [];
    for (const [key, value] of Object.entries(data)) {
        if (object(value) && (key === 'five_hour' || key.startsWith('seven_day'))) {
            const label = key === 'five_hour' ? 'Session · 5 hours' : key === 'seven_day' ? 'Week · all models' : `Week · ${key.replace(/^seven_day_/, '').replaceAll('_', ' ')}`;
            windows.push(window(label, remaining(value.utilization), timestamp(value.resets_at)));
        }
    }
    for (const value of Array.isArray(data.limits) ? data.limits : [])
        windows.push(window(text(value?.scope?.model?.display_name) ?? text(value?.display_name) ?? 'Additional limit', remaining(value?.utilization), timestamp(value?.resets_at)));
    if (data.extra_usage?.is_enabled === true)
        windows.push(window('Extra usage', remaining(data.extra_usage.utilization), null, 'Extra usage is enabled in Claude; this app does not change that setting.'));
    return snapshot('CLAUDE', windows, now);
}
export function codex(data, now = Date.now()) {
    const windows = [];
    const append = (limits, prefix = '') => {
        for (const [key, fallback] of [['primary_window', 'Session'], ['secondary_window', 'Week']]) {
            const lane = object(limits?.[key]);
            if (!lane)
                continue;
            const duration = number(lane.limit_window_seconds);
            const label = duration === 18000 ? 'Session · 5 hours' : duration === 604800 ? 'Week' : fallback;
            windows.push(window(prefix + label, remaining(lane.used_percent), timestamp(lane.reset_at)));
        }
    };
    append(data?.rate_limit);
    for (const item of Array.isArray(data?.additional_rate_limits) ? data.additional_rate_limits : [])
        append(item?.rate_limit, `${text(item?.limit_name) ?? text(item?.metered_feature) ?? 'Other'} · `);
    append(data?.code_review_rate_limit, 'Code review · ');
    if (data?.credits?.balance !== null && data?.credits?.balance !== undefined && ['string', 'number'].includes(typeof data.credits.balance))
        windows.push(window('Credits', null, null, `Reported balance: ${data.credits.balance}`));
    return snapshot('CODEX', windows, now, text(data?.plan_type));
}
export function antigravitySummary(data) {
    const root = object(data?.response) ?? object(data?.summary) ?? data;
    const windows = [];
    for (const group of Array.isArray(root?.groups) ? root.groups : []) {
        for (const bucket of Array.isArray(group?.buckets) ? group.buckets : []) {
            const fraction = number(bucket?.remainingFraction) ?? number(bucket?.remaining?.remainingFraction) ?? (bucket?.remaining?.case === 'remainingFraction' ? number(bucket.remaining.value) : null);
            const label = [text(group?.displayName) ?? text(group?.name), text(bucket?.displayName) ?? text(bucket?.name) ?? text(bucket?.bucketId)].filter(Boolean).join(' · ') || 'Quota';
            windows.push(window(label, bucket?.disabled === true || fraction === null ? null : percent(fraction * 100), timestamp(bucket?.resetTime), bucket?.disabled === true ? 'Not available on this plan' : text(bucket?.description)));
        }
    }
    return windows;
}
export function antigravityModels(data) {
    const windows = [];
    for (const [id, model] of Object.entries(object(data?.models) ?? {})) {
        if (!object(model?.quotaInfo))
            continue;
        const fraction = number(model.quotaInfo.remainingFraction);
        windows.push(window(text(model.displayName) ?? id, fraction === null ? null : percent(fraction * 100), timestamp(model.quotaInfo.resetTime)));
    }
    return windows;
}
export function antigravity(data, fallback, plan, now = Date.now()) {
    let windows = antigravitySummary(data);
    let notice = null;
    if (!windows.length) {
        windows = antigravityModels(fallback);
        if (windows.length && windows.every(w => w.remainingPercent !== null && w.remainingPercent >= 99.9)) {
            windows = windows.map(w => ({...w, remainingPercent: null}));
            notice = 'Google returned unverified maximum values. Check the official page.';
        }
    }
    return snapshot('ANTIGRAVITY', windows, now, plan, notice);
}
export function opencode(data, now = Date.now()) {
    const windows = [];
    for (const [key, label] of [['rolling', 'Session · 5 hours'], ['weekly', 'Week'], ['monthly', 'Month']]) {
        const meter = object(data?.usage?.[key]);
        if (!meter)
            continue;
        const first = keys => keys.map(k => number(meter[k])).find(n => n !== null) ?? null;
        const used = first(['usedMicroCents', 'used', 'usage', 'consumed', 'count', 'usedTokens']);
        const limit = first(['limitMicroCents', 'limit', 'total', 'quota', 'max', 'cap', 'tokenLimit']);
        const direct = first(['usagePercent', 'usedPercent', 'percentUsed', 'percent', 'usage_percent', 'used_percent', 'utilization']);
        const usedPercent = used !== null && limit !== null && limit > 0 ? used / limit * 100 : direct;
        let reset = ['resetsAt', 'resetAt', 'reset_at', 'resets_at'].map(k => timestamp(meter[k])).find(Boolean) ?? null;
        const seconds = first(['resetInSec', 'resetInSeconds', 'reset_in_sec']);
        if (!reset && seconds !== null && seconds >= 0)
            reset = timestamp((now + seconds * 1000) / 1000);
        windows.push(window(label, remaining(usedPercent), reset));
    }
    return snapshot('OPENCODE', windows, now, 'Go');
}
