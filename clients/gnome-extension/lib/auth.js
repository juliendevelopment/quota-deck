import {OAUTH, ENDPOINTS} from './config.js';
import {Failure} from './errors.js';
import {number, text} from './parsers.js';

export const query = fields => Object.entries(fields).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
const retryCodes = new Set(['authorization_pending', 'slow_down', 'temporarily_unavailable', 'server_error']);

export class Auth {
    constructor(http, runtime) {
        this.http = http;
        this.runtime = runtime;
    }

    credential(data) {
        const accessToken = text(data?.access_token);
        if (!accessToken)
            throw new Failure('OAUTH');
        const claims = token => {
            try {
                const payload = token?.split('.')[1];
                return payload ? JSON.parse(this.runtime.decodeBase64(payload)) : null;
            } catch {
                return null;
            }
        };
        // ID-token metadata is context, NOT a local validation of the token's authenticity.
        // Opaque access tokens are valid; provider quota endpoints authenticate them.
        const accountId = text(claims(data.id_token)?.['https://api.openai.com/auth']?.chatgpt_account_id) ??
            text(claims(accessToken)?.['https://api.openai.com/auth']?.chatgpt_account_id) ?? '';
        const seconds = number(data.expires_in);
        return {accessToken, refreshToken: text(data.refresh_token) ?? '',
            expiresAt: this.runtime.now() + (seconds !== null && seconds >= 0 ? seconds : 3600) * 1000,
            accountId, projectId: ''};
    }

    async connect(id, scope, notify) {
        if (id === 'CODEX')
            return this.codex(scope, notify);
        if (!['CLAUDE', 'ANTIGRAVITY'].includes(id))
            throw new Failure('KEY');
        const state = await this.runtime.random(scope);
        const verifier = await this.runtime.random(scope);
        scope.check();
        const receiver = this.runtime.receiver(id, state, scope);
        // Attach rejection handling immediately: a cancelled browser launch can reject receive first.
        const received = receiver.receive;
        received.catch(() => {});
        try {
            const common = {client_id: id === 'CLAUDE' ? OAUTH.claudeClient : OAUTH.googleClient,
                redirect_uri: receiver.uri, response_type: 'code',
                scope: id === 'CLAUDE' ? 'user:profile' : OAUTH.googleScopes.join(' '),
                state, code_challenge: this.runtime.challenge(verifier), code_challenge_method: 'S256'};
            const fields = id === 'CLAUDE' ? {...common, code: 'true'} : {...common, access_type: 'offline', prompt: 'select_account consent'};
            const base = ENDPOINTS[id === 'CLAUDE' ? 'claudeAuthorize' : 'googleAuthorize'];
            notify('BROWSER');
            await this.runtime.openBrowser(`${base}?${query(fields)}`, scope);
            notify('CALLBACK');
            const code = await received;
            scope.check();
            notify('TOKEN_EXCHANGE');
            // No retries: authorization codes are single use, even after an uncertain response.
            const data = id === 'CLAUDE' ? await this.http.request('claudeToken', {
                body: {grant_type: 'authorization_code', code, redirect_uri: receiver.uri,
                    client_id: OAUTH.claudeClient, state, code_verifier: verifier},
            }, scope) : await this.http.request('googleToken', {
                form: {grant_type: 'authorization_code', client_id: OAUTH.googleClient,
                    client_secret: OAUTH.googleInstalledValue, code, redirect_uri: receiver.uri, code_verifier: verifier},
            }, scope);
            scope.check();
            return this.credential(data);
        } finally {
            receiver.close();
        }
    }

    async codex(scope, notify) {
        notify('DEVICE_START');
        const data = await this.http.request('codexStart', {body: {client_id: OAUTH.openaiClient}}, scope);
        const deviceId = text(data?.device_auth_id);
        const code = text(data?.user_code) ?? text(data?.usercode);
        if (!deviceId || !code)
            throw new Failure('OAUTH');
        const seconds = number(data.interval);
        const baseInterval = Math.max(5, Math.min(900, seconds ?? 5)) * 1000;
        const ttl = Math.max(0, Math.min(900, number(data.expires_in) ?? 900)) * 1000;
        const deadline = this.runtime.monotonic() + ttl;
        const clearDeadline = scope.expire(ttl);
        let interval = baseInterval;
        let failures = 0;
        const remaining = () => Math.max(0, deadline - this.runtime.monotonic());
        const check = () => {
            scope.check();
            if (!remaining())
                throw new Failure('EXPIRED', {code: 'expired_token'});
        };
        try {
            notify('DEVICE_POLL', code);
            await this.runtime.openBrowser('https://auth.openai.com/codex/device', scope);
            // Wait before the first poll as well as subsequent polls.
            await scope.sleep(Math.min(interval, remaining()));
            while (remaining() > 0) {
                check();
                let grant;
                try {
                    grant = await this.http.request('codexPoll', {body: {device_auth_id: deviceId, user_code: code}}, scope);
                } catch (error) {
                    check();
                    if (!(error instanceof Failure) || error.code && !retryCodes.has(error.code))
                        throw error;
                    const pending = error.code === 'authorization_pending' || [403, 404].includes(error.http);
                    const retryable = error.network || [408, 429].includes(error.http) || error.http >= 500 ||
                        ['slow_down', 'temporarily_unavailable', 'server_error'].includes(error.code);
                    if (!pending && !retryable)
                        throw error;
                    if (error.http === 429 || error.code === 'slow_down')
                        interval = Math.min(900000, interval + 5000);
                    const backoff = retryable ? Math.max(interval, Math.min(Math.max(baseInterval, 60000), baseInterval * 2 ** Math.min(failures++, 4))) : interval;
                    if (!retryable)
                        failures = 0;
                    await scope.sleep(Math.min(remaining(), Math.max(backoff, error.retryAfterMs)));
                    continue;
                }
                check();
                if (!text(grant?.authorization_code) || !text(grant?.code_verifier))
                    throw new Failure('OAUTH');
                notify('TOKEN_EXCHANGE');
                // Deliberately OUTSIDE the poll catch. Never replay the final exchange.
                const tokens = await this.http.request('codexToken', {form: {
                    grant_type: 'authorization_code', client_id: OAUTH.openaiClient,
                    code: grant.authorization_code, code_verifier: grant.code_verifier,
                    redirect_uri: 'https://auth.openai.com/deviceauth/callback',
                }}, scope);
                check();
                return this.credential(tokens);
            }
            throw new Failure('EXPIRED', {code: 'expired_token'});
        } finally {
            clearDeadline();
            notify(null); // Remove the transient device code without changing the failure stage.
        }
    }

    async refresh(id, old, scope) {
        if (id === 'OPENCODE')
            return old;
        if (!old.refreshToken)
            throw new Failure('LOGIN');
        const fields = {grant_type: 'refresh_token', refresh_token: old.refreshToken};
        const data = id === 'CLAUDE' ? await this.http.request('claudeToken', {body: {
            ...fields, client_id: OAUTH.claudeClient, scope: 'user:profile',
        }}, scope) : id === 'CODEX' ? await this.http.request('codexToken', {body: {
            ...fields, client_id: OAUTH.openaiClient,
        }}, scope) : await this.http.request('googleToken', {form: {
            ...fields, client_id: OAUTH.googleClient, client_secret: OAUTH.googleInstalledValue,
        }}, scope);
        scope.check();
        const next = this.credential(data);
        return {...next, refreshToken: next.refreshToken || old.refreshToken,
            accountId: old.accountId || next.accountId, projectId: old.projectId};
    }
}
