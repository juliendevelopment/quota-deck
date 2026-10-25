import GLib from 'gi://GLib';
import Soup from 'gi://Soup?version=3.0';
import {Failure} from '../lib/errors.js';

export function callbackQuery(raw) {
    if (typeof raw !== 'string' || raw.length > 8192)
        throw new Failure('CALLBACK');
    const fields = Object.create(null);
    try {
        for (const part of raw.split('&')) {
            const pos = part.indexOf('=');
            if (pos < 0)
                continue;
            const decode = s => decodeURIComponent(s.replaceAll('+', ' '));
            const key = decode(part.slice(0, pos));
            if (Object.hasOwn(fields, key))
                throw new Failure('CALLBACK');
            fields[key] = decode(part.slice(pos + 1));
        }
    } catch {
        throw new Failure('CALLBACK');
    }
    return fields;
}

// Only the desktop running consent listens, on IPv4 loopback and an ephemeral port.
export class Loopback {
    constructor(id, state, scope) {
        scope.check();
        this.server = new Soup.Server();
        this.done = false;
        this.closed = false;
        this.uri = '';
        this.receive = new Promise((resolve, reject) => {
            this.resolve = resolve;
            this.reject = reject;
        });
        this.receive.catch(() => {});
        this.release = scope.own(() => this.close(scope.reason));
        const path = id === 'CLAUDE' ? '/callback' : '/oauth-callback';
        this.server.add_handler('/', (_server, msg, requestPath) => {
            let valid = false;
            let failure = null;
            let code;
            try {
                const uri = msg.get_uri();
                const expectedHost = id === 'CLAUDE' ? 'localhost' : '127.0.0.1';
                const host = msg.get_request_headers().get_one('Host');
                if (this.done || msg.get_method() !== 'GET' || requestPath !== path ||
                    uri.get_host() !== expectedHost || host !== `${expectedHost}:${this.port}`)
                    throw new Failure('CALLBACK');
                const fields = callbackQuery(uri.get_query());
                if (fields.state !== state)
                    throw new Failure('CALLBACK');
                if (fields.error)
                    failure = new Failure(fields.error === 'access_denied' ? 'DENIED' : 'OAUTH', {code: fields.error});
                valid = !fields.error && typeof fields.code === 'string' && fields.code.length > 0;
                code = fields.code;
            } catch {
                // Invalid unsolicited requests never terminate a legitimate pending authorization.
            }
            const headers = msg.get_response_headers();
            headers.replace('Cache-Control', 'no-store');
            headers.replace('Referrer-Policy', 'no-referrer');
            headers.replace('Content-Security-Policy', "default-src 'none'; base-uri 'none'; frame-ancestors 'none'");
            headers.replace('X-Content-Type-Options', 'nosniff');
            const title = valid ? 'Authorization received' : 'Invalid authorization';
            const explanation = valid ? 'Return to QuotaDeck Preferences. Receiving this code does not yet confirm a successful token exchange or quota reading.' : 'Return to QuotaDeck Preferences to check or restart sign-in.';
            msg.set_status(valid ? 200 : 400, null);
            msg.set_response('text/html; charset=utf-8', Soup.MemoryUse.COPY,
                new TextEncoder().encode(`<!doctype html><html lang="en"><head><meta charset="utf-8"><title>QuotaDeck</title></head><body><h1>${title}</h1><p>${explanation}</p></body></html>`));
            if (valid || failure) {
                this.done = true;
                if (valid)
                    this.resolve(code);
                else
                    this.reject(failure);
            }
        });
        try {
            this.server.listen_local(0, Soup.ServerListenOptions.IPV4_ONLY);
            this.port = this.server.get_uris()[0].get_port();
            this.uri = `http://${id === 'CLAUDE' ? 'localhost' : '127.0.0.1'}:${this.port}${path}`;
            this.timeout = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, 300, () => {
                this.timeout = 0;
                this.close(new Failure('EXPIRED'));
                return GLib.SOURCE_REMOVE;
            });
        } catch {
            this.close(new Failure('CALLBACK'));
            throw new Failure('CALLBACK');
        }
    }

    close(reason = new Failure('CANCELLED')) {
        if (this.closed)
            return;
        this.closed = true;
        this.release?.();
        if (this.timeout)
            GLib.Source.remove(this.timeout);
        this.timeout = 0;
        this.server.disconnect();
        this.server.remove_handler('/');
        this.server = null;
        if (!this.done)
            this.reject(reason);
        this.done = true;
        this.resolve = null;
        this.reject = null;
    }
}
