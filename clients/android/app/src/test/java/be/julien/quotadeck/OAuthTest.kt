package be.julien.quotadeck

import be.julien.quotadeck.auth.*
import be.julien.quotadeck.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class OAuthTest {
    @Test fun pkceMatchesRfc7636() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OAuthConfig.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertNotEquals(OAuthConfig.random(), OAuthConfig.random())
    }
    @Test fun googleUsesStatePkceAndExternalBrowserEndpoint() {
        val url = OAuthService().googleUrl("http://127.0.0.1:12345/oauth-callback", "csrf", "verifier").toHttpUrl()
        assertEquals("accounts.google.com", url.host)
        assertEquals("csrf", url.queryParameter("state"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("http://127.0.0.1:12345/oauth-callback", url.queryParameter("redirect_uri"))
    }
    @Test fun codexDeviceProtocolAndExchangeAreComplete() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"device_auth_id":"device","user_code":"ABCD-EFGH","interval":"5"}"""))
            server.enqueue(MockResponse().setBody("""{"authorization_code":"code","code_verifier":"verifier","code_challenge":"challenge"}"""))
            server.enqueue(MockResponse().setBody("""{"access_token":"test-access","refresh_token":"test-refresh","expires_in":3600}"""))
            val oauth = OAuthService(ApiClient(), server.url("/").toString().trimEnd('/'))
            val device = oauth.startCodex()
            assertEquals("ABCD-EFGH", device.code)
            val credential = oauth.finishCodex(device)
            assertEquals("test-access", credential.accessToken)
            assertEquals("test-refresh", credential.refreshToken)
            assertEquals("/api/accounts/deviceauth/usercode", server.takeRequest().path)
            assertEquals("/api/accounts/deviceauth/token", server.takeRequest().path)
            val exchange = server.takeRequest()
            assertEquals("/oauth/token", exchange.path)
            assertTrue(exchange.body.readUtf8().contains("code_verifier=verifier"))
        }
    }
    @Test fun callbackAcceptsCorrectStateAndRejectsCsrf() = runBlocking {
        suspend fun request(state: String): Result<String> = coroutineScope {
            LoopbackReceiver().use { receiver ->
                val receive = async { runCatching { receiver.receive("expected") } }
                val uri = java.net.URI(receiver.redirectUri)
                withContext(Dispatchers.IO) {
                    Socket("127.0.0.1", uri.port).use {
                        it.getOutputStream().write("GET /oauth-callback?state=$state&code=local-code HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                        it.getOutputStream().flush()
                        it.getInputStream().readBytes()
                    }
                }
                receive.await()
            }
        }
        assertEquals("local-code", request("expected").getOrThrow())
        assertTrue(request("attacker").exceptionOrNull() is ProviderException)
    }
    @Test fun queryPreservesEncodedValues() { assertEquals("a+b/c", LoopbackReceiver.query("code=a%2Bb%2Fc&state=s")["code"]) }
}
