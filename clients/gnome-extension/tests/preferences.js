// Real GTK4/libadwaita widget test with explicitly synthetic D-Bus state.
import Adw from 'gi://Adw?version=1';
import Gio from 'gi://Gio';
import {Preferences} from '../ui/preferences.js';
import {IDS} from '../lib/config.js';
import {Runtime} from '../native/runtime.js';

Adw.init();
let count = 0;
function assert(value, name) {
    if (!value)
        throw new Error(name);
    print(`ok ${++count} - ${name}`);
}
const client = {
    calls: [],
    async start(changed) { this.changed = changed; },
    async call(method, value) {
        this.calls.push({method, value});
        if (method === 'GetDeviceCode')
            return 'SYNTHETIC-CODE';
        if (method === 'GetState')
            return JSON.stringify(IDS.map(id => ({id, connected: true, loading: id === 'CODEX', stage: id === 'CODEX' ? 'DEVICE_POLL' : 'CONNECTED',
                snapshot: {provider: id, plan: 'User <Plan>', identity: 'User Workspace', fetchedAt: 1792749600000,
                    windows: [{label: 'User <Model>', remainingPercent: null, resetAt: null}]}, diagnostic: null})));
        return undefined;
    },
    dispose() { this.disposed = true; },
};
const settings = new Gio.Settings({schema_id: 'org.gnome.shell.extensions.quotadeck'});
const window = new Adw.PreferencesWindow();
const prefs = new Preferences(window, settings, client);
window.present();
await prefs.update();
await new Runtime().scope().sleep(50);
assert(prefs.rows.size === 4, 'All four native account groups constructed');
assert(window.get_visible(), 'Real preferences window mapped under Xvfb');
assert(prefs.rows.get('CLAUDE').state.subtitle.includes('User <Plan>'), 'Provider-owned plan preserved as literal text');
assert(!prefs.rows.get('CLAUDE').state.use_markup, 'Provider text is not markup');
assert(prefs.rows.get('CLAUDE').children[0].title.includes('Not reported'), 'Unknown quota displayed, not assumed full');
assert(prefs.rows.get('CODEX').code.label === 'SYNTHETIC-CODE', 'Transient device code shown only in Preferences');
prefs.providerRow.selected = 2;
assert(settings.get_string('panel-provider') === 'ANTIGRAVITY', 'Panel-provider selector updates non-secret GSettings');
settings.set_string('panel-provider', 'CODEX');
assert(prefs.providerRow.selected === 1, 'External GSettings update reflected in native preferences');
const consent = prefs.confirmConnect('ANTIGRAVITY');
await new Runtime().scope().sleep(20);
const dialog = window.get_visible_dialog();
assert(Boolean(dialog) && dialog.body.includes('cloud-platform'), 'Google consent discloses technical scopes before connecting');
dialog.emit('response', 'connect');
await consent;
assert(client.calls.some(c => c.method === 'Connect' && c.value === 'ANTIGRAVITY'), 'Explicit native consent triggers the connection action');
const disconnect = prefs.confirmDisconnect('CLAUDE');
await new Runtime().scope().sleep(20);
window.get_visible_dialog().emit('response', 'disconnect');
await disconnect;
assert(client.calls.some(c => c.method === 'Disconnect' && c.value === 'CLAUDE'), 'Native destructive confirmation performs only local disconnect');
prefs.rows.get('OPENCODE').key.set_text('synthetic-existing-key');
prefs.started.add('CODEX');
await prefs.dispose();
assert(client.calls.some(c => c.method === 'Cancel' && c.value === 'CODEX'), 'Closing Preferences explicitly cancels active authorization');
assert(client.disposed && prefs.rows.size === 0, 'Proxy signals and UI references released');
window.destroy();
print(`Native GTK preferences: ${count} passed; synthetic state, no account consent.`);
