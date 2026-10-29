# QuotaDeck for Android

[Monorepo overview](../../README.md) · [Client architecture](../../docs/architecture.md)

A personal **Kotlin / Jetpack Compose** Android dashboard for **Claude, Codex, Google Antigravity and OpenCode Go** subscription quotas. The interface is in English. No QuotaDeck backend, ads, telemetry, purchases or AI generation requests.

## Screenshots

<p align="center">
  <img src="../../docs/screenshots/dashboard-claude-codex.png" alt="QuotaDeck English interface: Claude and Codex quotas" width="340" />
  <img src="../../docs/screenshots/dashboard-codex-antigravity.png" alt="QuotaDeck English interface: Codex and Antigravity quotas" width="340" />
</p>

English UI previews rendered by the app's real Compose components using **synthetic test fixtures**. These are not live account quota readings. Actual values depend on the account and the time of the reading.

## Installation

Download `QuotaDeck-v0.1.5.apk` from the [releases](https://github.com/juliendevelopment/quota-deck/releases) and allow installation on your Android device. **Android 8.0 or later** is required (minSdk 26).

Install updates over the existing app, **without uninstalling**. Releases keep the same signing certificate and use an increasing versionCode, preserving your encrypted sessions. The app uses English even when the device language is French. Existing cached quota labels from earlier French versions are translated for display without clearing accounts or changing their values.

## Connections

- **Claude**: **Connect Claude** → system browser with Google/two-factor authentication support → authorization using the **public Claude Code client** → `http://localhost:<port>/callback` on **the phone** → **Return to QuotaDeck**. Token exchange resumes when the app is in the foreground; the token is encrypted locally and used to read `/api/oauth/usage`. Uses PKCE S256, CSRF `state` validation, the **`user:profile` scope only**, and token refresh. Public client configuration and token endpoints were checked against the official Claude Code distribution; this does not constitute Anthropic's endorsement of QuotaDeck. The app does not bypass provider refusals. An embedded email-only fallback is available; attempts to use Google there are intercepted with an explanation and a system-browser sign-in action.
- **Codex**: sign in with the **public Codex client** using a device code. Copy the displayed code, open OpenAI, complete authorization until the success message, then close the tab with **× / Back** or choose **⋮ → Return to QuotaDeck**. Device authorization does not automatically redirect to this app: QuotaDeck polls OpenAI for the session. Polling and the final token exchange wait while the app is not in the foreground. Temporary polling failures keep the same pending code and use backoff and Retry-After; server polling intervals, cancellation and the 15-minute expiration are respected. Explicit refusal stops the attempt. The final single-use code exchange is not blindly replayed. Pending authorization is kept in memory; force-closing the app or process death requires a new attempt. ChatGPT may require enabling device-code sign-in in account security settings. Sessions are independent of any Codex credentials on another computer. Workspace metadata comes from the identity token, with an access-token fallback, so opaque access tokens are supported. Existing workspace selection is preserved during refresh.
- **Antigravity**: Google system-browser sign-in with an OAuth callback on **the phone's 127.0.0.1**, not on a server. Uses the public installed-app Antigravity client, PKCE and CSRF `state` validation. Select **Return to QuotaDeck** after authorization; token exchange waits for the activity to resume. The client's Cloud Platform and technical scopes are disclosed before authorization. The account must already be initialized in the desktop Antigravity app. QuotaDeck does not create projects, onboard accounts, enable billing or bypass a client refusal.
- **OpenCode Go**: **Connect OpenCode Go** → **Open OpenCode with Google** in the system browser → copy the Go workspace API key from the console → paste it **only into QuotaDeck**, once. Use your existing subscription; no new purchase is required. The key only reads `/zen/go/v1/usage` and is never used to call a model. Chrome cookies cannot be transferred automatically to an embedded WebView. Older stored web sessions remain readable until they expire.

**This is an unofficial alpha.** Private/internal provider interfaces can change and some accounts or quota fields may not be supported. A successful build or fixture test does not prove access to a particular account. The app never fills missing quotas with invented data and ships with no preconnected accounts. Provider-supplied names, account identities and workspace names are preserved as reported, not translated as app text.

## Data and security

- Sessions and the most recent readings are encrypted with **AES-256-GCM** and a non-exportable **Android Keystore** key.
- Android backups and device transfers are disabled for app data. No user credential is stored in Git or bundled in the APK.
- Installed-client OAuth configuration is **publicly distributed by the providers**. It is not a user's private credential or a QuotaDeck OAuth registration; its use does not imply official approval.
- Google SSO is never run in an app-controlled WebView. No spoofed user-agent or weakened Google security settings are used. Blocked redirects and embedded loads exceeding 45 seconds produce visible messages.
- QuotaDeck does not read your password. The embedded fallback exposes **no JavaScript bridge**, blocks file/content access, plain HTTP and TLS errors, and clears WebView data when disposed.
- Screenshots are blocked during sign-in and in the API-key form. Non-sensitive OAuth error screens can be captured. Copied device codes are marked sensitive on supported Android versions.
- **Disconnect** removes the local session and cache. Revoke provider-side authorization in the provider's settings; the app does not revoke other applications.
- Errors and copied diagnostics never include raw HTTP bodies, headers, passwords, API keys, cookies, sign-in codes, tokens or account identifiers. Diagnostics include only app/Android API version, provider, stage, allowlisted HTTP/OAuth error codes, network category and a safe endpoint label. Credential-bearing redirects are not followed.
- A missing quota remains **Not reported**, never an assumed 0% or 100%. Failed refreshes keep the previous reading and mark it as stale. Unverifiable all-100% Antigravity model fallback values are hidden rather than presented as confirmed data.
- Refresh runs whenever QuotaDeck enters the foreground and on request; there is no aggressive background polling.

## Troubleshooting sign-in

For Claude and Antigravity, the local page confirms **receipt of an authorization code**, not a completed token exchange. Select **Return to QuotaDeck**, or close the tab and return manually. The app waits up to two minutes for foreground return after receiving the code; restart sign-in if that window expires.

For Codex, finish authorization in OpenAI and return manually. Do not start a new connection after approving the current code. The existing attempt resumes in the foreground. If Android destroys the process while the browser is open, start a fresh attempt with a new code.

On failure, check the displayed stage and use **Copy diagnostic**. DNS, TLS/certificate failures, timeouts and restricted network access have separate categories. Backend descriptions are never copied because they can contain private data. No support log is sent automatically.

Regression tests exercise the real ViewModel and HTTP transport with local fixtures: browser callbacks, device-code requests, polling, token exchange, quota parsing, foreground return, transient failures, refusal, expiry, cancellation and the credential-free browser return action. Memory-store fixtures verify orchestration, not Android Keystore persistence. A real-account check on the target phone is still required; simulated tests are not evidence that every provider account works.

## Development

Run the following commands from **`clients/android/`**, not the repository root. Open this directory in Android Studio. The client-local Gradle wrapper and signing script are self-contained.

Use **JDK 17**, **Android SDK 35** and **build-tools 35.0.0**. The project pins AGP 8.13.2, Kotlin 2.1.0, Compose BOM 2025.01.01 and Gradle 8.13 with a verified distribution SHA-256.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Set `JAVA_HOME` and `ANDROID_HOME`; declare `sdk.dir` in an untracked `local.properties` if needed. Never put production sessions or keys in test fixtures.

App-owned UI, diagnostics, documentation and workflow messages are maintained in English. Tests cover English rendering on a French device locale and display-only compatibility for older French cache entries. This compatibility mapping intentionally retains legacy French input strings; protocol fields, storage identifiers and user-owned names are not renamed.

## Signed releases

[Android CI](../../.github/workflows/build.yml) runs tests, lint and a debug APK build on push/PR. [Signed Android Release](../../.github/workflows/release.yml) tests, lints, builds, verifies the signature and SHA-256, and publishes a GitHub prerelease on a validated **vMAJOR.MINOR.PATCH** tag.

Dedicated GitHub secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. Never commit or replace the private release key after distributing the first version.

Build locally without copying signing secrets into the checkout:

```sh
python3 scripts/build_signed.py --config /private/path/signing.json
```

The private JSON contains `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. Keep the key and its backup outside the repository, with directory permissions 0700 and file permissions 0600. Do not put secrets in a synchronized document vault.

VersionCode is `major*10000 + minor*100 + patch`, with minor and patch below 100. Never publish a decreasing versionCode.

## Protocol references

Independent Kotlin implementation, informed by these formats and endpoints:

- [Google secure-browser OAuth policy](https://developers.google.com/identity/protocols/oauth2/policies#browsers).
- [Official Claude Code authentication](https://code.claude.com/docs/en/authentication); the installed 2.1.296 distribution supplies the public authorization/token URLs and localhost callback configuration.
- [OpenCode Go key-based connection](https://opencode.ai/docs/go/).
- [Official Codex device-code flow](https://github.com/openai/codex/blob/main/codex-rs/login/src/device_code_auth.rs).
- [CodexBar Claude web API](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/Claude/ClaudeWeb/ClaudeWebAPIFetcher.swift).
- [CodexBar Codex usage](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/Codex/CodexOAuth/CodexOAuthUsageFetcher.swift).
- [CodexBar Antigravity remote quotas](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/Antigravity/AntigravityRemoteUsageFetcher.swift) and [summary parser](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/Antigravity/AntigravityQuotaSummaryParser.swift).
- [Antigravity installed-client configuration](https://github.com/NoeFabris/opencode-antigravity-auth/blob/main/src/constants.ts).
- [CodexBar OpenCode Go API and console](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/OpenCodeGo/OpenCodeGoUsageFetcher.swift).

No hidden backend or proxy: the phone connects directly to each provider.
