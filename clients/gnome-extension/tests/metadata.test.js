import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {VERSION, UUID} from '../lib/config.js';
const read = name => readFileSync(new URL(`../${name}`, import.meta.url), 'utf8');
test('Install metadata, code and package version agree; supported versions are explicit', () => {
    const metadata = JSON.parse(read('metadata.json'));
    assert.equal(metadata.uuid, UUID);
    assert.equal(metadata['version-name'], VERSION);
    assert.equal(JSON.parse(read('package.json')).version, VERSION);
    assert.deepEqual(metadata['shell-version'], ['49', '50']);
    assert.equal(metadata['settings-schema'], 'org.gnome.shell.extensions.quotadeck');
    assert.match(metadata.description, /clipboard/);
    assert.equal(metadata['session-modes'], undefined);
});
test('Native Shell/preferences libraries stay in separate processes', () => {
    assert.doesNotMatch(read('extension.js'), /gi:\/\/(?:Gtk|Adw|Gdk)/);
    assert.doesNotMatch(read('ui/preferences.js'), /gi:\/\/(?:St|Shell|Meta|Clutter)/);
    assert.match(read('prefs.js'), /ExtensionPreferences/);
});
