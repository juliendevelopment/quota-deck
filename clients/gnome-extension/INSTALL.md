# Install QuotaDeck on a GNOME PC

**Alpha for GNOME Shell 49 or 50.** This is a Shell extension: it adds an indicator to the top panel, not a separate application icon. Real account authorization and quota reads still need to be accepted on your desktop. No extensions.gnome.org publication is claimed.

## 1. Get the ready-made Linux bundle

Download **`QuotaDeck-GNOME-0.1.0-linux.tar.gz`** supplied with this build. Do not choose an Android APK or GitHub's automatic **Source code** archives.

During branch testing, the same bundle is inside **`QuotaDeck-GNOME-fedora44`**, on a successful [GNOME CI run](https://github.com/juliendevelopment/quota-deck/actions/workflows/gnome.yml): open the run, select **Artifacts**, download that artifact and extract GitHub's outer ZIP once. GitHub requires sign-in for Actions downloads. Prefer Fedora 44's package; Fedora 43 is also tested. A future GNOME prerelease will carry the bundle directly; do not assume one exists before it is listed.

Extract the `.tar.gz` with **Files** (right-click → **Extract Here**) and open the extracted `QuotaDeck-GNOME-0.1.0` folder. It contains the real extension ZIP, its checksum, this guide and `install.py`. **Keep the inner `.shell-extension.zip` zipped.**

## 2. Install from your PC's terminal

Use **your GNOME PC**, not a VPS/SSH terminal and not your phone. Right-click inside the extracted folder → **Open in Terminal**, then run:

```sh
python3 install.py
```

No Git clone, Android SDK, Node/npm, compilation or administrator access is needed. The installer stays offline. It checks the ZIP checksum, package identity, supported Shell version and native runtime libraries, installs into your user's extension directory and reads the installed source back to verify it.

**Do not run the installer with `sudo`.** It does not sign you in, access a keyring, import CLI sessions or change your accounts. Checksums detect corruption; obtain the bundle from this project's trusted build, since a checksum supplied with a file is not an independent signature.

On Fedora GNOME, if runtime dependencies are missing:

```sh
sudo dnf install gjs libsoup3 libsecret gtk4 libadwaita gnome-keyring python3
```

Then run `python3 install.py` again **without sudo**. libadwaita **1.8 or newer** is required. Other distributions must supply the equivalent runtime libraries; GNOME 48 or older is not supported by this build. Do not turn off Shell's extension-version validation to force compatibility.

## 3. Log out, enable, connect

**Save your work, log out of GNOME, then log back in.** Closing the terminal is not enough. Wayland cannot reload extension JavaScript in place.

Open a terminal and run:

```sh
gnome-extensions enable quotadeck@juliendevelopment.github.io
gnome-extensions prefs quotadeck@juliendevelopment.github.io
```

Alternatively, open GNOME's **Extensions** app, enable **QuotaDeck**, and open its settings. If the global extensions switch is off, turn it on there. The panel initially shows `—` because no accounts are connected.

Unlock your normal desktop keyring if requested. In QuotaDeck settings, connect each provider you need:

- **Claude / Antigravity:** approve in your PC's browser. Antigravity requires an account already initialized in the official desktop app.
- **Codex:** approve the device sign-in shown in settings on the official OpenAI page; leave QuotaDeck settings open while it completes.
- **OpenCode Go:** enter an existing Go workspace API key in the app's password-style field.

Do not send passwords, device codes, API keys or tokens through chat. Connecting is not proof of quota availability: confirm that a fresh, real reading appears. Provider refusal or unsupported fields remain explicit errors/unknown values.

## Updates, diagnostics and removal

- **Update:** install the new complete bundle the same way, then log out/back in. Do **not** uninstall first. Your QuotaDeck keyring records are retained.
- **Status:** `gnome-extensions info quotadeck@juliendevelopment.github.io`. Check the Shell version with `gnome-shell --version`.
- **No indicator:** check GNOME Extensions' global switch and QuotaDeck's switch; log out/back in. Do not restart an entire Wayland session without saving your work.
- **Keyring locked/unavailable:** unlock/configure the default keyring with your desktop's keyring UI. Never put credentials in configuration files.
- **Safe reporting:** use the app's **Copy diagnostic**, not a raw journal dump or screenshot containing a code/key.
- **Disable:** `gnome-extensions disable quotadeck@juliendevelopment.github.io`.
- **Remove extension code:** `gnome-extensions uninstall quotadeck@juliendevelopment.github.io`. This is separate from account revocation; use QuotaDeck's **Disconnect** before removal if you want to delete its local provider records, and the provider's official site for remote revocation.

The complete client guide in the repository explains privacy, scopes, troubleshooting and the remaining real-desktop/account checks.
