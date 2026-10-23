package be.julien.quotadeck.core

enum class AuthPhase(val label: String) {
    PREPARATION("Préparation de la connexion"),
    BROWSER("Autorisation dans le navigateur"),
    CALLBACK_RECEIVED("Autorisation reçue · retour dans l’app attendu"),
    TOKEN_EXCHANGE("Échange du code contre un jeton"),
    SAVE_SESSION("Enregistrement chiffré de la session"),
    LOAD_QUOTAS("Lecture des quotas"),
    REFRESH_TOKEN("Renouvellement du jeton"),
}
