package be.julien.quotadeck.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit

class ProviderException(message: String, val needsLogin: Boolean = false, val httpCode: Int? = null,
    val safeErrorCode: String? = null, val networkFailure: NetworkFailure? = null, val endpoint: String? = null) : IOException(message)

class ApiClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()) {
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): JsonElement = request(url, headers, null)
    suspend fun post(url: String, body: JsonElement, headers: Map<String, String> = emptyMap()): JsonElement =
        request(url, headers, body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
    suspend fun form(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()): JsonElement =
        request(url, headers, FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build())
    private suspend fun request(url: String, headers: Map<String, String>, body: RequestBody?): JsonElement = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "QuotaDeck/0.1.2 Android")
            .apply { headers.forEach { (k, v) -> header(k, v) }; if (body != null) post(body) }.build()
        val endpoint = request.url.host + request.url.encodedPath
            .replace(Regex("/[A-Fa-f0-9-]{20,}"), "/{id}")
            .replace(Regex("/(?:org_|wrk_)[A-Za-z0-9_-]+"), "/{id}")
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val root = runCatching { json.parseToJsonElement(response.peekBody(8192).string()) }.getOrNull()
                    val oauthCode = (root?.text("error") ?: root?.obj("error")?.text("type"))?.takeIf { it in FailureReport.oauthCodes }
                    val message = when (oauthCode) {
                        "invalid_grant" -> "Le code de connexion est expiré, déjà utilisé ou ne correspond pas à cette demande. [invalid_grant]"
                        "invalid_client", "unauthorized_client" -> "Le fournisseur refuse ce client OAuth. [$oauthCode]"
                        "invalid_scope", "insufficient_scope" -> "Le fournisseur refuse les autorisations de lecture demandées. [$oauthCode]"
                        "access_denied" -> "Autorisation refusée par le fournisseur. [access_denied]"
                        else -> when (response.code) {
                        401 -> "Session expirée. Reconnectez ce compte."
                        403 -> "Accès refusé par le fournisseur (403). Essayez une reconnexion ou consultez la page officielle."
                        429 -> "Trop de requêtes. Patientez avant d’actualiser."
                        in 300..399 -> "Le fournisseur demande une nouvelle connexion."
                        else -> "Le fournisseur est indisponible (HTTP ${response.code})."
                        }
                    }
                    throw ProviderException(message, response.code == 401 || response.code in 300..399, response.code, oauthCode, endpoint = endpoint)
                }
                val text = response.body?.string() ?: throw ProviderException("Réponse vide du fournisseur.")
                if (text.length > 4_000_000) throw ProviderException("Réponse du fournisseur trop volumineuse.")
                try {
                    json.parseToJsonElement(text).also {
                        if (it !is JsonObject && it !is JsonArray) throw IllegalArgumentException("Unexpected scalar response")
                    }
                }
                catch (_: IllegalArgumentException) { throw ProviderException("Réponse inattendue du fournisseur (pas de données JSON).") }
            }
        } catch (e: ProviderException) { throw e }
        catch (e: IOException) {
            val hint = e.message.orEmpty().lowercase()
            val kind = when {
                "eperm" in hint || "eacces" in hint || "not permitted" in hint || "network denied" in hint -> NetworkFailure.RESTRICTED
                e is UnknownHostException -> NetworkFailure.DNS
                e is SSLException -> NetworkFailure.TLS
                e is InterruptedIOException -> NetworkFailure.TIMEOUT
                e is ConnectException || e is SocketException -> NetworkFailure.CONNECTION
                else -> NetworkFailure.IO
            }
            throw ProviderException("Connexion impossible : ${kind.userLabel}. Réessayez lorsque QuotaDeck est au premier plan.", networkFailure = kind, endpoint = endpoint)
        }
    }
}

fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
fun JsonElement.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
fun JsonElement.text(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
fun JsonElement.number(key: String): Double? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
fun JsonElement.long(key: String): Long? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.longOrNull
fun JsonElement.flag(key: String): Boolean? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull
