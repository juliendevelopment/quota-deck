## QuotaDeck 0.1.1 — correction des connexions Google

Installez cet APK **par-dessus la version 0.1.0**, sans désinstaller. Même clé de signature, versionCode 101 : les connexions existantes sont conservées.

### Changements

- **Claude** : le bouton principal utilise désormais le navigateur système pour Google et la double authentification, puis un callback OAuth local vers le téléphone. PKCE, `state`, scope `user:profile` demandé pour les quotas, jetons chiffrés et renouvelables. Client public Claude Code, intégration non officielle.
- **OpenCode Go** : connexion Google dans le navigateur système, puis copie unique de la clé Go de la console vers l’app. Aucun nouvel abonnement, aucun appel de génération. Chrome ne partage pas ses cookies avec l’app : ce transfert manuel est explicite, au lieu d’un faux retour de connexion automatique.
- **Ancienne fenêtre email intégrée** : Google y est intercepté avant l’authentification, avec explication et bouton de connexion externe. Les erreurs HTTP, redirections refusées et chargements bloqués ne sont plus silencieux.
- Codex et Antigravity : parcours inchangés.

### Vérification et limites

Des tests de régression ont d’abord échoué sur les deux parcours Google embarqués, puis la nouvelle suite vérifie le lancement du navigateur depuis l’activité réelle, le formulaire Go, PKCE, les échanges et renouvellements Claude, le callback localhost et l’interception Google dans la fenêtre de secours.

La 2FA et l’accès aux quotas de comptes personnels restent à tester sur le téléphone de Julien : aucune validation de compte réel n’est revendiquée. Anthropic peut refuser un client/scope ; ce refus ne sera pas contourné. Ne transmettre aucun mot de passe, code de connexion ou clé dans Discord.
