import GLib from 'gi://GLib';
import St from 'gi://St';
import Clutter from 'gi://Clutter';
import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import * as PanelMenu from 'resource:///org/gnome/shell/ui/panelMenu.js';
import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import * as Config from 'resource:///org/gnome/shell/misc/config.js';
import {PROVIDERS} from './lib/config.js';
import {panelText, percentage, resetLabel, readingLabel} from './lib/display.js';
import {Controller} from './lib/controller.js';
import {Auth} from './lib/auth.js';
import {Providers} from './lib/providers.js';
import {Runtime} from './native/runtime.js';
import {Http} from './native/http.js';
import {Store} from './native/store.js';
import {Bridge} from './native/bridge.js';

export default class QuotaDeckExtension extends Extension {
    enable() {
        this._settings = this.getSettings();
        this._runtime = new Runtime();
        this._http = new Http();
        this._store = new Store();
        this._controller = new Controller({store: this._store, auth: new Auth(this._http, this._runtime),
            providers: new Providers(this._http), runtime: this._runtime, shellVersion: Config.PACKAGE_VERSION,
            changed: () => {
                this._render();
                this._bridge?.changed();
            }});
        this._indicator = new PanelMenu.Button(0.0, 'QuotaDeck');
        this._label = new St.Label({text: 'QuotaDeck', y_align: Clutter.ActorAlign.CENTER});
        this._indicator.add_child(this._label);
        Main.panel.addToStatusArea(this.uuid, this._indicator);
        this._bridge = new Bridge(this._controller);
        this._settingsSignal = this._settings.connect('changed', () => {
            this._schedule();
            this._render();
        });
        this._menuSignal = this._indicator.menu.connect('open-state-changed', (_menu, open) => {
            if (open)
                this._render();
        });
        this._schedule();
        this._clockTimer = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, 60, () => {
            this._updatePanel();
            return GLib.SOURCE_CONTINUE;
        });
        this._render();
        this._controller.start();
    }

    _schedule() {
        if (this._refreshTimer)
            GLib.Source.remove(this._refreshTimer);
        this._refreshTimer = 0;
        if (this._settings.get_boolean('automatic-refresh')) {
            this._refreshTimer = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT,
                this._settings.get_uint('refresh-minutes') * 60, () => {
                    this._controller.refreshAll();
                    return GLib.SOURCE_CONTINUE;
                });
        }
    }

    _line(menu, value) {
        const item = new PopupMenu.PopupMenuItem(value, {reactive: false, can_focus: false});
        item.label.clutter_text.line_wrap = true;
        item.label.clutter_text.line_wrap_mode = 2;
        item.label.add_style_class_name('quotadeck-detail');
        menu.addMenuItem(item);
    }

    _updatePanel() {
        if (!this._indicator || !this._controller)
            return;
        const state = this._controller.states[this._settings.get_string('panel-provider')];
        this._label.text = panelText(state, Date.now(), this._settings.get_uint('refresh-minutes'));
        this._indicator.accessible_name = `QuotaDeck · ${this._label.text}`;
    }

    _render() {
        if (!this._indicator || !this._controller)
            return;
        const states = this._controller.view();
        const interval = this._settings.get_uint('refresh-minutes');
        this._updatePanel();
        const menu = this._indicator.menu;
        menu.removeAll();
        for (const state of states) {
            const provider = PROVIDERS[state.id];
            const sub = new PopupMenu.PopupSubMenuMenuItem(`${provider.title}${state.loading ? ' · Working…' : ''}${state.stale ? ' · Stale' : ''}`);
            menu.addMenuItem(sub);
            this._line(sub.menu, readingLabel(state, Date.now(), interval));
            if (state.snapshot?.plan)
                this._line(sub.menu, `Plan: ${state.snapshot.plan}`);
            if (this._settings.get_boolean('show-identity') && state.snapshot?.identity)
                this._line(sub.menu, state.snapshot.identity);
            for (const w of state.snapshot?.windows ?? []) {
                this._line(sub.menu, `${w.label}: ${percentage(w.remainingPercent)}`);
                this._line(sub.menu, `${resetLabel(w.resetAt)}${w.resetAt ? ` · ${w.resetAt}` : ''}`);
                if (w.detail)
                    this._line(sub.menu, w.detail);
            }
            if (state.snapshot?.notice)
                this._line(sub.menu, state.snapshot.notice);
            if (state.error)
                this._line(sub.menu, state.error);
            if (state.loading)
                this._line(sub.menu, `Stage: ${state.stage}`);
            const refresh = sub.menu.addAction('Refresh', () => this._controller.refresh(state.id));
            refresh.setSensitive(state.connected && !state.loading);
            sub.menu.addAction(state.connected ? 'Account / Reconnect…' : 'Connect in Preferences…', () => this.openPreferences());
            sub.menu.addAction('Official provider page', () => this._official(provider.home));
            if (state.diagnostic)
                sub.menu.addAction('Copy safe diagnostic', () => St.Clipboard.get_default().set_text(St.ClipboardType.CLIPBOARD, state.diagnostic));
            if (state.loading)
                sub.menu.addAction('Cancel', () => this._controller.cancel(state.id));
        }
        menu.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());
        menu.addAction('Refresh all connected accounts', () => this._controller.refreshAll());
        menu.addAction('Preferences / Privacy', () => this.openPreferences());
    }

    async _official(uri) {
        const scope = this._runtime.scope();
        this._browserScopes ??= new Set();
        this._browserScopes.add(scope);
        try {
            await this._runtime.openBrowser(uri, scope);
        } catch {
            if (!scope.cancelled && this._indicator)
                Main.notify('QuotaDeck', 'The system browser could not be opened. Check the desktop browser configuration.');
        } finally {
            scope.dispose();
            this._browserScopes?.delete(scope);
        }
    }

    disable() {
        if (this._clockTimer)
            GLib.Source.remove(this._clockTimer);
        this._clockTimer = 0;
        if (this._refreshTimer)
            GLib.Source.remove(this._refreshTimer);
        this._refreshTimer = 0;
        if (this._settingsSignal)
            this._settings.disconnect(this._settingsSignal);
        this._settingsSignal = 0;
        if (this._menuSignal)
            this._indicator.menu.disconnect(this._menuSignal);
        this._menuSignal = 0;
        this._bridge?.dispose();
        this._bridge = null;
        this._controller?.dispose();
        for (const scope of this._browserScopes ?? [])
            scope.cancel();
        this._browserScopes?.clear();
        this._browserScopes = null;
        this._http?.dispose();
        this._store?.dispose();
        this._indicator?.destroy();
        this._indicator = null;
        this._label = null;
        this._controller = null;
        this._http = null;
        this._store = null;
        this._runtime = null;
        this._settings = null;
    }
}
