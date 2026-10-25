import {IDS, VERSION} from './config.js';

export const OAUTH_CODES = new Set([
    'invalid_grant', 'invalid_client', 'invalid_request', 'invalid_scope', 'access_denied',
    'unauthorized_client', 'unsupported_grant_type', 'temporarily_unavailable', 'server_error',
    'permission_error', 'authentication_error', 'insufficient_scope', 'rate_limit_exceeded',
    'authorization_pending', 'slow_down', 'expired_token',
]);
export const STAGES = new Set([
    'STORAGE', 'REFRESH', 'QUOTA', 'BROWSER', 'CALLBACK', 'DEVICE_START', 'DEVICE_POLL',
    'TOKEN_EXCHANGE', 'CONNECTED', 'DISCONNECTED', 'CANCELLED', 'KEY_ENTRY',
]);
const NETWORKS = new Set(['DNS', 'TLS', 'TIMEOUT', 'RESTRICTED', 'CONNECTION', 'IO']);
const ENDPOINT_LABELS = new Set([
    'claudeToken', 'claudeUsage', 'codexStart', 'codexPoll', 'codexToken', 'codexUsage',
    'googleToken', 'googleLoad', 'googleSummary', 'googleModels', 'opencodeUsage',
]);
const MESSAGES = Object.freeze({
    UNKNOWN: 'The operation failed. Try again or check the official provider page.',
    FORMAT: 'The provider did not report a supported quota response.',
    HTTP: 'The provider refused the request or is temporarily unavailable.',
    LOGIN: 'Session expired. Reconnect this account.',
    DENIED: 'Authorization denied by the provider.',
    EXPIRED: 'Authorization expired. Start a new connection.',
    OAUTH: 'The provider rejected this OAuth request. Reconnect or check the official page.',
    NETWORK: 'Connection failed. Check the network and try again.',
    KEYRING: 'Desktop keyring unavailable or locked. Unlock Secret Service and retry. No cleartext fallback is used.',
    STORAGE: 'The saved QuotaDeck keyring record could not be read. Disconnect locally and reconnect.',
    CANCELLED: 'Operation cancelled.',
    CALLBACK: 'Invalid authorization response. Start a new connection.',
    BROWSER: 'The system browser could not be opened. Check the desktop browser configuration.',
    INITIALIZE: 'Initialize this account in the official Antigravity desktop app first. QuotaDeck does not create projects or enable billing.',
    CLIENT: 'Google rejected this client for this account. Check the official Antigravity app.',
    KEY: 'Enter an existing OpenCode Go API key in Preferences, without spaces or line breaks.',
    RATE: 'Too many requests. Wait before refreshing.',
});
export class Failure extends Error {
    constructor(kind = 'UNKNOWN', fields = {}) {
        super(MESSAGES[kind] ?? MESSAGES.UNKNOWN);
        this.name = 'QuotaDeckFailure';
        this.kind = Object.hasOwn(MESSAGES, kind) ? kind : 'UNKNOWN';
        this.http = Number.isInteger(fields.http) && fields.http >= 100 && fields.http <= 599 ? fields.http : null;
        this.code = OAUTH_CODES.has(fields.code) ? fields.code : null;
        this.network = NETWORKS.has(fields.network) ? fields.network : null;
        this.endpoint = ENDPOINT_LABELS.has(fields.endpoint) ? fields.endpoint : null;
        this.retryAfterMs = Number.isFinite(fields.retryAfterMs) ? Math.max(0, Math.min(86400000, fields.retryAfterMs)) : 0;
    }
}
export function safeFailure(error) {
    return error instanceof Failure ? error : new Failure();
}
export function diagnostic(id, stage, error, runtime = '') {
    const e = safeFailure(error);
    // Never interpolate exceptions, paths, URLs, headers, bodies, account metadata or free text.
    const version = /^\d+\.\d+(?:\.\d+)?$/.test(runtime) ? runtime : 'not-reported';
    return [
        `QuotaDeck ${VERSION} · GNOME Shell ${version}`,
        `Provider: ${IDS.includes(id) ? id : 'not-reported'}`,
        `Stage: ${STAGES.has(stage) ? stage : 'not-reported'}`,
        `HTTP: ${e.http ?? 'not-reported'}`,
        `Provider code: ${e.code ?? 'not-reported'}`,
        `Network: ${e.network ?? 'not-reported'}`,
        `Endpoint: ${e.endpoint ?? 'not-reported'}`,
        'Safe diagnostic: no credentials, OAuth URLs or account data.',
    ].join('\n');
}
export function retryAfter(value, now = Date.now()) {
    if (typeof value !== 'string' || !value.trim())
        return 0;
    const ms = /^\d+$/.test(value.trim()) ? Number(value.trim()) * 1000 : Date.parse(value) - now;
    return Number.isFinite(ms) ? Math.max(0, Math.min(86400000, ms)) : 0;
}
export function httpFailure(status, data, endpoint, retry = 0) {
    const codes = [data?.error, data?.error?.code, data?.error?.type];
    const code = codes.find(c => OAUTH_CODES.has(c));
    const kind = code === 'access_denied' ? 'DENIED' : code === 'expired_token' ? 'EXPIRED' :
        code && !['authorization_pending', 'slow_down'].includes(code) ? 'OAUTH' :
            status === 401 || status >= 300 && status < 400 ? 'LOGIN' : status === 429 ? 'RATE' : 'HTTP';
    return new Failure(kind, {http: status, code, endpoint, retryAfterMs: retry});
}
