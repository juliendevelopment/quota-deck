package be.julien.quotadeck.auth

import be.julien.quotadeck.core.*
import be.julien.quotadeck.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Public installed-client configuration, not user credentials or a private application secret. */
object OAuthConfig {
    const val CLAUDE_CLIENT = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    const val CLAUDE_AUTHORIZE = "https://claude.com/cai/oauth/authorize"
    const val CLAUDE_TOKEN = "https://platform.claude.com/v1/oauth/token"
    const val OPENAI_CLIENT = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val GOOGLE_CLIENT = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
    const val GOOGLE_INSTALLED_CLIENT_VALUE = "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"
    val GOOGLE_SCOPES = listOf("https://www.googleapis.com/auth/cloud-platform", "https://www.googleapis.com/auth/userinfo.email", "https://www.googleapis.com/auth/userinfo.profile", "https://www.googleapis.com/auth/cclog", "https://www.googleapis.com/auth/experimentsandconfigs")
    fun random(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

data class DeviceLogin(val deviceId: String, val code: String, val intervalSeconds: Long, val startedAt: Long = System.currentTimeMillis())

class OAuthService(private val api: ApiClient = ApiClient(), private val openAiBase: String = "https://auth.openai.com", private val claudeTokenUrl: String = OAuthConfig.CLAUDE_TOKEN, private val googleTokenUrl: String = "https://oauth2.googleapis.com/token") {
    fun claudeUrl(redirect: String, state: String, verifier: String): String = OAuthConfig.CLAUDE_AUTHORIZE.toHttpUrl().newBuilder()
        .addQueryParameter("code", "true").addQueryParameter("client_id", OAuthConfig.CLAUDE_CLIENT)
        .addQueryParameter("response_type", "code").addQueryParameter("redirect_uri", redirect)
        .addQueryParameter("scope", "user:profile").addQueryParameter("state", state)
        .addQueryParameter("code_challenge", OAuthConfig.challenge(verifier)).addQueryParameter("code_challenge_method", "S256").build().toString()
    suspend fun finishClaude(code: String, redirect: String, state: String, verifier: String): Credential = credential(api.post(claudeTokenUrl, buildJsonObject {
        put("grant_type", "authorization_code"); put("code", code); put("redirect_uri", redirect)
        put("client_id", OAuthConfig.CLAUDE_CLIENT); put("state", state); put("code_verifier", verifier)
    }))
    suspend fun startCodex(): DeviceLogin {
        val data = api.post("$openAiBase/api/accounts/deviceauth/usercode", buildJsonObject { put("client_id", OAuthConfig.OPENAI_CLIENT) })
        return DeviceLogin(data.text("device_auth_id") ?: throw ProviderException("OpenAI ne propose pas de connexion par code actuellement."),
            data.text("user_code") ?: data.text("usercode") ?: throw ProviderException("Code OpenAI manquant."), (data.long("interval") ?: 5).coerceIn(5, 30))
    }
    suspend fun finishCodex(login: DeviceLogin): Credential {
        while (System.currentTimeMillis() - login.startedAt < 15 * 60_000) {
            currentCoroutineContext().ensureActive()
            val data = try {
                api.post("$openAiBase/api/accounts/deviceauth/token", buildJsonObject { put("device_auth_id", login.deviceId); put("user_code", login.code) })
            } catch (e: ProviderException) {
                if (e.httpCode == 403 || e.httpCode == 404 || e.httpCode == 429) { delay(login.intervalSeconds * 1000); continue }
                throw e
            }
            val code = data.text("authorization_code") ?: throw ProviderException("Autorisation OpenAI incomplète.")
            val verifier = data.text("code_verifier") ?: throw ProviderException("Validation OpenAI incomplète.")
            val tokens = api.form("$openAiBase/oauth/token", mapOf("grant_type" to "authorization_code", "client_id" to OAuthConfig.OPENAI_CLIENT,
                "code" to code, "code_verifier" to verifier, "redirect_uri" to "$openAiBase/deviceauth/callback"))
            return credential(tokens)
        }
        throw ProviderException("Le code OpenAI a expiré. Relancez la connexion.")
    }
    fun googleUrl(redirect: String, state: String, verifier: String): String = "https://accounts.google.com/o/oauth2/v2/auth".toHttpUrl().newBuilder()
        .addQueryParameter("client_id", OAuthConfig.GOOGLE_CLIENT).addQueryParameter("redirect_uri", redirect)
        .addQueryParameter("response_type", "code").addQueryParameter("scope", OAuthConfig.GOOGLE_SCOPES.joinToString(" "))
        .addQueryParameter("access_type", "offline").addQueryParameter("prompt", "select_account consent")
        .addQueryParameter("state", state).addQueryParameter("code_challenge", OAuthConfig.challenge(verifier)).addQueryParameter("code_challenge_method", "S256").build().toString()
    suspend fun finishGoogle(code: String, redirect: String, verifier: String): Credential = credential(api.form(googleTokenUrl, mapOf(
        "grant_type" to "authorization_code", "client_id" to OAuthConfig.GOOGLE_CLIENT, "client_secret" to OAuthConfig.GOOGLE_INSTALLED_CLIENT_VALUE,
        "code" to code, "redirect_uri" to redirect, "code_verifier" to verifier)))
    suspend fun refresh(id: ProviderId, old: Credential): Credential {
        if (id != ProviderId.CODEX && id != ProviderId.ANTIGRAVITY && id != ProviderId.CLAUDE) return old
        if (old.refreshToken.isBlank()) throw ProviderException("Session expirée. Reconnectez ce compte.", needsLogin = true)
        val response = if (id == ProviderId.CLAUDE) api.post(claudeTokenUrl, buildJsonObject {
            put("client_id", OAuthConfig.CLAUDE_CLIENT); put("grant_type", "refresh_token"); put("refresh_token", old.refreshToken); put("scope", "user:profile")
        }) else if (id == ProviderId.CODEX) api.post("$openAiBase/oauth/token", buildJsonObject {
            put("client_id", OAuthConfig.OPENAI_CLIENT); put("grant_type", "refresh_token"); put("refresh_token", old.refreshToken)
        }) else api.form(googleTokenUrl, mapOf("client_id" to OAuthConfig.GOOGLE_CLIENT, "client_secret" to OAuthConfig.GOOGLE_INSTALLED_CLIENT_VALUE,
            "grant_type" to "refresh_token", "refresh_token" to old.refreshToken))
        val updated = credential(response)
        return updated.copy(refreshToken = updated.refreshToken.ifBlank { old.refreshToken }, accountId = old.accountId, projectId = old.projectId)
    }
    private fun credential(data: JsonElement): Credential {
        val token = data.text("access_token")?.takeIf { it.isNotBlank() } ?: throw ProviderException("Le fournisseur n’a pas renvoyé de session valide.")
        val claims = token.split('.').getOrNull(1)?.let { payload -> runCatching { Json.parseToJsonElement(Base64.getUrlDecoder().decode(payload).toString(Charsets.UTF_8)) }.getOrNull() }
        val account = claims?.obj("https://api.openai.com/auth")?.text("chatgpt_account_id") ?: ""
        return Credential(accessToken = token, refreshToken = data.text("refresh_token") ?: "", expiresAt = System.currentTimeMillis() + (data.long("expires_in") ?: 3600) * 1000, accountId = account)
    }
}
