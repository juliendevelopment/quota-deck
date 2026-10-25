import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import {IDS} from '../lib/config.js';
import {BUS_XML, BUS_PATH, BUS_INTERFACE, BUS_NAME} from '../lib/ipc.js';
import {invoke} from './runtime.js';

export class Bridge {
    constructor(controller, connection = Gio.DBus.session) {
        const valid = id => IDS.includes(id);
        this.object = Gio.DBusExportedObject.wrapJSObject(BUS_XML, {
            GetState: () => JSON.stringify(controller.view()),
            GetDeviceCode: () => controller.deviceCode,
            Refresh: id => {
                if (id === 'ALL')
                    controller.refreshAll();
                else if (valid(id))
                    controller.refresh(id);
            },
            Connect: id => { if (valid(id)) controller.connect(id); },
            Cancel: id => { if (valid(id)) controller.cancel(id); },
            Disconnect: id => { if (valid(id)) controller.disconnect(id); },
            SubmitKey: key => { controller.submitKey(key); },
        });
        this.object.export(connection, BUS_PATH);
    }

    changed() {
        this.object?.emit_signal('Changed', null);
    }

    dispose() {
        this.object?.unexport();
        this.object = null;
    }
}

export class BridgeClient {
    constructor() {
        this.cancellable = new Gio.Cancellable();
        this.proxy = null;
        this.signals = [];
    }

    async start(changed, name = BUS_NAME) {
        this.proxy = await invoke(cb => Gio.DBusProxy.new(Gio.DBus.session, Gio.DBusProxyFlags.DO_NOT_AUTO_START,
            Gio.DBusNodeInfo.new_for_xml(BUS_XML).interfaces[0], name, BUS_PATH, BUS_INTERFACE,
            this.cancellable, cb), r => Gio.DBusProxy.new_finish(r));
        if (this.cancellable.is_cancelled())
            return;
        this.signals.push(this.proxy.connect('g-signal', (_p, _sender, signal) => {
            if (signal === 'Changed')
                changed();
        }));
        this.signals.push(this.proxy.connect('notify::g-name-owner', changed));
        changed();
    }

    async call(method, value = null) {
        if (!this.proxy || this.cancellable.is_cancelled())
            throw new Error('Extension unavailable');
        const result = await invoke(cb => this.proxy.call(method, value === null ? null : new GLib.Variant('(s)', [value]),
            Gio.DBusCallFlags.NO_AUTO_START, 5000, this.cancellable, cb), r => this.proxy.call_finish(r));
        return result.deepUnpack()[0];
    }

    dispose() {
        this.cancellable.cancel();
        for (const id of this.signals)
            this.proxy.disconnect(id);
        this.signals = [];
        this.proxy = null;
    }
}
