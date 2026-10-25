package be.julien.quotadeck

import be.julien.quotadeck.auth.*
import be.julien.quotadeck.core.*
import be.julien.quotadeck.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI

class ClaudeOAuthRegressionTest {
    @Test fun claudeAuthorizationUsesExternalProviderOriginPkceAndProfileOnly() {
        val redirect = "http://localhost:12345/callback"
        val url = OAuthService().claudeUrl(redirect, "csrf", "verifier").toHttpUrl()
        assertEquals("claude.com", url.host)
        assertEquals("/cai/oauth/authorize", url.encodedPath)
        assertEquals(redirect, url.queryParameter("redirect_uri"))
        assertEquals("user:profile", url.queryParameter("scope"))
        assertEquals("csrf", url.queryParameter("state"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(OAuthConfig.challenge("verifier"), url.queryParameter("code_challenge"))
        assertEquals("true", url.queryParameter("code"))
    }
    @Test fun claudeExchangesAuthorizationCodeWithMatchingRedirectStateAndVerifier() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"fixture-access","refresh_token":"fixture-refresh","expires_in":3600}"""))
            val service = OAuthService(ApiClient(), claudeTokenUrl = server.url("/v1/oauth/token").toString())
            val credential = service.finishClaude("fixture-code", "http://localhost:12345/callback", "csrf", "verifier")
            val request = server.takeRequest()
            val body = Json.parseToJsonElement(request.body.readUtf8())
            assertEquals("/v1/oauth/token", request.path)
            assertEquals("authorization_code", body.text("grant_type"))
            assertEquals("http://localhost:12345/callback", body.text("redirect_uri"))
            assertEquals("csrf", body.text("state"))
            assertEquals("verifier", body.text("code_verifier"))
            assertEquals("fixture-access", credential.accessToken)
            assertEquals("fixture-refresh", credential.refreshToken)
        }
    }
    @Test fun claudeRefreshRotatesTokenWithoutRequestingInferenceScope() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}"""))
            val service = OAuthService(ApiClient(), claudeTokenUrl = server.url("/v1/oauth/token").toString())
            val result = service.refresh(ProviderId.CLAUDE, Credential(accessToken = "old", refreshToken = "old-refresh"))
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            assertEquals("refresh_token", body.text("grant_type"))
            assertEquals("old-refresh", body.text("refresh_token"))
            assertEquals("user:profile", body.text("scope"))
            assertEquals("new-refresh", result.refreshToken)
        }
    }
    @Test fun claudeUsageUsesOAuthRatherThanBrowserCookies() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"five_hour":{"utilization":25}}"""))
            val service = ProviderService(ApiClient(), server.url("/api/oauth/usage").toString())
            val result = service.fetch(ProviderId.CLAUDE, Credential(accessToken = "fixture-access"))
            val request = server.takeRequest()
            assertEquals("Bearer fixture-access", request.getHeader("Authorization"))
            assertEquals("oauth-2025-04-20", request.getHeader("anthropic-beta"))
            assertNull(request.getHeader("Cookie"))
            assertEquals(75.0, result.second.windows.single().remainingPercent!!, 0.01)
        }
    }
    @Test fun nativeClaudeCallbackWorksWithOfficialLocalhostPath() = runBlocking {
        LoopbackReceiver("/callback", "localhost").use { receiver ->
            val uri = URI(receiver.redirectUri)
            assertEquals("localhost", uri.host)
            assertEquals("/callback", uri.path)
            val result = async { receiver.receive("csrf") }
            withContext(Dispatchers.IO) {
                Socket("localhost", uri.port).use {
                    it.getOutputStream().write("GET /callback?code=fixture-code&state=csrf HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                    it.getOutputStream().flush()
                    assertTrue(it.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 200 OK"))
                }
            }
            assertEquals("fixture-code", result.await())
        }
    }
}
