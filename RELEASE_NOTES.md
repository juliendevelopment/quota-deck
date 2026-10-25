## QuotaDeck 0.1.3 — reprise de la connexion Codex

Installer **par-dessus la version actuelle, sans désinstaller**. Même clé de signature, versionCode 103. Les sessions existantes restent conservées ; pas de changement des connecteurs Claude, Antigravity et OpenCode Go.

### Codex

- Après saisie du code et confirmation chez OpenAI, fermer l’onglet avec **× / Retour**, ou choisir **⋮ → Revenir dans QuotaDeck**. OpenAI ne redirige pas automatiquement le flux par code d’appareil vers une application tierce.
- Le polling reprend au premier plan au lieu d’échouer pendant l’affichage de Chrome. La même demande reste en mémoire durant le parcours ; aucune nouvelle demande de code n’est faite au retour.
- Une erreur temporaire de polling est réessayée avec backoff et respect de Retry-After. Le refus explicite, l’expiration et l’annulation restent terminaux. Pas de boucle infinie ni de polling agressif.
- L’intervalle donné par OpenAI est respecté, y compris au-delà de 30 secondes. La tentative est bornée à 15 minutes, avec une horloge monotone pendant la vérification.
- Demande du code, vérification OpenAI, échange du jeton, enregistrement et lecture des quotas sont distingués. Le diagnostic copiable reste sans code d’appareil, jeton ni données de compte.
- L’échange final est marqué à usage unique côté client HTTP et n’est pas rejoué aveuglément après une réponse réseau incertaine.
- Le code copié est marqué sensible pour éviter son aperçu dans le presse-papiers Android récent.
- L’identifiant de l’espace ChatGPT est extrait du jeton d’identité, comme dans le client officiel, y compris avec un jeton d’accès opaque. Il accompagne la lecture des quotas et reste hors du diagnostic partageable ; une sélection existante n’est pas changée silencieusement lors du renouvellement.

### Vérification et limites

Le protocole a été comparé au client officiel Codex. Les tests exercent le vrai ViewModel, le polling et l’échange HTTP sur serveur local avec fixtures, le retour au premier plan, les erreurs réseau, le refus, l’expiration, l’annulation et le menu navigateur. Les tests ne constituent pas une connexion réelle à un compte.

L’erreur exacte du téléphone n’a pas été obtenue. Ces défauts sont reproduits et corrigés dans l’app, mais la connexion de Julien et l’accès à ses quotas restent à confirmer sur son appareil. Ne partager que le diagnostic copié, jamais le code d’appareil ni un jeton. Une destruction du processus pendant le navigateur nécessite une nouvelle tentative ; aucun secret de tentative n’est persisté pour contourner cela.
