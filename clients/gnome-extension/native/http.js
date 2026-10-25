import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import Soup from 'gi://Soup?version=3.0';
import {ENDPOINTS, VERSION} from '../lib/config.js';
import {Failure, httpFailure, retryAfter} from '../lib/errors.js';
import {query} from '../lib/auth.js';
import {invoke} from './runtime.js';

export function networkFailure(error, endpoint, timedOut = false) {
    const matches = (domain, code) => error?.matches?.(domain, code) === true;
    const network = timedOut || matches(Gio.io_error_quark(), Gio.IOErrorEnum.TIMED_OUT) ? 'TIMEOUT' :
        matches(Gio.resolver_error_quark(), Gio.ResolverError.NOT_FOUND) || matches(Gio.resolver_error_quark(), Gio.ResolverError.TEMPORARY_FAILURE) ? 'DNS' :
            error?.domain === Gio.tls_error_quark() ? 'TLS' :
                matches(Gio.io_error_quark(), Gio.IOErrorEnum.PERMISSION_DENIED) ? 'RESTRICTED' :
                    matches(Gio.io_error_quark(), Gio.IOErrorEnum.CONNECTION_REFUSED) || matches(Gio.io_error_quark(), Gio.IOErrorEnum.HOST_UNREACHABLE) ? 'CONNECTION' : 'IO';
    return new Failure('NETWORK', {network, endpoint});
}

export class Http {
    constructor(endpoints = ENDPOINTS, {allowLoopback = false, timeoutMs = 45000, maxBytes = 4000000} = {}) {
        // Overrides are used ONLY by local fixture tests, never by GSettings/UI.
        this.endpoints = endpoints;
        this.allowLoopback = allowLoopback;
        this.timeoutMs = timeoutMs;
        this.maxBytes = maxBytes;
        this.session = new Soup.Session({timeout: 30, user_agent: `QuotaDeck/${VERSION} GNOME`});
    }

    async request(endpoint, {headers = {}, body, form} = {}, scope) {
        scope.check();
        const url = this.endpoints[endpoint];
        if (typeof url !== 'string' || !(url.startsWith('https://') || this.allowLoopback && /^http:\/\/127\.0\.0\.1:\d+\//.test(url)))
            throw new Failure();
        const msg = Soup.Message.new(body !== undefined || form !== undefined ? 'POST' : 'GET', url);
        // No cookie jar, automatic credentials, cross-origin redirects or implicit POST replay.
        msg.set_flags(Soup.MessageFlags.NO_REDIRECT | Soup.MessageFlags.DO_NOT_USE_AUTH_CACHE | Soup.MessageFlags.NEW_CONNECTION);
        msg.disable_feature(Soup.AuthManager);
        const requestHeaders = msg.get_request_headers();
        requestHeaders.replace('Accept', 'application/json');
        for (const [name, value] of Object.entries(headers))
            requestHeaders.replace(name, value);
        if (body !== undefined || form !== undefined) {
            const payload = form !== undefined ? query(form) : JSON.stringify(body);
            msg.set_request_body_from_bytes(form !== undefined ? 'application/x-www-form-urlencoded' : 'application/json',
                new GLib.Bytes(new TextEncoder().encode(payload)));
        }
        const requestCancel = new Gio.Cancellable();
        const unown = scope.own(() => requestCancel.cancel());
        let timedOut = false;
        let timer = GLib.timeout_add(GLib.PRIORITY_DEFAULT, this.timeoutMs, () => {
            timer = 0;
            timedOut = true;
            requestCancel.cancel();
            return GLib.SOURCE_REMOVE;
        });
        const releaseTimer = scope.own(() => {
            if (timer)
                GLib.Source.remove(timer);
            timer = 0;
        });
        let stream;
        try {
            stream = await invoke(cb => this.session.send_async(msg, GLib.PRIORITY_DEFAULT, requestCancel, cb), r => this.session.send_finish(r));
            // status-code is uint; get_status() is GI-enum-typed and rejects valid
            // unlisted HTTP statuses (including 429 in some libsoup typelibs).
            const status = msg.status_code;
            const cap = status >= 200 && status < 300 ? this.maxBytes : 8192;
            const chunks = [];
            let total = 0;
            while (true) {
                scope.check();
                const bytes = await invoke(cb => stream.read_bytes_async(Math.min(65536, cap - total + 1), GLib.PRIORITY_DEFAULT, requestCancel, cb), r => stream.read_bytes_finish(r));
                const chunk = bytes.toArray();
                if (!chunk.length)
                    break;
                total += chunk.length;
                if (total > cap) {
                    if (status >= 200 && status < 300)
                        throw new Failure('FORMAT', {endpoint});
                    break;
                }
                chunks.push(chunk);
            }
            const data = new Uint8Array(Math.min(total, cap));
            let offset = 0;
            for (const chunk of chunks) {
                data.set(chunk, offset);
                offset += chunk.length;
            }
            let parsed = null;
            try {
                parsed = JSON.parse(new TextDecoder().decode(data.subarray(0, offset)));
            } catch {
                // Raw responses and JSON parse errors never leave transport.
            }
            if (status < 200 || status >= 300)
                throw httpFailure(status, parsed, endpoint, retryAfter(msg.get_response_headers().get_one('Retry-After')));
            if (!parsed || typeof parsed !== 'object')
                throw new Failure('FORMAT', {endpoint});
            scope.check();
            return parsed;
        } catch (error) {
            scope.check();
            if (error instanceof Failure)
                throw error;
            throw networkFailure(error, endpoint, timedOut);
        } finally {
            releaseTimer();
            if (timer)
                GLib.Source.remove(timer);
            timer = 0;
            unown();
            requestCancel.cancel();
            if (stream)
                await invoke(cb => stream.close_async(GLib.PRIORITY_DEFAULT, null, cb), r => stream.close_finish(r)).catch(() => {});
        }
    }

    dispose() {
        this.session.abort();
    }
}
