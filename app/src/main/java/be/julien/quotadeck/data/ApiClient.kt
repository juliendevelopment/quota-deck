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
    val safeErrorCode: String? = null, val networkFailure: NetworkFailure? = null, val endpoint: String? = null,
    val retryAfterMillis: Long? = null) : IOException(message)

class ApiClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()) {
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): JsonElement = request(url, headers, null)
    suspend fun post(url: String, body: JsonElement, headers: Map<String, String> = emptyMap()): JsonElement =
        request(url, headers, body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
    suspend fun form(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap(), singleUse: Boolean = false): JsonElement {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val body = if (!singleUse) form else object : RequestBody() {
            override fun contentType() = form.contentType()
            override fun contentLength() = form.contentLength()
            override fun writeTo(sink: okio.BufferedSink) = form.writeTo(sink)
            override fun isOneShot() = true
        }
        return request(url, headers, body)
    }
    private suspend fun request(url: String, headers: Map<String, String>, body: RequestBody?): JsonElement = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "QuotaDeck/${be.julien.quotadeck.BuildConfig.VERSION_NAME} Android")
            .apply { headers.forEach { (k, v) -> header(k, v) }; if (body != null) post(body) }.build()
        val endpoint = request.url.host + request.url.encodedPath
            .replace(Regex("/[A-Fa-f0-9-]{20,}"), "/{id}")
            .replace(Regex("/(?:org_|wrk_)[A-Za-z0-9_-]+"), "/{id}")
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val root = runCatching { json.parseToJsonElement(response.peekBody(8192).string()) }.getOrNull()
                    val oauthCode = listOfNotNull(root?.text("error"), root?.obj("error")?.text("code"), root?.obj("error")?.text("type")).firstOrNull { it in FailureReport.oauthCodes }
                    val message = when (oauthCode) {
                        "invalid_grant" -> "The sign-in code has expired, was already used or does not match this request. [invalid_grant]"
                        "invalid_client", "unauthorized_client" -> "The provider rejected this OAuth client. [$oauthCode]"
                        "invalid_scope", "insufficient_scope" -> "The provider rejected the requested read permissions. [$oauthCode]"
                        "access_denied" -> "Authorization denied by the provider. [access_denied]"
                        "expired_token" -> "The device code has expired. Start sign-in again. [expired_token]"
                        else -> when (response.code) {
                        401 -> "Session expired. Reconnect this account."
                        403 -> "Access denied by the provider (403). Reconnect or check the official page."
                        429 -> "Too many requests. Wait before refreshing."
                        in 300..399 -> "The provider requires a new sign-in."
                        else -> "The provider is unavailable (HTTP ${response.code})."
                        }
                    }
                    val retryAfter = response.header("Retry-After")?.let { raw ->
                        raw.trim().toLongOrNull()?.coerceIn(0, 86_400)?.times(1000)
                            ?: runCatching { (java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceIn(0, 86_400_000) }.getOrNull()
                    }
                    throw ProviderException(message, response.code == 401 || response.code in 300..399, response.code, oauthCode, endpoint = endpoint, retryAfterMillis = retryAfter)
                }
                val text = response.body?.string() ?: throw ProviderException("Empty provider response.")
                if (text.length > 4_000_000) throw ProviderException("Provider response is too large.")
                try {
                    json.parseToJsonElement(text).also {
                        if (it !is JsonObject && it !is JsonArray) throw IllegalArgumentException("Unexpected scalar response")
                    }
                }
                catch (_: IllegalArgumentException) { throw ProviderException("Unexpected provider response (not JSON data).") }
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
            throw ProviderException("Connection failed: ${kind.userLabel}. Try again with QuotaDeck in the foreground.", networkFailure = kind, endpoint = endpoint)
        }
    }
}

fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
fun JsonElement.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
fun JsonElement.text(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
fun JsonElement.number(key: String): Double? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
fun JsonElement.long(key: String): Long? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.longOrNull
fun JsonElement.flag(key: String): Boolean? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull
