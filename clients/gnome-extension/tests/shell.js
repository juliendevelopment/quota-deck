// Runs INSIDE a real GNOME Shell 50 headless Wayland compositor via the official test tool.
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import * as Scripting from 'resource:///org/gnome/shell/ui/scripting.js';

export const METRICS = {};
const UUID = 'quotadeck@juliendevelopment.github.io';
let checks = 0;
function assert(value, message) {
    if (!value)
        throw new Error(message);
    checks++;
}
export async function run() {
    let extension;
    for (let i = 0; i < 100; i++) {
        extension = Main.extensionManager.lookup(UUID);
        if (extension?.stateObj?._controller && !extension.stateObj._controller.view().some(s => s.loading))
            break;
        await Scripting.sleep(100);
    }
    assert(extension?.stateObj?._indicator, 'Extension indicator loaded');
    assert(extension.error?.length === 0 || !extension.error, 'No extension error');
    const object = extension.stateObj;
    assert(Main.panel.statusArea[UUID] === object._indicator, 'Native panel registration');
    object._indicator.menu.open();
    await Scripting.sleep(100);
    assert(object._indicator.menu.isOpen, 'Native menu opened');
    assert(object._indicator.menu._getMenuItems().length >= 7, 'All providers and menu actions');
    object._indicator.menu.close();
    const controller = object._controller;
    assert(controller.view().length === 4 && controller.view().every(s => !s.connected), 'No preconnected or fabricated accounts');
    const before = object._refreshTimer;
    assert(before > 0, 'Automatic refresh source created');
    object.disable();
    assert(object._refreshTimer === 0 && object._clockTimer === 0 && !object._indicator && !object._settingsSignal, 'Disable releases actors, timer and settings signal');
    assert(controller.disposed && controller.records.size === 0 && controller.operations.size === 0, 'Disable cancels controller');
    let missing = false;
    try {
        await new Promise((resolve, reject) => {
            Gio.DBus.session.call('org.gnome.Shell', '/be/julien/QuotaDeck', 'be.julien.QuotaDeck', 'GetState',
                null, GLib.VariantType.new('(s)'), Gio.DBusCallFlags.NO_AUTO_START, 2000, null,
                (connection, result) => {
                    try { resolve(connection.call_finish(result)); } catch (error) { reject(error); }
                });
        });
    } catch {
        missing = true;
    }
    assert(missing, 'Disable unexports D-Bus');
    object.enable();
    await Scripting.sleep(100);
    assert(object._indicator && object._refreshTimer > 0 && object._refreshTimer !== before, 'Clean enable-disable-enable cycle');
    print(`Shell smoke: ${checks} passed inside real GNOME Shell; no live accounts or human desktop acceptance claimed.`);
}
