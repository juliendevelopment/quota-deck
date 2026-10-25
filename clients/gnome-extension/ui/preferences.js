import Adw from 'gi://Adw?version=1';
import Gtk from 'gi://Gtk?version=4.0';
import Gio from 'gi://Gio';
import {IDS, PROVIDERS, OAUTH, VERSION} from '../lib/config.js';
import {percentage, resetLabel, readingLabel} from '../lib/display.js';
import {BridgeClient} from '../native/bridge.js';
import {Runtime, invoke} from '../native/runtime.js';

const row = (title, subtitle = '') => new Adw.ActionRow({title, subtitle, use_markup: false, subtitle_lines: 0});
function button(label, action) {
    const widget = new Gtk.Button({label, valign: Gtk.Align.CENTER});
    widget.connect('clicked', action);
    return widget;
}

export class Preferences {
    constructor(window, settings, client = new BridgeClient()) {
        Object.assign(this, {window, settings, client});
        this.alive = true;
        this.fetching = false;
        this.dirty = false;
        this.rows = new Map();
        this.started = new Set();
        this.observedWorking = new Set();
        this.runtime = new Runtime();
        this.browserScopes = new Set();
        window.set_default_size(760, 820);
        window.set_title('QuotaDeck');
        this.buildAccounts();
        this.buildSettings();
        this.buildPrivacy();
        this.closeSignal = window.connect('close-request', () => {
            this.dispose();
            return false;
        });
        client.start(() => this.update()).catch(() => this.unavailable());
    }

    buildAccounts() {
        const page = new Adw.PreferencesPage({title: 'Accounts', icon_name: 'avatar-default-symbolic'});
        this.window.add(page);
        const status = new Adw.PreferencesGroup({title: 'Local account sessions',
            description: 'Independent from Android and other apps. Enable the extension to connect accounts. No account is preconnected.'});
        page.add(status);
        this.statusRow = row('Connecting to the extension…');
        status.add(this.statusRow);
        this.refreshAll = button('Refresh all', () => this.action('Refresh', 'ALL'));
        this.statusRow.add_suffix(this.refreshAll);
        for (const id of IDS) {
            const group = new Adw.PreferencesGroup({title: PROVIDERS[id].title});
            page.add(group);
            const state = row('Not connected');
            group.add(state);
            const connect = button('Connect', () => this.confirmConnect(id));
            const disconnect = button('Disconnect locally', () => this.confirmDisconnect(id));
            state.add_suffix(connect);
            state.add_suffix(disconnect);
            const actions = row('Account actions');
            group.add(actions);
            const refresh = button('Refresh', () => this.action('Refresh', id));
            const cancel = button('Cancel', () => this.action('Cancel', id));
            const official = button('Official page', () => this.openOfficial(id));
            const copy = button('Copy diagnostic', () => {
                const diagnostic = this.rows.get(id)?.diagnostic;
                if (diagnostic)
                    this.window.get_clipboard().set_text(diagnostic);
            });
            for (const b of [refresh, cancel, official, copy])
                actions.add_suffix(b);
            const details = new Adw.ExpanderRow({title: 'Quota windows', use_markup: false});
            group.add(details);
            const codeRow = row('Device code', 'Complete approval in the system browser. Codes expire; never send them in chat.');
            const code = new Gtk.Label({label: '', selectable: true, visible: true, valign: Gtk.Align.CENTER});
            codeRow.add_suffix(code);
            codeRow.visible = false;
            group.add(codeRow);
            let key = null;
            if (id === 'OPENCODE') {
                key = new Adw.PasswordEntryRow({title: 'Existing Go API key', use_markup: false, show_apply_button: false});
                group.add(key);
                const save = button('Connect with key', () => {
                    const value = key.get_text();
                    key.set_text('');
                    this.started.add(id);
                    this.action('SubmitKey', value);
                });
                key.add_suffix(save);
            }
            this.rows.set(id, {state, connect, disconnect, refresh, cancel, copy, details, children: [],
                codeRow, code, key, diagnostic: null});
        }
    }

    buildSettings() {
        const page = new Adw.PreferencesPage({title: 'Appearance & Refresh', icon_name: 'preferences-system-symbolic'});
        this.window.add(page);
        const group = new Adw.PreferencesGroup({title: 'Non-secret preferences'});
        page.add(group);
        const automatic = new Adw.SwitchRow({title: 'Automatic refresh', subtitle: 'Refresh connected accounts while the extension is enabled.'});
        group.add(automatic);
        this.settings.bind('automatic-refresh', automatic, 'active', Gio.SettingsBindFlags.DEFAULT);
        const interval = new Adw.SpinRow({title: 'Refresh interval (minutes)',
            adjustment: new Gtk.Adjustment({lower: 5, upper: 1440, step_increment: 5, page_increment: 60})});
        group.add(interval);
        this.settings.bind('refresh-minutes', interval, 'value', Gio.SettingsBindFlags.DEFAULT);
        const identity = new Adw.SwitchRow({title: 'Show account context in the menu', subtitle: 'Account names are never included in copied diagnostics.'});
        group.add(identity);
        this.settings.bind('show-identity', identity, 'active', Gio.SettingsBindFlags.DEFAULT);
        this.providerRow = new Adw.ComboRow({title: 'Panel quota', subtitle: 'Shows the selected provider’s first window; * marks stale data.',
            model: Gtk.StringList.new(IDS.map(id => PROVIDERS[id].title))});
        group.add(this.providerRow);
        this.providerRow.selected = IDS.indexOf(this.settings.get_string('panel-provider'));
        this.providerSignal = this.providerRow.connect('notify::selected', () => {
            this.settings.set_string('panel-provider', IDS[this.providerRow.selected]);
        });
        this.settingsSignal = this.settings.connect('changed::panel-provider', () => {
            this.providerRow.selected = IDS.indexOf(this.settings.get_string('panel-provider'));
        });
    }

    buildPrivacy() {
        const page = new Adw.PreferencesPage({title: 'Privacy & About', icon_name: 'dialog-information-symbolic'});
        this.window.add(page);
        const group = new Adw.PreferencesGroup({title: `QuotaDeck ${VERSION} · Unofficial alpha`});
        page.add(group);
        for (const [title, description] of [
            ['Direct quota reads only', 'No QuotaDeck server, model generation, telemetry, purchases, project creation or billing activation. Provider interfaces may change or refuse this client.'],
            ['Desktop Secret Service', 'Refreshable credentials, API keys and cached readings are stored only in this client’s desktop keyring records. Unlock your keyring first. There is no cleartext fallback or cross-device credential store. GSettings stores only appearance and refresh preferences.'],
            ['Explicit browser consent', 'No browser cookies, CLI credentials, Android sessions or unrelated app accounts are imported. Only connect accounts you choose. Passwords are entered on official provider pages, never in QuotaDeck.'],
            ['Google technical scopes', `Antigravity requires an already initialized desktop account. Installed-client scopes: ${OAUTH.googleScopes.join(' · ')}. QuotaDeck uses them only to read existing account quota data.`],
            ['Clipboard and diagnostics', 'Copy diagnostic writes only allowlisted provider, stage, HTTP/OAuth code, network category, endpoint label and version. No raw error, body, header, URL, account ID or credential. The clipboard is never read. Device codes and API-key fields are never added to diagnostics. Desktop screenshot/clipboard managers are outside this app’s control.'],
            ['Disconnect and cancellation', 'Disconnect deletes only this client’s local keyring record and cached reading. Revoke provider authorization separately in provider settings. Closing Preferences cancels connections started there; disabling the extension cancels all pending work and local listeners.'],
            ['Unknown is not unlimited', 'Missing values remain Not reported. Failed refreshes retain the last reading marked stale. Unverified all-maximum Antigravity fallback values are hidden. A connected session is not proof of quota access.'],
        ])
            group.add(row(title, description));
    }

    async confirmConnect(id) {
        if (id === 'OPENCODE') {
            await this.openOfficial(id);
            this.rows.get(id).key.grab_focus();
            return;
        }
        const descriptions = {
            CLAUDE: 'Open the system browser using the public Claude Code installed-client configuration, PKCE and state. Scope: user:profile only. This unofficial client may be refused. No model calls are made.',
            CODEX: 'Open the system browser and approve the device code shown here. Device sign-in may need enabling in your ChatGPT security settings. Polling expires within 15 minutes and can be cancelled.',
            ANTIGRAVITY: `Use an account already initialized in Antigravity. No onboarding, cloud project creation or billing changes. Google scopes: ${OAUTH.googleScopes.join(' · ')}. Consent returns to this desktop’s local loopback listener.`,
        };
        const dialog = new Adw.AlertDialog({heading: `Connect ${PROVIDERS[id].title}?`, body: descriptions[id]});
        dialog.add_response('cancel', 'Cancel');
        dialog.add_response('connect', 'Open browser');
        dialog.set_default_response('cancel');
        dialog.set_close_response('cancel');
        const answer = await invoke(cb => dialog.choose(this.window, null, cb), r => dialog.choose_finish(r));
        if (this.alive && answer === 'connect') {
            this.started.add(id);
            await this.action('Connect', id);
        }
    }

    async confirmDisconnect(id) {
        const dialog = new Adw.AlertDialog({heading: `Disconnect ${PROVIDERS[id].title} locally?`,
            body: 'Delete this GNOME client’s saved session and cached reading. Other apps and provider-side authorization are unchanged.'});
        dialog.add_response('cancel', 'Cancel');
        dialog.add_response('disconnect', 'Disconnect locally');
        dialog.set_response_appearance('disconnect', Adw.ResponseAppearance.DESTRUCTIVE);
        dialog.set_close_response('cancel');
        const answer = await invoke(cb => dialog.choose(this.window, null, cb), r => dialog.choose_finish(r));
        if (this.alive && answer === 'disconnect')
            await this.action('Disconnect', id);
    }

    async openOfficial(id) {
        const scope = this.runtime.scope();
        this.browserScopes.add(scope);
        try {
            await this.runtime.openBrowser(PROVIDERS[id].home, scope);
        } catch {
            if (this.alive)
                this.statusRow.subtitle = 'The system browser could not be opened.';
        } finally {
            scope.dispose();
            this.browserScopes.delete(scope);
        }
    }

    async action(method, value) {
        try {
            await this.client.call(method, value);
        } catch {
            this.unavailable();
        }
    }

    unavailable() {
        if (!this.alive)
            return;
        this.statusRow.title = 'Extension unavailable';
        this.statusRow.subtitle = 'Enable QuotaDeck in GNOME Extensions. Accounts cannot be connected while it is disabled.';
        this.refreshAll.sensitive = false;
        for (const widgets of this.rows.values()) {
            widgets.code.label = '';
            widgets.codeRow.visible = false;
            for (const b of [widgets.connect, widgets.disconnect, widgets.refresh, widgets.cancel])
                b.sensitive = false;
            if (widgets.key)
                widgets.key.sensitive = false;
        }
    }

    async update() {
        if (!this.alive)
            return;
        if (this.fetching) {
            this.dirty = true;
            return;
        }
        this.fetching = true;
        try {
            const states = JSON.parse(await this.client.call('GetState'));
            if (!this.alive)
                return;
            this.statusRow.title = 'Extension enabled';
            this.statusRow.subtitle = 'Only this desktop’s QuotaDeck sessions are used.';
            this.refreshAll.sensitive = true;
            for (const state of states) {
                const widgets = this.rows.get(state.id);
                if (!widgets)
                    continue;
                widgets.state.title = readingLabel(state, Date.now(), this.settings.get_uint('refresh-minutes'));
                widgets.state.subtitle = [state.loading ? `Working · ${state.stage}` : state.stage,
                    state.snapshot?.plan, state.snapshot?.identity, state.error, state.snapshot?.notice].filter(Boolean).join(' · ');
                widgets.connect.label = state.connected ? 'Reconnect' : 'Connect';
                widgets.connect.sensitive = !state.loading;
                widgets.disconnect.sensitive = state.connected;
                widgets.refresh.sensitive = state.connected && !state.loading;
                widgets.cancel.sensitive = state.loading;
                if (widgets.key)
                    widgets.key.sensitive = !state.loading;
                widgets.diagnostic = state.diagnostic;
                widgets.copy.sensitive = Boolean(state.diagnostic);
                for (const child of widgets.children)
                    widgets.details.remove(child);
                widgets.children = [];
                for (const w of state.snapshot?.windows ?? []) {
                    const item = row(`${w.label} · ${percentage(w.remainingPercent)}`,
                        [resetLabel(w.resetAt), w.resetAt, w.detail].filter(Boolean).join(' · '));
                    widgets.details.add_row(item);
                    widgets.children.push(item);
                }
                widgets.details.subtitle = state.snapshot?.windows?.length ? `${state.snapshot.windows.length} reported windows` : 'Quota unavailable';
                if (state.id === 'CODEX') {
                    const code = state.loading && state.stage === 'DEVICE_POLL' ? await this.client.call('GetDeviceCode') : '';
                    if (!this.alive)
                        return;
                    widgets.code.label = code;
                    widgets.codeRow.visible = Boolean(code);
                }
                if (state.loading && this.started.has(state.id))
                    this.observedWorking.add(state.id);
                if (!state.loading && this.observedWorking.has(state.id)) {
                    this.started.delete(state.id);
                    this.observedWorking.delete(state.id);
                }
            }
        } catch {
            this.unavailable();
        } finally {
            this.fetching = false;
            if (this.dirty && this.alive) {
                this.dirty = false;
                this.update();
            }
        }
    }

    async dispose() {
        if (!this.alive)
            return;
        this.alive = false;
        this.window.disconnect(this.closeSignal);
        this.settings.disconnect(this.settingsSignal);
        this.providerRow.disconnect(this.providerSignal);
        for (const widgets of this.rows.values()) {
            widgets.key?.set_text('');
            widgets.code.label = '';
        }
        for (const scope of this.browserScopes)
            scope.cancel();
        this.browserScopes.clear();
        // Deliver cancellation before cancelling the D-Bus proxy itself.
        await Promise.all([...this.started].map(id => this.client.call('Cancel', id).catch(() => {})));
        this.started.clear();
        this.observedWorking.clear();
        this.client.dispose();
        this.rows.clear();
    }
}
