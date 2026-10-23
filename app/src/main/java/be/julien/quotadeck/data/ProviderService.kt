package be.julien.quotadeck.data

import be.julien.quotadeck.core.*
import kotlinx.serialization.json.*

class ProviderService(private val api: ApiClient = ApiClient()) {
    suspend fun fetch(id: ProviderId, credential: Credential): Pair<Credential, ProviderSnapshot> = when (id) {
        ProviderId.CLAUDE -> claude(credential)
        ProviderId.CODEX -> codex(credential)
        ProviderId.ANTIGRAVITY -> antigravity(credential)
        ProviderId.OPENCODE -> opencode(credential)
    }
    private suspend fun claude(c: Credential): Pair<Credential, ProviderSnapshot> {
        val headers = buildMap {
            put("Cookie", c.sessionCookie); put("Referer", "https://claude.ai/settings/usage")
            if (c.userAgent.isNotBlank()) put("User-Agent", c.userAgent)
        }
        val orgs = api.get("https://claude.ai/api/organizations", headers) as? JsonArray
            ?: throw ProviderException("Claude ne communique pas les organisations du compte.")
        val org = orgs.firstOrNull { row -> row.arr("capabilities")?.any { it.jsonPrimitive.contentOrNull == "chat" } == true } ?: orgs.firstOrNull()
            ?: throw ProviderException("Aucune organisation Claude disponible.")
        val id = org.text("uuid")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
            ?: throw ProviderException("Organisation Claude non reconnue.")
        val response = api.get("https://claude.ai/api/organizations/$id/usage", headers)
        return c.copy(accountId = id) to QuotaParsers.claude(response).copy(plan = org.text("rate_limit_tier"))
    }
    private suspend fun codex(c: Credential): Pair<Credential, ProviderSnapshot> {
        val headers = buildMap { put("Authorization", "Bearer ${c.accessToken}"); if (c.accountId.isNotBlank()) put("ChatGPT-Account-Id", c.accountId) }
        val response = api.get("https://chatgpt.com/backend-api/wham/usage", headers)
        return c to QuotaParsers.codex(response)
    }
    private suspend fun antigravity(c: Credential): Pair<Credential, ProviderSnapshot> {
        val headers = mapOf("Authorization" to "Bearer ${c.accessToken}", "User-Agent" to "antigravity/hub/2.24.0 linux/amd64")
        val metadata = buildJsonObject { putJsonObject("metadata") { put("ideType", "ANTIGRAVITY"); put("platform", "PLATFORM_UNSPECIFIED"); put("pluginType", "GEMINI") } }
        val info = api.post("https://daily-cloudcode-pa.googleapis.com/v1internal:loadCodeAssist", metadata, headers)
        if (info.arr("ineligibleTiers")?.any { it.text("reasonCode") == "GOOGLE_TOS_NOT_SUPPORTED_BY_CLIENT" } == true && info.obj("currentTier") == null)
            throw ProviderException("Google refuse ce client pour ce compte. Consultez Antigravity sur ordinateur.")
        val project = info.text("cloudaicompanionProject")?.takeUnless { it.startsWith("{") } ?: info.obj("cloudaicompanionProject")?.text("id") ?: info.obj("cloudaicompanionProject")?.text("projectId") ?: c.projectId
        if (project.isBlank()) throw ProviderException("Projet Antigravity indisponible. Connectez d’abord ce compte dans Antigravity sur ordinateur.")
        val body = buildJsonObject { put("project", project) }
        val plan = info.obj("planInfo")?.text("planType") ?: info.obj("paidTier")?.text("name") ?: info.obj("currentTier")?.text("name")
        val summary = try { api.post("https://daily-cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary", body, headers) } catch (e: ProviderException) { if (e.needsLogin) throw e; null }
        var windows = summary?.let(QuotaParsers::antigravitySummary).orEmpty()
        var notice: String? = null
        if (windows.isEmpty()) {
            windows = QuotaParsers.antigravityModels(api.post("https://daily-cloudcode-pa.googleapis.com/v1internal:fetchAvailableModels", body, headers))
            if (windows.isNotEmpty() && windows.all { (it.remainingPercent ?: -1.0) >= 99.9 }) {
                // Some unsupported clients receive a placeholder of 100% for every model. Never present it as verified.
                windows = windows.map { it.copy(remainingPercent = null) }
                notice = "Google renvoie des valeurs maximales non vérifiables : consultez la page officielle."
            }
        }
        if (windows.isEmpty()) throw ProviderException("Google ne communique pas de quotas Antigravity pour ce compte.")
        return c.copy(projectId = project) to ProviderSnapshot(ProviderId.ANTIGRAVITY, plan = plan, windows = windows, notice = notice)
    }
    private suspend fun opencode(c: Credential): Pair<Credential, ProviderSnapshot> {
        if (c.accessToken.isNotBlank()) return c to QuotaParsers.opencode(api.get("https://opencode.ai/zen/go/v1/usage", mapOf("Authorization" to "Bearer ${c.accessToken}")), apiKey = true)
        val headers = buildMap {
            put("Cookie", c.sessionCookie); put("Origin", "https://opencode.ai"); put("Referer", "https://opencode.ai/console")
            if (c.userAgent.isNotBlank()) put("User-Agent", c.userAgent)
        }
        val orgs = api.get("https://opencode.ai/console/api/orgs", headers) as? JsonArray ?: throw ProviderException("Liste des espaces OpenCode indisponible.")
        val candidates = orgs.filter { it.text("id")?.matches(Regex("(?:org_|wrk_)[A-Za-z0-9_-]+")) == true }
            .sortedBy { if (it.text("id") == c.accountId) 0 else 1 }.take(10)
        var failure: ProviderException? = null
        for (org in candidates) {
            val id = org.text("id") ?: continue
            try {
                val response = api.get("https://opencode.ai/console/api/go/status", headers + ("x-org-id" to id))
                val snapshot = QuotaParsers.opencode(response).copy(identity = org.text("name"), notice = if (candidates.size > 1) "Espace Go : ${org.text("name") ?: id}." else null)
                return c.copy(accountId = id) to snapshot
            } catch (e: ProviderException) { if (e.needsLogin || e.httpCode == 429) throw e; failure = e }
        }
        throw failure ?: ProviderException("Aucun espace avec un abonnement OpenCode Go disponible.")
    }
}
