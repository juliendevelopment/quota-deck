## QuotaDeck 0.1.2 — finalisation après le retour du navigateur

Installez cet APK **par-dessus la version actuelle, sans désinstaller**. Même clé de signature, versionCode 102. Le connecteur OpenCode Go et les données chiffrées existantes sont conservés.

### Changements

- **Claude et Google Antigravity** : après réception du callback, l’app attend d’être au premier plan avant d’appeler l’endpoint de jeton. Le parcours ne dépend plus de requêtes réseau lancées pendant que Chrome est affiché.
- **Page localhost** : « Autorisation reçue » au lieu d’une connexion prématurément annoncée comme terminée. Un grand bouton **Revenir dans QuotaDeck** ramène à l’activité existante, sans transporter de code ou de jeton dans le lien. Fermer l’onglet et revenir manuellement reste possible.
- **Finalisation visible** : réception de l’autorisation, échange du jeton, stockage chiffré et lecture des quotas sont distingués. La session n’est pas déclarée connectée à la seule réception du callback.
- **Erreurs exploitables** : DNS, TLS/certificat, délai réseau ou accès réseau restreint sont distingués. **Copier le diagnostic** fournit uniquement des métadonnées autorisées : version, étape, catégorie réseau, endpoint sans query et éventuel statut HTTP/code OAuth.
- **Captures d’écran** : autorisées pour les erreurs OAuth sans secret. Les champs de clé API, le code d’appareil et la connexion web restent protégés.

### Vérification et limites

Les régressions couvrent le vrai ViewModel avec callback HTTP et échange de jeton simulé pour les deux fournisseurs, un réseau refusé en arrière-plan, le retour dans la même tâche Android, les captures d’erreur et la copie d’un diagnostic sans secret. Les endpoints réels de jetons ont répondu aux sondes anonymes négatives ; cela ne valide aucune connexion de compte.

Le blocage réseau en arrière-plan est une cause plausible du message observé, pas une cause prouvée sur le téléphone de Julien. La connexion et l’accès aux quotas de ses comptes réels restent à confirmer sur son appareil. En cas d’échec, partager uniquement le diagnostic copié, jamais une clé, un mot de passe, un code de connexion ou un jeton.
