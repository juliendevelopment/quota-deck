# QuotaDeck

Application Android personnelle en **Kotlin / Jetpack Compose**, en français, pour consulter sur une seule page les quotas d’abonnement **Claude, Codex, Google Antigravity et OpenCode Go**. Sans serveur QuotaDeck, publicité, télémétrie, achat ni requête de génération IA.

## Installer

Depuis les [releases privées](https://github.com/juliendevelopment/quota-deck/releases), télécharger `QuotaDeck-v0.1.2.apk`, puis autoriser son installation Android. **Android 8.0 ou plus récent** (minSdk 26). Les versions suivantes conserveront la même signature et un versionCode croissant.

## Connexions

- **Claude** : `Connecter Claude` → navigateur système (Chrome ou équivalent), compatible Google/2FA → autorisation avec le **client public Claude Code** → retour `http://localhost:<port>/callback` sur **le téléphone** → bouton **Revenir dans QuotaDeck** → échange du jeton **une fois l’app au premier plan**, stockage chiffré et lecture de `/api/oauth/usage`. PKCE S256, contrôle de `state`, scope demandé **`user:profile` uniquement**, renouvellement du jeton. La configuration du client et l’endpoint de jeton ont été vérifiés dans le binaire officiel Claude Code installé ; cela ne constitue pas une approbation de QuotaDeck par Anthropic. Si le fournisseur refuse ce client/scope, l’app ne contourne pas son refus. Une alternative email intégrée reste disponible ; Google y est intercepté avec une explication et un bouton vers le vrai navigateur, jamais laissé tourner après la 2FA.
- **Codex** : connexion officielle du **client public Codex** par code d’appareil, dans le navigateur système. Copier le code affiché, ouvrir OpenAI, autoriser, revenir dans l’app. ChatGPT peut exiger d’activer la connexion par code dans ses réglages de sécurité. La session est indépendante des fichiers Codex du VPS. Les jetons sont renouvelés automatiquement.
- **Antigravity** : navigateur système Google + callback OAuth sur **127.0.0.1 du téléphone**, pas sur le VPS. Configuration publique du client installé Antigravity, PKCE et contrôle de `state`. Après l’autorisation, toucher **Revenir dans QuotaDeck** : l’échange du jeton attend le retour de l’activité au premier plan, pour ne pas dépendre d’un accès réseau en arrière-plan autorisé par le téléphone. Les scopes techniques Cloud Platform du client sont présentés avant l’ouverture. Nécessite un compte déjà initialisé dans Antigravity sur ordinateur. Aucun projet, onboarding ou service facturable n’est créé. Google peut refuser ce client sur certains comptes ; ne pas essayer de contourner ce refus.
- **OpenCode Go** : `Connecter OpenCode Go` → **Ouvrir OpenCode avec Google** dans le navigateur système → récupérer la clé API de l’espace Go dans la console → revenir dans QuotaDeck et coller la clé **uniquement dans l’app**, une seule fois. Aucun nouvel abonnement nécessaire. La clé sert uniquement à lire `/zen/go/v1/usage`, jamais à appeler un modèle. Les cookies Chrome ne sont pas transférables à une WebView : ouvrir simplement le même site dans Chrome ne connecterait pas l’app. L’ancien parcours Google intégré a donc été retiré du chemin principal. Les anciennes sessions déjà enregistrées restent lisibles jusqu’à expiration.

**Cette version est une alpha non officielle.** Les interfaces privées/internes des fournisseurs peuvent changer. La compilation, les tests synthétiques et les tests d’interface ne prouvent pas que la connexion d’un compte personnel particulier sera acceptée. Les quatre parcours doivent être essayés avec les comptes de Julien sur son téléphone. Aucune donnée de quota fictive n’est affichée et aucun compte préconnecté n’est embarqué.

## Données et sécurité

- Session et dernier relevé chiffrés en **AES-256-GCM**, clé non exportable dans **Android Keystore**.
- Pas de sauvegarde Android des données. Aucune clé utilisateur dans Git ou l’APK.
- Les identifiants/configurations OAuth des clients installés sont **publics et distribués par les fournisseurs**, pas des secrets personnels ni un enregistrement OAuth QuotaDeck. Leur utilisation n’est pas une approbation officielle de cette application.
- Le SSO Google ne doit jamais être exécuté dans une WebView contrôlée par QuotaDeck. Aucun contournement par User-Agent ni assouplissement des protections Google. Les redirections refusées et les chargements web de plus de 45 secondes ont une explication visible.
- Aucun mot de passe lu par QuotaDeck. La fenêtre web n’expose **aucun pont JavaScript**, n’a pas d’accès fichier/contenu, refuse HTTP et les erreurs TLS. Captures d’écran bloquées durant la connexion et dans le formulaire de clé. Les écrans d’erreur OAuth sans donnée secrète sont capturables.
- `Déconnecter` efface la session et le cache locaux. La révocation côté fournisseur se fait dans son compte. Aucune révocation automatique d’autres applications.
- Les erreurs ne reprennent jamais le corps HTTP ni les jetons. Un diagnostic copiable contient uniquement la version de l’app, l’API Android, le fournisseur, l’étape, un éventuel statut HTTP/code d’erreur autorisé et la catégorie réseau ; ni identifiant de compte, ni query OAuth, ni message brut d’exception. Les redirections HTTP avec jeton/cookie ne sont pas suivies.
- Un quota manquant reste **Non communiqué**, jamais 0 % ou 100 % supposé. Un relevé en échec conserve le précédent en le marquant non actualisé. Des valeurs Google toutes à 100 % issues du fallback modèles sont masquées car elles peuvent être des placeholders.
- Actualisation à l’ouverture et sur bouton ; pas de polling agressif en arrière-plan.

## Connexion reçue mais erreur réseau

La page locale confirme seulement que le **code d’autorisation est reçu**, pas qu’un jeton a été obtenu. Appuyez sur **Revenir dans QuotaDeck** (ou fermez l’onglet pour revenir manuellement) : l’app poursuit l’échange et la lecture des quotas au premier plan. Elle attend au maximum deux minutes ce retour après réception du code ; au-delà, relancer une nouvelle connexion.

Si la finalisation échoue, l’écran affiche l’étape concernée et **Copier le diagnostic**. Les erreurs DNS, TLS, délais réseau et refus de réseau sont distinguées ; les codes OAuth sont conservés uniquement sur liste blanche, jamais les descriptions brutes du fournisseur. Le diagnostic ne contient aucun mot de passe, clé API, cookie, code de connexion ou jeton et peut être partagé pour le dépannage. Aucun journal n’est envoyé automatiquement.

Les tests de régression exécutent le vrai ViewModel, le serveur de callback et l’échange HTTP sur fixtures pour Claude et Google avec un réseau volontairement refusé en arrière-plan ; ce scénario ne prouve pas la cause exacte du refus sur un téléphone particulier. L’accès des comptes réels reste à valider sur le téléphone.

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

- [Politique Google : navigateurs sécurisés, sans OAuth en WebView](https://developers.google.com/identity/protocols/oauth2/policies#browsers).
- [Authentification officielle Claude Code](https://code.claude.com/docs/en/authentication) ; le client installé 2.1.296 fournit les URLs publiques `claude.com/cai/oauth/authorize`, `platform.claude.com/v1/oauth/token` et le callback localhost.
- [Connexion OpenCode Go par clé](https://opencode.ai/docs/go/).
- [Codex officiel : device code auth](https://github.com/openai/codex/blob/main/codex-rs/login/src/device_code_auth.rs).
- [CodexBar : Claude web API](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/Claude/ClaudeWeb/ClaudeWebAPIFetcher.swift).
- [CodexBar : Codex usage](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/Codex/CodexOAuth/CodexOAuthUsageFetcher.swift).
- [CodexBar : Antigravity remote quotas](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/Antigravity/AntigravityRemoteUsageFetcher.swift) et [summary](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/Antigravity/AntigravityQuotaSummaryParser.swift).
- [Antigravity installed-client config](https://github.com/NoeFabris/opencode-antigravity-auth/blob/main/src/constants.ts).
- [CodexBar : OpenCode Go API et console](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/OpenCodeGo/OpenCodeGoUsageFetcher.swift).

Pas de backend caché ni de proxy vers le VPS. Le dashboard dépend de l’accès direct du téléphone aux fournisseurs.
