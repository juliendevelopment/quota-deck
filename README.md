# QuotaDeck

Application Android personnelle en **Kotlin / Jetpack Compose**, en français, pour consulter sur une seule page les quotas d’abonnement **Claude, Codex, Google Antigravity et OpenCode Go**. Sans serveur QuotaDeck, publicité, télémétrie, achat ni requête de génération IA.

## Installer

Depuis les [releases privées](https://github.com/juliendevelopment/quota-deck/releases), télécharger `QuotaDeck-v0.1.0.apk`, puis autoriser son installation Android. **Android 8.0 ou plus récent** (minSdk 26). Les versions suivantes conserveront la même signature et un versionCode croissant.

## Connexions

- **Claude** : `Connecter Claude` → consentement local → connexion sur **claude.ai** dans une fenêtre intégrée → **Terminer** après la connexion. Le cookie de session est enregistré chiffré, puis les cookies du navigateur intégré sont effacés. Les quotas sont lus depuis les organisations du compte et leur endpoint `usage`. Les restrictions Google/passkeys/Cloudflare peuvent empêcher cette connexion intégrée ; l’app le signale, sans contourner la protection.
- **Codex** : connexion officielle du **client public Codex** par code d’appareil, dans le navigateur système. Copier le code affiché, ouvrir OpenAI, autoriser, revenir dans l’app. ChatGPT peut exiger d’activer la connexion par code dans ses réglages de sécurité. La session est indépendante des fichiers Codex du VPS. Les jetons sont renouvelés automatiquement.
- **Antigravity** : navigateur système Google + callback OAuth sur **127.0.0.1 du téléphone**, pas sur le VPS. Configuration publique du client installé Antigravity, PKCE et contrôle de `state`. Les scopes techniques Cloud Platform du client sont présentés avant l’ouverture. Nécessite un compte déjà initialisé dans Antigravity sur ordinateur. Aucun projet, onboarding ou service facturable n’est créé. Google peut refuser ce client sur certains comptes ; ne pas essayer de contourner ce refus.
- **OpenCode Go** : connexion web **opencode.ai**, puis **Terminer**. Lecture des compteurs de l’espace Go (5 h, semaine, mois). Si plusieurs espaces existent, premier espace Go accessible, avec son nom affiché. Alternative : saisir la clé Go **uniquement dans l’app** ; elle ne sert qu’à appeler l’endpoint de quotas, jamais un modèle.

**Cette version est une alpha non officielle.** Les interfaces privées/internes des fournisseurs peuvent changer. La compilation, les tests synthétiques et les tests d’interface ne prouvent pas que la connexion d’un compte personnel particulier sera acceptée. Les quatre parcours doivent être essayés avec les comptes de Julien sur son téléphone. Aucune donnée de quota fictive n’est affichée et aucun compte préconnecté n’est embarqué.

## Données et sécurité

- Session et dernier relevé chiffrés en **AES-256-GCM**, clé non exportable dans **Android Keystore**.
- Pas de sauvegarde Android des données. Aucune clé utilisateur dans Git ou l’APK.
- Les identifiants/configurations OAuth des clients installés sont **publics et distribués par les fournisseurs**, pas des secrets personnels ni un enregistrement OAuth QuotaDeck. Leur utilisation n’est pas une approbation officielle de cette application.
- Aucun mot de passe lu par QuotaDeck. La fenêtre web n’expose **aucun pont JavaScript**, n’a pas d’accès fichier/contenu, refuse HTTP et les erreurs TLS. Captures d’écran bloquées durant la connexion.
- `Déconnecter` efface la session et le cache locaux. La révocation côté fournisseur se fait dans son compte. Aucune révocation automatique d’autres applications.
- Les erreurs ne reprennent jamais le corps HTTP ni les jetons. Les redirections HTTP avec jeton/cookie ne sont pas suivies.
- Un quota manquant reste **Non communiqué**, jamais 0 % ou 100 % supposé. Un relevé en échec conserve le précédent en le marquant non actualisé. Des valeurs Google toutes à 100 % issues du fallback modèles sont masquées car elles peuvent être des placeholders.
- Actualisation à l’ouverture et sur bouton ; pas de polling agressif en arrière-plan.

## Développer

JDK 17, SDK Android 35 et build-tools 35.0.0. Versions alignées sur les autres applications Kotlin de Julien : AGP 8.13.2, Kotlin 2.1.0, Compose BOM 2025.01.01, wrapper Gradle 8.13 avec SHA-256 vérifié.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Sur ce VPS, les sources sont `/home/fedora/dev/sources/quota-deck` et le SDK `/home/fedora/dev/apps/android-sdk`. `local.properties` reste hors Git. Ne jamais utiliser de session/clé de production dans les fixtures de test.

## Release signée

Le workflow `.github/workflows/build.yml` vérifie tests, lint et APK debug sur push/PR. `.github/workflows/release.yml` fait les tests, le lint release, le build signé, la vérification `apksigner`, le SHA-256 et publie une prerelease privée sur un tag **vMAJOR.MINOR.PATCH**.

Secrets GitHub dédiés à cette app : `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. La clé privée n’est jamais committée et ne doit pas être remplacée après diffusion de la première version.

Build signé local, sans copier les secrets dans le dépôt :

```sh
python3 scripts/build_signed.py --config /chemin/prive/signing.json
```

Le JSON privé contient les quatre valeurs `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. La copie de secours de la clé de cette app est conservée hors du dépôt sous `/home/fedora/agent-managed/keys/android/quota-deck/` (répertoire 0700, fichiers 0600). Ne pas déplacer les secrets dans le vault documentaire.

VersionCode : `major*10000 + minor*100 + patch` (minor et patch < 100). Ne pas publier une version dont le code régresse.

## Sources de protocole consultées

Implémentation Kotlin indépendante ; références pour les formats et endpoints :

- [Codex officiel : device code auth](https://github.com/openai/codex/blob/main/codex-rs/login/src/device_code_auth.rs).
- [CodexBar : Claude web API](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/Claude/ClaudeWeb/ClaudeWebAPIFetcher.swift).
- [CodexBar : Codex usage](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/Codex/CodexOAuth/CodexOAuthUsageFetcher.swift).
- [CodexBar : Antigravity remote quotas](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/Antigravity/AntigravityRemoteUsageFetcher.swift) et [summary](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/Antigravity/AntigravityQuotaSummaryParser.swift).
- [Antigravity installed-client config](https://github.com/NoeFabris/opencode-antigravity-auth/blob/main/src/constants.ts).
- [CodexBar : OpenCode Go API et console](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/OpenCodeGo/OpenCodeGoUsageFetcher.swift).

Pas de backend caché ni de proxy vers le VPS. Le dashboard dépend de l’accès direct du téléphone aux fournisseurs.
