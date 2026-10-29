## QuotaDeck 0.1.5 — Refresh when returning to the app

Install this APK **over the existing app without uninstalling**. It uses the same signing certificate and versionCode 105, preserving encrypted sessions and account connections.

### Changes

- Connected-provider quotas refresh whenever QuotaDeck enters the foreground, including after pressing Home and reopening the app while its process remains alive.
- The first reading now begins at the same foreground lifecycle boundary instead of only when the ViewModel is first created.
- Existing per-provider in-flight job protection remains in place, so repeated lifecycle events do not start concurrent quota reads for the same account.

### Unchanged behavior and security

Provider endpoints, OAuth client configuration, scopes, PKCE/state validation, device-code polling, signing keys and credential storage remain unchanged. No model requests, new permissions, billing changes or telemetry were added. Failed readings retain the last known quota and are shown as stale.

### Verification

A fixture regression test covers initial foreground entry and Home → reopen with the same ViewModel still alive. The complete Android unit suite, lint and debug build pass locally. The GitHub release workflow reruns tests, release lint, signed APK assembly and certificate verification. A real account refresh on the target phone remains a device-only check.
