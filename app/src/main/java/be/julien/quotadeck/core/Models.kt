package be.julien.quotadeck.core

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
enum class ProviderId(val title: String, val subtitle: String, val homeUrl: String) {
    CLAUDE("Claude", "Anthropic", "https://claude.ai/settings/usage"),
    CODEX("Codex", "OpenAI · ChatGPT", "https://chatgpt.com/codex/settings/usage"),
    ANTIGRAVITY("Antigravity", "Google", "https://antigravity.google/"),
    OPENCODE("OpenCode Go", "OpenCode", "https://opencode.ai/workspace")
}

@Serializable
data class QuotaWindow(
    val label: String,
    val remainingPercent: Double? = null,
    val resetAt: String? = null,
    val detail: String? = null,
) {
    init { require(remainingPercent == null || (remainingPercent.isFinite() && remainingPercent in 0.0..100.0)) }
}

@Serializable
data class ProviderSnapshot(
    val provider: ProviderId,
    val plan: String? = null,
    val identity: String? = null,
    val windows: List<QuotaWindow> = emptyList(),
    val fetchedAt: Long = System.currentTimeMillis(),
    val notice: String? = null,
)

@Serializable
data class Credential(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    val sessionCookie: String = "",
    val accountId: String = "",
    val projectId: String = "",
    val userAgent: String = "",
)

data class ProviderState(
    val id: ProviderId,
    val connected: Boolean = false,
    val loading: Boolean = false,
    val snapshot: ProviderSnapshot? = null,
    val error: String? = null,
    val diagnostic: String? = null,
)

fun resetLabel(value: String?, now: Instant = Instant.now()): String? {
    if (value == null) return null
    val target = runCatching { Instant.parse(value) }.getOrNull() ?: return null
    val seconds = java.time.Duration.between(now, target).seconds
    if (seconds <= 0) return "Reset pending"
    val minutes = (seconds + 59) / 60
    val days = minutes / (24 * 60)
    val hours = (minutes % (24 * 60)) / 60
    return when {
        days > 0 -> "Resets in ${days}d ${hours}h"
        hours > 0 -> "Resets in ${hours}h ${minutes % 60}m"
        else -> "Resets in ${minutes}m"
    }
}
