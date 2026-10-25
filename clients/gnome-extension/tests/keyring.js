// Run ONLY in an isolated HOME/XDG/session bus created by scripts/verify.py.
import GLib from 'gi://GLib';
import Gio from 'gi://Gio';
import {Store} from '../native/store.js';
import {Runtime} from '../native/runtime.js';
if (GLib.getenv('QUOTADECK_ISOLATED_TEST') !== '1')
    throw new Error('Refusing to exercise a non-test keyring');
const store = new Store();
const runtime = new Runtime();
const scope = runtime.scope();
if (ARGV.includes('--unavailable')) {
    let error;
    try { await store.load('CLAUDE', scope); } catch (e) { error = e; }
    if (error?.kind !== 'KEYRING')
        throw new Error('Unavailable keyring must fail explicitly');
    print('Secret Service unavailable: 1 passed; no cleartext fallback.');
} else {
    const record = {credential: {accessToken: 'synthetic-private-access', refreshToken: 'synthetic-private-refresh', expiresAt: 9999999999999},
        snapshot: {provider: 'CLAUDE', windows: [{label: 'Synthetic', remainingPercent: null}], fetchedAt: 1234}};
    if (await store.load('CLAUDE', scope) !== null)
        throw new Error('Test keyring not initially empty');
    await store.save('CLAUDE', record, scope);
    const keyring = Gio.File.new_for_path(`${GLib.getenv('XDG_DATA_HOME')}/keyrings/login.keyring`);
    const bytes = keyring.load_contents(null)[1];
    const disk = new TextDecoder().decode(bytes);
    if (disk.includes(record.credential.accessToken) || disk.includes(record.credential.refreshToken))
        throw new Error('Synthetic credentials persisted as cleartext');
    const another = new Store();
    const loaded = await another.load('CLAUDE', scope);
    if (JSON.stringify(loaded) !== JSON.stringify(record))
        throw new Error('Secure persistence read-back mismatch');
    await another.clear('CLAUDE', scope);
    if (await store.load('CLAUDE', scope) !== null)
        throw new Error('Local disconnect record still present');
    another.dispose();
    print('Real isolated Secret Service: 5 passed (empty, save, encrypted file, cross-instance read-back, delete).');
}
scope.dispose();
store.dispose();
