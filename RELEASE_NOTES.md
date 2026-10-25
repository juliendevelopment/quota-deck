## QuotaDeck 0.1.4 — English interface and documentation

Install this APK **over the existing app without uninstalling**. It uses the same signing certificate and versionCode 104, preserving encrypted sessions and account connections.

### Changes

- The dashboard, quota labels, relative reset times, sign-in instructions, buttons, accessibility labels, errors, copied diagnostics and local browser callback pages are now in English.
- App-owned text remains English on a French device locale. Reading timestamps use English month names while keeping the device time zone.
- Cached labels from earlier French versions are translated for display, including balances and workspace notice prefixes. Percentages, missing values, reset dates, timestamps, account identities and user-owned workspace names are preserved. Credentials and encrypted storage identifiers are unchanged.
- The README, workflow messages, repository description and release documentation are in English.
- README screenshots now show the English interface rendered by real Compose components with explicitly synthetic test fixtures; they are not live account quota readings.

### Unchanged behavior and security

Provider endpoints, OAuth client configuration, scopes, PKCE/state validation, device-code polling, foreground gating, signing keys and credential storage remain unchanged. No model requests, new permissions, billing changes or telemetry were added. Provider/browser/system-owned pages and user data may retain their own language.

### Verification

Tests cover English reset/quota labels and diagnostics, compatibility with older cached data, preservation of user-owned names, and native English UI rendering on a French locale. The complete authentication and quota regression suite is run along with release lint and a signed APK build. Real account access still depends on provider support and the account's permissions.
