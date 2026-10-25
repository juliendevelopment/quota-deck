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
import java.io.IOException

/** Receives only the browser's OAuth callback, on this PHONE's loopback interface. No VPS listener. */
class LoopbackReceiver(private val callbackPath: String = "/oauth-callback", private val callbackHost: String = "127.0.0.1") : AutoCloseable {
    init { require(callbackPath in setOf("/oauth-callback", "/callback")); require(callbackHost in setOf("127.0.0.1", "localhost")) }
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 750 }
    val redirectUri = "http://$callbackHost:${server.localPort}$callbackPath"
    suspend fun receive(state: String): String = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + 5 * 60_000
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            val code = socket.use { readCode(it, state) }
            if (code != null) return@withContext code
        }
        throw ProviderException("Sign-in timed out after 5 minutes. Please try again.")
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
        if (uri.path != callbackPath) { respond(socket, false); return null }
        val params = query(uri.rawQuery)
        val valid = params["state"] == state && !params["code"].isNullOrBlank() && params["error"] == null
        // The browser may close immediately after redirect. A response write failure must not discard a valid code.
        try { respond(socket, valid) } catch (_: IOException) { /* callback already validated; browser may have closed */ }
        if (!valid) throw ProviderException("Sign-in cancelled or invalid security response.")
        return params.getValue("code")
    }
    private fun respond(socket: java.net.Socket, success: Boolean) {
        val title = if (success) "Authorization received" else "Invalid authorization"
        val explanation = if (success) "QuotaDeck received the authorization code. Return to the app to exchange it for a token and read your quotas. This page does not yet confirm a successful account sign-in." else "The response is invalid. Return to the app to restart sign-in."
        val body = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>QuotaDeck</title><style>body{font:18px sans-serif;max-width:36rem;margin:3rem auto;padding:1.5rem;color:#202630;background:#f7faf9}h1{font-size:1.7rem}a{display:block;margin:2rem 0;padding:1rem;border-radius:12px;background:#9edbc3;color:#10131b;text-align:center;text-decoration:none;font-weight:bold}p{line-height:1.6}</style></head><body><h1>$title</h1><p>$explanation</p><a href="intent://oauth/return#Intent;scheme=quotadeck;package=be.julien.quotadeck;end">Return to QuotaDeck</a><p>If the button does not work, close this tab and return to the app manually.</p></body></html>"""
        socket.getOutputStream().write(("HTTP/1.1 ${if (success) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'\r\nConnection: close\r\n\r\n$body").toByteArray(Charsets.UTF_8))
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
