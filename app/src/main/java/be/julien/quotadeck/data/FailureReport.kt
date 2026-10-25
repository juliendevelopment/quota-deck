package be.julien.quotadeck.data

import be.julien.quotadeck.core.*

/** Allowlisted metadata only: never exception messages, HTTP bodies, headers, account IDs or OAuth URLs. */
object FailureReport {
    val oauthCodes = setOf("invalid_grant", "invalid_client", "invalid_request", "invalid_scope", "access_denied",
        "unauthorized_client", "unsupported_grant_type", "temporarily_unavailable", "server_error", "permission_error",
        "authentication_error", "insufficient_scope", "rate_limit_exceeded", "authorization_pending", "slow_down", "expired_token")
    fun format(provider: ProviderId, phase: AuthPhase, error: Exception, version: String, android: Int): String {
        val e = error as? ProviderException
        val code = e?.safeErrorCode?.takeIf { it in oauthCodes } ?: "not-reported"
        val endpoint = e?.endpoint?.substringBefore('?')?.substringBefore('#')?.takeIf { it.matches(Regex("[A-Za-z0-9.:/{}_\\-]+")) } ?: "not-reported"
        return listOf(
            "QuotaDeck $version · Android API $android",
            "Provider: ${provider.name}",
            "Stage: ${phase.name}",
            "HTTP: ${e?.httpCode ?: "not-reported"}",
            "Provider code: $code",
            "Network: ${e?.networkFailure?.name ?: "not-reported"}",
            "Endpoint: $endpoint",
            "Safe diagnostic: no passwords, sign-in codes, tokens, cookies or account data.",
        ).joinToString("\n")
    }
}

enum class NetworkFailure(val userLabel: String) {
    DNS("DNS resolution failed"), TLS("TLS connection/certificate rejected"), TIMEOUT("network timeout"),
    RESTRICTED("network access denied or restricted for this app"), CONNECTION("connection interrupted or refused"), IO("network communication failed"),
}
