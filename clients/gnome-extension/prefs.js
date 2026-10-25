import {ExtensionPreferences} from 'resource:///org/gnome/Shell/Extensions/js/extensions/prefs.js';
import {Preferences} from './ui/preferences.js';

export default class QuotaDeckPreferences extends ExtensionPreferences {
    fillPreferencesWindow(window) {
        window._quotaDeck = new Preferences(window, this.getSettings());
    }
}
