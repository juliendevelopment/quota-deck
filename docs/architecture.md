# Client architecture

[Repository overview](../README.md) · [Cross-client behavior](../shared/README.md)

## Boundaries

- **`clients/android/`** owns the existing Kotlin/Compose UI, Android lifecycle, network/authentication orchestration, Android Keystore storage, tests, Gradle wrapper, build configuration, signing entrypoint and Android release notes. Open this directory, not the monorepo root, in Android Studio.
- **`clients/gnome-extension/`** owns the native GJS/ES-module GNOME Shell 49/50 indicator/menu, GTK4/libadwaita preferences, desktop OAuth/network orchestration, Secret Service integration, pure/native/headless-Shell tests and deterministic ZIP packaging. Shell is the sole account-operation owner; preferences call a local session D-Bus object with no token getter. GSettings holds only non-secret appearance/refresh preferences.
- **`shared/`** owns portable behavior/data contracts and, when implemented and verified, sanitized provider fixtures usable by both clients. It is not a shared credential store, a Kotlin/GJS binary library or a backend.
- **`docs/`** owns cross-client design and screenshots. Client-specific build instructions stay with that client.
- Root **`tests/`** checks the repository layout, relative documentation links and Android build/release path assumptions without an SDK or signing secrets.
- Root **`.github/workflows/`** dispatches each client in its own working directory. Action artifact paths remain repository-relative; `defaults.run.working-directory` applies only to `run` steps, not to `uses` steps.

## Sharing policy

Kotlin/Android and GJS/GNOME have different runtimes, UI APIs, lifecycle and secret-storage services. Do not introduce a remote service, Kotlin Multiplatform migration or Android dependency in GNOME merely to call the two clients "shared". Keep the proven Android implementation stable; port provider logic behind desktop adapters, and promote only truly portable contracts/fixtures after exercising both implementations.

The Android models, parsers and authentication regressions are the reference for existing behavior. The shared contract describes semantics, not a requirement to import Kotlin code or reproduce Android Activity APIs on a desktop.

## Authentication and configuration

Both clients offer explicit per-provider sign-in/reconnect, local disconnect, refresh, official-provider links, cancellable/expiring authorization and credential-free diagnostics. Each client keeps its own account sessions. No automatic import from a phone, browser profile or another CLI; no cross-device secret synchronization.

Use the system browser for OAuth/Google SSO, PKCE and state validation where supported. A loopback callback belongs on the device performing authorization. Device-code login is a back-channel polling flow, not a guaranteed browser redirect. Preserve server intervals, bounded retries, cancellation, refusal, expiry and non-replayable final code exchange. Adapt lifecycle gating to GNOME rather than blindly copying Android's foreground requirement.

Android secrets remain Keystore-backed with backups disabled. GNOME must keep secrets in the desktop's Secret Service/keyring; GSettings and tracked files are not credential stores. Keep network operations asynchronous, dispose signal handlers/timers and release resources on extension disable. A local transient OAuth listener/helper is not a remote QuotaDeck backend.

## Android migration invariants

The repository move does not change `be.julien.quotadeck`, SDK requirements, provider IDs/endpoints, OAuth clients/scopes, credential fields, Keystore aliases, signing material or versionCode. Existing source/build files move byte-for-byte. The signing script resolves the Gradle wrapper relative to itself, so its existing logic works in the client directory and when invoked from the repository root.

Machine-local `local.properties`, caches and build outputs stay ignored. Keys stay outside the checkout. Already-published release assets are not rebuilt or replaced as part of a directory change.

## Releases and verification

- Existing **`vMAJOR.MINOR.PATCH`** tags remain Android releases. The signed workflow runs inside `clients/android/` and reads `clients/android/RELEASE_NOTES.md`. Keep the long-lived Android signing certificate and increasing versionCode for actual app updates.
- Reserve **`gnome-v*`** for GNOME releases so they cannot accidentally trigger the Android `v*` workflow. The dedicated GNOME release workflow validates the semantic tag against `metadata.json`/package versions, runs the independent native matrix and publishes a checksummed ZIP prerelease. No GNOME tag is created merely for feature-branch delivery.
- Preserve distinct CI verification and artifacts per client. Root repository checks and Android tests must keep working when GNOME code is added.
- Compare moved Android files, run all existing unit/activity tests, lint, debug and signed builds after relocation. Verify package/version/certificate; a successful filesystem move is not build evidence.
- For GNOME, verify pure/provider logic with fixtures, execute GJS integration checks where possible, build a real installable archive and clearly separate headless verification from real Shell/preferences/account testing. Never claim Shell or provider acceptance based solely on Node tests or a ZIP file.
