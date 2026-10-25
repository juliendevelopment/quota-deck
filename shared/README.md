# Cross-client behavior contract

[Repository overview](../README.md) · [Architecture](../docs/architecture.md)

This directory contains runtime-independent requirements for the [Android client](../clients/android/README.md) and [GNOME client](../clients/gnome-extension/README.md). It is **not** a service, shared session directory or immediately reusable Kotlin/GJS SDK. No credentials or real-account fixtures belong here.

## Providers and actions

Keep stable provider identities: `CLAUDE`, `CODEX`, `ANTIGRAVITY`, `OPENCODE`; the latter is displayed as **OpenCode Go**. Each client must support:

- Per-provider connect/reconnect, local disconnect and an official-provider page action.
- Refresh one account and all connected accounts, with visible progress and errors.
- Plan/account context when safely available, provider-reported quota windows, remaining percentages, reset information, notices and the last reading's time.
- Explicit unknown/unavailable and stale-cache states; connection success is distinct from quota-read success.
- Cancellable and expiring authorization, visible stages and an explicit copy-safe-diagnostic action.
- An English interface and privacy/about information. Preserve user-owned workspace/account names and provider-owned model names as reported.

## Data semantics

The baseline [Android models](../clients/android/app/src/main/java/be/julien/quotadeck/core/Models.kt) define these concepts:

- **Snapshot:** `provider`; optional `plan` and `identity`; a list of `windows`; `fetchedAt` as Unix epoch **milliseconds**; optional `notice`.
- **Window:** `label`; optional finite `remainingPercent` in **0..100**; optional ISO-8601 `resetAt`; optional `detail`.
- **Provider state:** identity, connected/loading flags, optional snapshot, safe error and safe diagnostic. A stored session alone does not prove that the account exposes quotas.

Keep missing values null/unknown, not 0, 100 or unlimited. Convert reported utilization to remaining percentages only when units and bounds are known. Never generate model requests, purchases or cloud onboarding to obtain data. Hide unverifiable maximum-value fallback readings rather than claiming confirmed quota availability. Keep prior readings on refresh failure and clearly mark them stale.

Shared future fixtures must be sanitized captures or explicitly synthetic examples with provenance and expected outcomes. Do not publish private account data or use demonstration values as a runtime fallback.

## Connection parity

- **Claude:** system-browser public-client OAuth with PKCE/state validation and the existing read-only profile/quota scope; token refresh; explicit unofficial-client limitations. Do not require the obsolete embedded web fallback merely for UI parity.
- **Codex:** device-code authorization, system browser, bounded polling honoring server intervals and Retry-After; transient retry versus explicit refusal/expiry; a non-replayed final exchange. Support opaque access tokens and workspace metadata from the identity token, preserving an existing selection at refresh.
- **Antigravity:** Google installed-client OAuth in the system browser, PKCE/state validation and a loopback callback on the desktop/phone performing consent. Disclose scopes. Require the existing initialized desktop account; do not create projects or enable billing.
- **OpenCode Go:** official console sign-in in the system browser and explicit one-time entry of an existing Go API key into the client. Encrypt/store it in the platform's secure store; use it only for quota reads, not generation. Never pretend Chrome cookies are automatically transferred.

Use the existing [Android authentication](../clients/android/app/src/main/java/be/julien/quotadeck/auth/OAuthService.kt), [provider transport](../clients/android/app/src/main/java/be/julien/quotadeck/data/ProviderService.kt), [parsers](../clients/android/app/src/main/java/be/julien/quotadeck/data/QuotaParsers.kt) and associated client-local tests as the baseline. Platform return/navigation actions are not authorization evidence.

## Secret and diagnostic boundary

Credentials are client-local: Android Keystore on Android; desktop Secret Service/keyring on GNOME. No passwords, tokens, cookies, API keys or device codes in GSettings, repository files, fixtures, shared docs, logging, support diagnostics or cross-device transport. Disconnect deletes only this client's local state; provider-side revocation is separate.

Diagnostics use allowlisted provider/stage identifiers, client/runtime versions, safe endpoint labels, network categories and recognized HTTP/OAuth error codes. Never export raw exceptions, bodies, headers, OAuth query strings, account IDs or backend error descriptions. Tests with secret-marker fixtures must verify this boundary.
