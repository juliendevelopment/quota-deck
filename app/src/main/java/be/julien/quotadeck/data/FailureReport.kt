package be.julien.quotadeck.data

import be.julien.quotadeck.core.*

/** Allowlisted metadata only: never exception messages, HTTP bodies, headers, account IDs or OAuth URLs. */
object FailureReport {
    val oauthCodes = setOf("invalid_grant", "invalid_client", "invalid_request", "invalid_scope", "access_denied",
        "unauthorized_client", "unsupported_grant_type", "temporarily_unavailable", "server_error", "permission_error",
        "authentication_error", "insufficient_scope", "rate_limit_exceeded")
    fun format(provider: ProviderId, phase: AuthPhase, error: Exception, version: String, android: Int): String {
        val e = error as? ProviderException
        val code = e?.safeErrorCode?.takeIf { it in oauthCodes } ?: "non-renseigne"
        val endpoint = e?.endpoint?.substringBefore('?')?.substringBefore('#')?.takeIf { it.matches(Regex("[A-Za-z0-9.:/{}_\\-]+")) } ?: "non-renseigne"
        return listOf(
            "QuotaDeck $version · Android API $android",
            "Fournisseur : ${provider.name}",
            "Etape : ${phase.name}",
            "HTTP : ${e?.httpCode ?: "non-renseigne"}",
            "Code fournisseur : $code",
            "Reseau : ${e?.networkFailure?.name ?: "non-renseigne"}",
            "Endpoint : $endpoint",
            "Diagnostic sans mot de passe, code de connexion, jeton, cookie ni donnees de compte.",
        ).joinToString("\n")
    }
}

enum class NetworkFailure(val userLabel: String) {
    DNS("résolution DNS impossible"), TLS("connexion TLS/certificat refusée"), TIMEOUT("délai réseau dépassé"),
    RESTRICTED("réseau refusé ou restreint pour l’app"), CONNECTION("connexion interrompue ou refusée"), IO("échec de communication réseau"),
}
