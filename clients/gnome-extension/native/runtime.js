import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import {Failure} from '../lib/errors.js';
import {Loopback} from './loopback.js';

// All explicit sources/cancellables/listeners belong to one cancellable operation.
export class Scope {
    constructor() {
        this.cancellable = new Gio.Cancellable();
        this.cleanups = new Set();
        this.cancelled = false;
        this.reason = new Failure('CANCELLED');
    }

    check() {
        if (this.cancelled)
            throw this.reason;
    }

    own(cleanup) {
        this.check();
        this.cleanups.add(cleanup);
        return () => this.cleanups.delete(cleanup);
    }

    expire(ms) {
        this.check();
        const id = GLib.timeout_add(GLib.PRIORITY_DEFAULT, Math.max(1, Math.ceil(ms)), () => {
            release();
            this.cancel(new Failure('EXPIRED', {code: 'expired_token'}));
            return GLib.SOURCE_REMOVE;
        });
        const cleanup = () => GLib.Source.remove(id);
        const release = this.own(cleanup);
        return () => {
            if (this.cleanups.delete(cleanup))
                cleanup();
        };
    }

    sleep(ms) {
        this.check();
        return new Promise((resolve, reject) => {
            const id = GLib.timeout_add(GLib.PRIORITY_DEFAULT, Math.max(1, Math.ceil(ms)), () => {
                release();
                resolve();
                return GLib.SOURCE_REMOVE;
            });
            const release = this.own(() => {
                GLib.Source.remove(id);
                reject(this.reason);
            });
        });
    }

    cancel(reason = new Failure('CANCELLED')) {
        if (this.cancelled)
            return;
        this.cancelled = true;
        this.reason = reason;
        this.cancellable.cancel();
        this.dispose();
    }

    dispose() {
        const cleanups = [...this.cleanups];
        this.cleanups.clear();
        for (const cleanup of cleanups)
            cleanup();
    }
}

export function invoke(start, finish) {
    return new Promise((resolve, reject) => {
        start((_source, result) => {
            try {
                resolve(finish(result));
            } catch (error) {
                reject(error);
            }
        });
    });
}

export class Runtime {
    now() { return Date.now(); }
    monotonic() { return GLib.get_monotonic_time() / 1000; }
    scope() { return new Scope(); }
    receiver(id, state, scope) { return new Loopback(id, state, scope); }
    decodeBase64(value) {
        const base = value.replaceAll('-', '+').replaceAll('_', '/');
        return new TextDecoder().decode(GLib.base64_decode(base + '='.repeat((4 - base.length % 4) % 4)));
    }

    async random(scope) {
        scope.check();
        // OS CSPRNG, not GLib.random_* or Math.random. Read asynchronously in Shell.
        const file = Gio.File.new_for_path('/dev/urandom');
        let stream;
        try {
            stream = await invoke(cb => file.read_async(GLib.PRIORITY_DEFAULT, scope.cancellable, cb), r => file.read_finish(r));
            const bytes = await invoke(cb => stream.read_bytes_async(32, GLib.PRIORITY_DEFAULT, scope.cancellable, cb), r => stream.read_bytes_finish(r));
            scope.check();
            if (bytes.get_size() !== 32)
                throw new Failure('OAUTH');
            return GLib.base64_encode(bytes.toArray()).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
        } catch {
            scope.check();
            throw new Failure('OAUTH');
        } finally {
            if (stream)
                await invoke(cb => stream.close_async(GLib.PRIORITY_DEFAULT, null, cb), r => stream.close_finish(r)).catch(() => {});
        }
    }

    challenge(verifier) {
        const hex = GLib.compute_checksum_for_string(GLib.ChecksumType.SHA256, verifier, -1);
        const bytes = Uint8Array.from(hex.match(/../g), s => parseInt(s, 16));
        return GLib.base64_encode(bytes).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
    }

    async openBrowser(uri, scope) {
        scope.check();
        try {
            await invoke(cb => Gio.AppInfo.launch_default_for_uri_async(uri, null, scope.cancellable, cb),
                r => Gio.AppInfo.launch_default_for_uri_finish(r));
            scope.check();
        } catch {
            scope.check();
            throw new Failure('BROWSER');
        }
    }
}
