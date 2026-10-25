import {PROVIDERS} from './config.js';

export function percentage(value) {
    return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100 ? `${Math.round(value * 10) / 10}% remaining` : 'Not reported';
}
export function resetLabel(value, now = Date.now()) {
    if (!value || !Number.isFinite(Date.parse(value)))
        return 'Reset not reported';
    const minutes = Math.ceil((Date.parse(value) - now) / 60000);
    if (minutes <= 0)
        return 'Reset pending';
    const days = Math.floor(minutes / 1440);
    const hours = Math.floor(minutes % 1440 / 60);
    return days ? `Resets in ${days}d ${hours}h` : hours ? `Resets in ${hours}h ${minutes % 60}m` : `Resets in ${minutes}m`;
}
export function readingLabel(state, now = Date.now(), intervalMinutes = 15) {
    if (!state.snapshot)
        return state.connected ? 'Connected · Quota unavailable' : 'Not connected';
    const old = state.stale || now - state.snapshot.fetchedAt > intervalMinutes * 120000;
    return `${old ? 'Stale reading' : 'Last reading'}: ${new Date(state.snapshot.fetchedAt).toLocaleString('en-GB')}`;
}
export function panelText(state, now = Date.now(), intervalMinutes = 15) {
    if (!state)
        return 'QD';
    const value = state.snapshot?.windows?.[0]?.remainingPercent;
    const valid = typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100;
    const stale = state.snapshot && (state.stale || now - state.snapshot.fetchedAt > intervalMinutes * 120000);
    return `${PROVIDERS[state.id]?.title ?? 'QD'} ${valid ? `${Math.round(value)}%` : '—'}${stale ? '*' : ''}${state.loading ? ' …' : ''}`;
}
