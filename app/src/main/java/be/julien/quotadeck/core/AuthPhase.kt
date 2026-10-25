package be.julien.quotadeck.core

enum class AuthPhase(val label: String) {
    PREPARATION("Preparing sign-in"),
    BROWSER("Authorization in the browser"),
    DEVICE_CODE("Requesting an OpenAI device code"),
    DEVICE_POLL("Checking OpenAI authorization"),
    CALLBACK_RECEIVED("Authorization received · waiting for return to the app"),
    TOKEN_EXCHANGE("Exchanging the code for a token"),
    SAVE_SESSION("Encrypting and saving the session"),
    LOAD_QUOTAS("Reading quotas"),
    REFRESH_TOKEN("Refreshing the token"),
}
