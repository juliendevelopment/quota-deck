package be.julien.quotadeck

import be.julien.quotadeck.auth.*
import be.julien.quotadeck.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CodexDeviceProtocolTest {
    private fun grant() = MockResponse().setBody("""{"authorization_code":"fixture-grant","code_verifier":"fixture-verifier","code_challenge":"fixture-challenge"}""")
    private fun tokens() = MockResponse().setBody("""{"access_token":"fixture-access","refresh_token":"fixture-refresh","expires_in":3600}""")
    @Test fun temporaryPollingIoFailureDoesNotDiscardAuthorization() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(grant()); server.enqueue(tokens())
            val attempts = AtomicInteger(0)
            val api = ApiClient(OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).addInterceptor { chain ->
                if (chain.request().url.encodedPath.endsWith("deviceauth/token") && attempts.incrementAndGet() == 1) throw IOException("fixture temporary network interruption")
                chain.proceed(chain.request())
            }.build())
            val credential = OAuthService(api, openAiBase = server.url("/").toString().trimEnd('/')).finishCodex(DeviceLogin("fixture-device", "FIXTURE-CODE", 5))
            assertEquals("fixture-access", credential.accessToken)
            assertEquals(2, attempts.get())
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun explicitUserDenialIsNotTreatedAsPending() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"access_denied","error_description":"fixture-private-secret"}"""))
            server.enqueue(grant()); server.enqueue(tokens())
            val error = runCatching { OAuthService(openAiBase = server.url("/").toString().trimEnd('/')).finishCodex(DeviceLogin("fixture-device", "FIXTURE-CODE", 5)) }.exceptionOrNull()
            assertTrue("Explicit denial must stop rather than requesting a grant again", error is ProviderException)
            assertEquals("access_denied", (error as ProviderException).safeErrorCode)
            assertFalse(error.message!!.contains("fixture-private-secret"))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun officialPollingAndFormExchangeProtocolIsPreserved() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(grant()); server.enqueue(tokens())
            val base = server.url("/").toString().trimEnd('/')
            val credential = OAuthService(openAiBase = base).finishCodex(DeviceLogin("fixture-device", "FIXTURE-CODE", 5))
            assertEquals("fixture-access", credential.accessToken)
            val poll = server.takeRequest()
            assertEquals("/api/accounts/deviceauth/token", poll.path)
            val json = poll.body.readUtf8()
            assertTrue(json.contains("\"device_auth_id\":\"fixture-device\""))
            assertTrue(json.contains("\"user_code\":\"FIXTURE-CODE\""))
            val exchange = server.takeRequest()
            assertEquals("/oauth/token", exchange.path)
            assertTrue(exchange.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
            val fields = exchange.body.readUtf8().split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            assertEquals("authorization_code", fields["grant_type"])
            assertEquals("fixture-verifier", fields["code_verifier"])
            assertEquals("$base/deviceauth/callback", fields["redirect_uri"])
        }
    }
    @Test fun uncertainFinalTokenExchangeIsNotBlindlyRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(grant())
            val exchanges = AtomicInteger(0)
            val api = ApiClient(OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().url.encodedPath == "/oauth/token") { assertTrue("A single-use code must not be replayed by OkHttp either", chain.request().body!!.isOneShot()); exchanges.incrementAndGet(); throw IOException("fixture final exchange connection lost") }
                chain.proceed(chain.request())
            }.build())
            val error = runCatching { OAuthService(api, openAiBase = server.url("/").toString().trimEnd('/')).finishCodex(DeviceLogin("fixture-device", "FIXTURE-CODE", 5)) }.exceptionOrNull()
            assertTrue(error is ProviderException)
            assertEquals(1, exchanges.get())
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun retryAfterAndNestedPendingCodeAreSafeAndRecognized() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "19")
                .setBody("""{"error":{"code":"slow_down","message":"fixture-private-secret"}}"""))
            val error = runCatching { ApiClient().post(server.url("/poll").toString(), kotlinx.serialization.json.buildJsonObject {}) }.exceptionOrNull() as ProviderException
            assertEquals(19_000L, error.retryAfterMillis)
            assertEquals("slow_down", error.safeErrorCode)
            assertFalse(error.message!!.contains("fixture-private-secret"))
        }
    }
    @Test fun serverPollingIntervalIsRespectedEvenWhenAboveThirtySeconds() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"device_auth_id":"fixture-device","user_code":"FIXTURE-CODE","interval":" 120 "}"""))
            val login = OAuthService(openAiBase = server.url("/").toString().trimEnd('/')).startCodex()
            assertEquals(120L, login.intervalSeconds)
        }
    }
    @Test fun opaqueAccessTokenUsesWorkspaceFromIdentityToken() = runBlocking {
        MockWebServer().use { server ->
            val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                """{"https://api.openai.com/auth":{"chatgpt_account_id":"fixture-workspace"}}""".toByteArray())
            server.enqueue(grant())
            server.enqueue(MockResponse().setBody("""{"access_token":"fixture-opaque-access","id_token":"e30.$payload.fixture","refresh_token":"fixture-refresh","expires_in":3600}"""))
            val credential = OAuthService(openAiBase = server.url("/").toString().trimEnd('/')).finishCodex(DeviceLogin("fixture-device", "FIXTURE-CODE", 5))
            assertEquals("fixture-workspace", credential.accountId)
        }
    }
    @Test fun refreshRepairsMissingWorkspaceWithoutSwitchingAnExistingSelection() = runBlocking {
        MockWebServer().use { server ->
            val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"https://api.openai.com/auth":{"chatgpt_account_id":"fixture-new-workspace"}}""".toByteArray())
            repeat(2) { server.enqueue(MockResponse().setBody("""{"access_token":"fixture-access","id_token":"e30.$payload.fixture","refresh_token":"fixture-refresh","expires_in":3600}""")) }
            val service = OAuthService(openAiBase = server.url("/").toString().trimEnd('/'))
            val missing = service.refresh(be.julien.quotadeck.core.ProviderId.CODEX, be.julien.quotadeck.core.Credential(refreshToken = "fixture-refresh"))
            assertEquals("fixture-new-workspace", missing.accountId)
            val selected = service.refresh(be.julien.quotadeck.core.ProviderId.CODEX, be.julien.quotadeck.core.Credential(refreshToken = "fixture-refresh", accountId = "fixture-selected-workspace"))
            assertEquals("fixture-selected-workspace", selected.accountId)
        }
    }
    @Test fun expiredCodeDoesNotSendAnyRequest() = runBlocking {
        MockWebServer().use { server ->
            val error = runCatching { OAuthService(openAiBase = server.url("/").toString().trimEnd('/')).finishCodex(DeviceLogin("fixture-device", "FIXTURE-CODE", 5, System.currentTimeMillis() - 16 * 60_000)) }.exceptionOrNull()
            assertTrue(error is ProviderException)
            assertTrue(error!!.message!!.contains("expired"))
            assertEquals(0, server.requestCount)
        }
    }
}
