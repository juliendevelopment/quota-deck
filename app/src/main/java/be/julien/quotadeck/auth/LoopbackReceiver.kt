package be.julien.quotadeck.auth

import be.julien.quotadeck.data.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder

/** Receives only the browser's OAuth callback, on this PHONE's loopback interface. No VPS listener. */
class LoopbackReceiver : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 750 }
    val redirectUri = "http://127.0.0.1:${server.localPort}/oauth-callback"
    suspend fun receive(state: String): String = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + 5 * 60_000
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            val code = socket.use { readCode(it, state) }
            if (code != null) return@withContext code
        }
        throw ProviderException("La connexion Google a expiré. Réessayez.")
    }
    private fun readCode(socket: java.net.Socket, state: String): String? {
        socket.soTimeout = 2500
        val input = socket.getInputStream()
        val line = StringBuilder()
        while (line.length < 8192) {
            val byte = input.read()
            if (byte == -1 || byte == 10) break
            if (byte != 13) line.append(byte.toChar())
        }
        val path = line.toString().split(' ').getOrNull(1) ?: return null
        val uri = runCatching { URI(path) }.getOrNull() ?: return null
        if (uri.path != "/oauth-callback") { respond(socket, false); return null }
        val params = query(uri.rawQuery)
        val valid = params["state"] == state && !params["code"].isNullOrBlank() && params["error"] == null
        respond(socket, valid)
        if (!valid) throw ProviderException("Connexion Google annulée ou réponse de sécurité invalide.")
        return params.getValue("code")
    }
    private fun respond(socket: java.net.Socket, success: Boolean) {
        val body = if (success) "Connexion terminee. Revenez dans QuotaDeck." else "Connexion non valide. Revenez dans QuotaDeck."
        socket.getOutputStream().write(("HTTP/1.1 ${if (success) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n$body").toByteArray())
        socket.getOutputStream().flush()
    }
    override fun close() { server.close() }
    companion object {
        fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { it.contains('=') }.associate {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
        }
    }
}
