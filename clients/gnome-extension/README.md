# QuotaDeck for GNOME Shell

[Repository overview](../../README.md) · [Architecture](../../docs/architecture.md) · [Cross-client behavior](../../shared/README.md)

**Native GJS / ES-module alpha for GNOME Shell 49 and 50.** Panel indicator, native menu and GTK4/libadwaita preferences, with direct quota reads and independent local sessions for Claude, Codex, Google Antigravity and OpenCode Go. No backend or Android toolchain is required.

Provider interfaces and installed-client integrations are unofficial. Accounts are not preconnected. Successful fixture/native tests do **not** prove that a real account will authorize or expose quotas. No live account was authorized during development.

## Install

For the easiest path, read [INSTALL.md](INSTALL.md) in this directory. It covers downloading the ready-made bundle, running `python3 install.py`, logging out/back in, enabling the extension and connecting providers.


Use a desktop running a supported GNOME Shell version (`gnome-shell --version`), with **GJS, libsoup 3, libsecret 1, GTK4, libadwaita >= 1.8 and an unlocked Secret Service default keyring**. A normal GNOME installation supplies most of these. On Fedora, missing runtime packages can be installed with:

```sh
sudo dnf install gjs libsoup3 libsecret gtk4 libadwaita gnome-keyring
```

Download the ZIP and `SHA256SUMS.txt` from the [GNOME CI artifacts](https://github.com/juliendevelopment/quota-deck/actions/workflows/gnome.yml), or build below. Choose the Fedora 44 artifact for the current package; both matrix packages contain the same runtime source, but GLib-compiled schemas can differ by build environment. GitHub requires sign-in to download Actions artifacts. Extract the artifact wrapper to obtain the actual `.shell-extension.zip`, then:

```sh
sha256sum --check SHA256SUMS.txt
gnome-extensions install --force quotadeck@juliendevelopment.github.io.shell-extension.zip
```

Log out and back in after the first install or a code update (Wayland cannot reload cached extension modules in place), then:

```sh
gnome-extensions enable quotadeck@juliendevelopment.github.io
gnome-extensions prefs quotadeck@juliendevelopment.github.io
```

No extensions.gnome.org publication is performed. No new software license is added by this client implementation.

## Panel and account configuration

- The panel shows the selected provider’s **first reported quota window**, not a fabricated aggregate. `—` means unknown/unavailable; `*` means stale. Expand a provider in the menu for all windows, remaining percentages, resets, last reading, plan, notices and safe errors.
- Preferences expose connect/reconnect, refresh one/all, cancel, **local disconnect**, official pages, privacy disclosures and copy-safe diagnostics. One independent local account session is stored per provider, matching Android’s account model. Codex’s selected workspace comes from ID-token metadata and is retained on token refresh; opaque access tokens are supported.
- Refresh runs once on enable and, by default, every 15 minutes while enabled. The interval is configurable from 5 minutes to one day, or automatic refresh can be disabled. A failed refresh retains the last reading marked stale; readings older than twice the interval are also marked stale. The panel age marker updates once a minute without extra network requests.
- A stored/connected session is not proof that quotas are available. Null/missing values remain **Not reported**, never 0%, 100% or unlimited. All-maximum Antigravity model fallback readings are explicitly unverified and hidden as percentages.

## Connections

Unlock your desktop keyring first. Secrets and cached readings are stored only in QuotaDeck’s own Secret Service records. A locked/unavailable/unconfigured default keyring is an explicit error, never permission to write cleartext files.

### Claude

Choose **Connect** and confirm the disclosure. The system browser uses the provider-distributed public Claude Code installed-client configuration, **PKCE S256, state and `user:profile` only**. Consent returns to `http://localhost:<ephemeral-port>/callback` on this desktop. The browser page confirms code receipt, not completed token exchange. The extension exchanges the code once, stores refreshable credentials in the keyring and reads `/api/oauth/usage` with the OAuth beta header. No embedded browser, cookie import or generation scope is used. Provider refusal is not bypassed.

### Codex

Choose **Connect**, then approve the transient device code shown only in Preferences at the official OpenAI browser page. ChatGPT account security settings may require enabling device sign-in. Browser approval does not redirect to QuotaDeck: asynchronous back-channel polling waits the server interval, honors Retry-After and uses bounded transient backoff. Explicit denial, invalid requests, expiry and cancellation stop the attempt. The final single-use form exchange is never blindly replayed, even if its response is uncertain. The attempt expires within 15 minutes; force-closing/restarting Shell requires a new attempt. Workspace context is obtained from the ID token, with a JWT access-token fallback; access tokens may be opaque.

### Google Antigravity

Use an account **already initialized in the official Antigravity desktop application**. Before system-browser OAuth the preferences dialog discloses the installed client’s Cloud Platform, userinfo email/profile, cclog and experimentsandconfigs scopes. The complete scope URLs are also visible in Privacy. PKCE/state consent returns to `http://127.0.0.1:<ephemeral-port>/oauth-callback` on this desktop, not a VPS. QuotaDeck reads existing project metadata and quota summary/model endpoints only. It never creates projects, onboards accounts, enables billing or bypasses a client rejection. These broad provider-defined technical scopes are used only for quota reads by this implementation.

### OpenCode Go

Choose **Connect** to open the official console in the system browser. Sign in there and copy an **existing Go workspace API key** into the password-style field in Preferences, once, then choose **Connect with key**. The field is cleared immediately on submission/close. QuotaDeck only calls `/zen/go/v1/usage`, never a model endpoint or purchase action. Browser cookies are not transferred and other OpenCode/CLI sessions are not imported.

Do not send passwords, device codes, API keys, cookies or tokens through chat or bug reports. Desktop screenshot tools and clipboard managers are outside the extension’s control; no screenshot protection is claimed.

## Privacy and lifecycle

- Secret Service schema `be.julien.QuotaDeck.Gnome`, exact attributes `application=quotadeck@juliendevelopment.github.io` and the stable provider ID. Lookups/deletion never search unrelated application secrets. Credentials and last readings stay in encrypted keyring records; account names are not stored in GSettings or plaintext cache files.
- GSettings contains only refresh/appearance preferences. No automatic phone/browser/Claude/Codex/Hermes credential import, cloud sync, telemetry, remote server, paid service, model generation or purchases.
- Shell is the single owner of transport/authentication/storage. Preferences use a local, user-session D-Bus object at `/be/julien/QuotaDeck` on `org.gnome.Shell`; there is no token getter or remote listener. A one-time Go key is submitted to that local owner, never a remote QuotaDeck service. The user-session bus and keyring are not a security boundary against other malicious processes running as the same user.
- All network/Secret Service operations are asynchronous and cancellable. HTTP requests have a 45-second total timeout, bounded streamed responses, normal TLS checks and no credential-bearing redirects or automatic POST retry.
- Closing Preferences cancels connections started there. Disabling the extension cancels all operations, transient codes, HTTP sessions, loopback listeners, timers and signal connections, unexports D-Bus and destroys menu actors. Local disconnect waits for a pending store write before deleting that provider’s record/cache. Provider-side revocation is separate.
- Diagnostics are **allowlist-based**: app/Shell version, provider, stage, HTTP status, recognized OAuth error, network category and fixed endpoint label. No raw exceptions/bodies/headers, account identifiers, OAuth URLs/queries or credentials. Clipboard writes happen only on explicit **Copy diagnostic**; the clipboard is never read and diagnostics are never sent automatically.

## Build and verify

From this directory, use Node >= 22.13 for development tests/lint, Python 3 and GLib’s schema compiler. Node/npm are **not** runtime dependencies of the extension.

```sh
npm ci --ignore-scripts
npm test
npm run lint
npm run build
```

The deterministic allowlisted package is `dist/quotadeck@juliendevelopment.github.io.shell-extension.zip`; its checksum is `dist/SHA256SUMS.txt`. No tests, synthetic fixtures, npm modules, build tools or local sessions enter the ZIP. Runtime source is readable, not bundled/minified. Build schemas with the same GLib version for byte-identical archives.

For real native fixture transport, OS-random/PKCE, callback/security, D-Bus, encrypted keyring and GTK widget tests:

```sh
python3 scripts/verify.py
python3 scripts/verify.py --shell
```

Native tests additionally need `gjs`, `gnome-keyring`, `dbus-run-session`, `xvfb-run`, Xauth and the runtime libraries. `--shell` needs `gnome-shell`, its official `gnome-shell-test-tool`, software Mesa/EGL rendering and a reachable system bus. Container CI starts its own ephemeral system bus; no privileged container is needed. Tests create isolated HOME/XDG directories and session buses under `$TMPDIR`; they never exercise your personal keyring or authorize a real account. The synthetic test keyring password is not a user credential. Reports are written to `build/reports/verification.json` and adjacent logs.

[GNOME CI](../../.github/workflows/gnome.yml) tests Fedora 43 / GNOME 49 and Fedora 44 / GNOME 50 independently of Android, including the actual ZIP installed into headless Shell. The GNOME 49 test tool predates `--extension`, so the verifier stages the package in its own temporary XDG home before invoking the tool. GTK tests map real preferences widgets under Xvfb with explicitly synthetic state. This is native integration testing, **not human desktop/account acceptance**. Review the logs: generic headless GDM, accessibility, portals, background or PipeWire warnings do not establish an extension failure, but extension errors must not be ignored.

[GNOME Release](../../.github/workflows/gnome-release.yml) reserves **`gnome-vMAJOR.MINOR.PATCH`** tags, requires matching `metadata.json`/package versions, reruns GNOME CI and verifies checksums before creating a separate prerelease. Do not use Android’s `v*` tags, replace Android assets or publish a GNOME tag merely to test a branch. Increase integer `metadata.version` for an actual GNOME update as well as the semantic `version-name`/package version.

## Troubleshooting and remaining device checks

- **Extension unavailable in Preferences:** enable it in GNOME Extensions; check `gnome-extensions info quotadeck@juliendevelopment.github.io`. Preferences do not start a second credential/network owner while Shell is disabled.
- **Keyring unavailable/locked:** unlock the default collection with your desktop keyring UI and retry/reconnect. Minimal/headless desktops may have no configured default keyring. Do not disable keyring security or paste credentials into configuration files.
- **Browser callback cannot return:** browser and Shell must run on the same desktop and network namespace. A browser on a different machine cannot reach this desktop’s ephemeral loopback port. No fixed public port/firewall rule is needed. Cancel and reconnect if the attempt expired or Shell was disabled/locked/restarted.
- **Unknown/stale data:** inspect the last-reading time and safe error. Open the official provider page. Unsupported accounts/fields remain unavailable. Copy only the app’s diagnostic, never a complete journal/network capture containing personal information.
- **Updated source seems ignored:** log out/back in; ES modules remain cached within a Shell process. On a development workstation use `dbus-run-session gnome-shell --devkit --wayland` for GNOME 49/50 (install `mutter-devkit` if necessary).
- **Live checks still required:** approve each provider yourself in the extension on a target desktop; separately confirm a successful real quota read, subsequent refresh/token rotation, cancel/deny/expiry, suspend/resume, lock/unlock, keyring relock/unlock and local disconnect. Test actual keyboard navigation, long account/model labels, screen scaling and light/dark Shell themes. Automated headless tests cannot establish these human/account results. No live-GNOME/account screenshot or synthetic data presented as live is included.

## Implementation and official references

`lib/` is portable provider/auth/controller logic; `native/` owns GIO/libsoup/Secret Service/lifecycle/D-Bus; `extension.js` owns Shell actors and sources; `ui/preferences.js` owns GTK widgets. [Android OAuth](../android/app/src/main/java/be/julien/quotadeck/auth/OAuthService.kt), [transport](../android/app/src/main/java/be/julien/quotadeck/data/ProviderService.kt), [parsers](../android/app/src/main/java/be/julien/quotadeck/data/QuotaParsers.kt) and privacy/authentication regressions are the protocol baseline, not a shared runtime dependency.

Official documentation consulted for the supported surface:

- [GJS ES-module extension creation](https://gjs.guide/extensions/development/creating.html), [preferences/process separation/GSettings](https://gjs.guide/extensions/development/preferences.html), [async operations/cancellation](https://gjs.guide/guides/gjs/asynchronous-programming.html) and [D-Bus](https://gjs.guide/guides/gio/dbus.html).
- [GNOME extension review/security/lifecycle guidelines](https://gjs.guide/extensions/review-guidelines/review-guidelines.html), [GNOME 49 changes](https://gjs.guide/extensions/upgrading/gnome-shell-49.html), [GNOME 50 changes and official test tool](https://gjs.guide/extensions/upgrading/gnome-shell-50.html).
- [libsoup asynchronous streamed HTTP](https://libsoup.gnome.org/libsoup-3.0/method.Session.send_async.html), [message flags](https://libsoup.gnome.org/libsoup-3.0/flags.MessageFlags.html) and [private server/listen APIs](https://libsoup.gnome.org/libsoup-3.0/class.Server.html).
- [libsecret asynchronous password storage](https://gnome.pages.gitlab.gnome.org/libsecret/func.password_store.html), [lookup](https://gnome.pages.gitlab.gnome.org/libsecret/func.password_lookup.html), [exact schema matching](https://gnome.pages.gitlab.gnome.org/libsecret/struct.Schema.html) and [default-collection lookup](https://gnome.pages.gitlab.gnome.org/libsecret/type_func.Collection.for_alias.html).
- [libadwaita asynchronous consent dialogs](https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/method.AlertDialog.choose.html).
