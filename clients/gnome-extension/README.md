# QuotaDeck for GNOME Shell

[Repository overview](../../README.md) · [Architecture](../../docs/architecture.md) · [Cross-client behavior](../../shared/README.md)

**Status: implementation is the next phase.** This directory currently defines the GNOME client boundary and scope. No `extension.js`, installable archive, tested Shell-version range or working account connector is claimed by the repository-reorganization commit.

## Goal

A native **GNOME Shell extension** showing the same subscription quotas and exposing the same connection/account configuration as the [Android client](../android/README.md): Claude, Codex, Google Antigravity and OpenCode Go. This is a second independent local client, not a web view of Android or a remote quota service.

## Expected experience

- A panel indicator and native menu with provider status, remaining quota windows, resets, last update, missing/stale data, refresh and safe errors.
- Native preferences for provider connect/reconnect, local disconnect, official-provider pages, privacy information and safe diagnostic copying.
- Browser-based Claude/Google OAuth, Codex device-code login, and one-time OpenCode Go API-key entry. Do not request credentials through chat or pretend that opening a browser transfers its cookies.
- Secure desktop keyring storage; GSettings may store only non-secret preferences. No automatic import of phone or unrelated CLI sessions.
- English app-owned labels. No telemetry, purchases, model calls or cloud-project creation.
- Asynchronous network work with bounded timeouts/retries, cancellable authorization, timer/signal cleanup on disable, no Shell-blocking polling and no cleartext credential fallback when the keyring is unavailable.

## Implementation session acceptance

1. Read the architecture and shared contract, then inspect the moved Android provider/authentication code and regressions. Preserve the existing Android client; do not migrate its runtime or credentials.
2. Check current official GNOME extension/GJS/preferences documentation. Use native GJS/ES modules and GNOME UI/Secret Service facilities appropriate to the supported Shell versions; document and verify that range instead of inferring it from a headless server.
3. Implement real quota parsing, transport, account configuration/authentication, secure storage, menu/preferences and safe diagnostics for all four providers. Missing fields stay unknown; no fake runtime quotas or placeholder connectors.
4. Exercise provider and privacy/lifecycle regressions with local fixtures, native GJS where available, and real packaging/install metadata. Keep pure tests independent of Android tooling. A packaged archive alone does not prove Shell UI or real account acceptance.
5. Add client-local build/install/troubleshooting instructions and separate GNOME CI/release handling. Reserve `gnome-v*` tags; do not overwrite Android releases or reuse Android signing material.
6. Run root repository checks and keep Android tests/builds green. Produce an actual installable `.shell-extension.zip` with a checksum; report precisely what was run and which live desktop/account checks remain. Never claim screenshots or consent from a GUI that was not exercised.

Development starts in a **new agent session after the repository reorganization has been committed, pushed and verified**. Work on a separate feature branch/worktree so the committed structural baseline stays distinct from GNOME implementation.
