package be.julien.quotadeck

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import be.julien.quotadeck.auth.OAuthService
import be.julien.quotadeck.core.*
import be.julien.quotadeck.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class CodexResumeRegressionTest {
    @Before fun mainDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun resetMain() { Dispatchers.resetMain() }
    @Test fun codexKeepsPendingCodeAndFinishesAfterBrowserReturn() = runBlocking { exercise(false) }
    @Test fun cancellingWhileBrowserIsOpenPreventsLaterTokenAndStoreCalls() = runBlocking { exercise(true) }
    private suspend fun exercise(cancel: Boolean) {
        MockWebServer().use { server ->
            val foreground = AtomicBoolean(true)
            val blocked = AtomicInteger(0)
            val api = ApiClient(OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).addInterceptor { chain ->
                if (chain.request().url.encodedPath.endsWith("deviceauth/token") && !foreground.get()) {
                    blocked.incrementAndGet(); throw IOException("EPERM fixture background network denied")
                }
                val request = chain.request()
                if (request.url.host == "chatgpt.com") chain.proceed(request.newBuilder().url(server.url("/fixture/usage")).build())
                else chain.proceed(request)
            }.build())
            val store = MemoryAccountStore()
            val reader = ProviderService(api) // Real connector/parser; all HTTP goes to local fixtures.
            val model = DashboardViewModel(ApplicationProvider.getApplicationContext<Application>(), store, reader, OAuthService(api, openAiBase = server.url("/").toString().trimEnd('/')))
            val owner = ViewModelStore().apply { put("codex-fixture", model) }
            try {
                model.beginConnection(ProviderId.CODEX)
                model.startConnection { }
                val first = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }
                assertNotNull("The initial device-code request must reach the local fixture server", first)
                assertEquals("/api/accounts/deviceauth/usercode", first!!.path)
                // The browser takes focus while the initial device-code response is returning.
                foreground.set(false); model.setUiActive(false)
                server.enqueue(MockResponse().setBody("""{"device_auth_id":"fixture-device","user_code":"FIXTURE-CODE","interval":"5"}"""))
                server.enqueue(MockResponse().setBody("""{"authorization_code":"fixture-grant","code_verifier":"fixture-verifier","code_challenge":"fixture-challenge"}"""))
                val identity = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"https://api.openai.com/auth":{"chatgpt_account_id":"fixture-workspace"}}""".toByteArray())
                server.enqueue(MockResponse().setBody("""{"access_token":"fixture-access","id_token":"e30.$identity.fixture","refresh_token":"fixture-refresh","expires_in":3600}"""))
                server.enqueue(MockResponse().setBody("""{"plan_type":"plus","rate_limit":{"primary_window":{"used_percent":30,"limit_window_seconds":18000}}}"""))
                withTimeout(4000) {
                    while (blocked.get() == 0 && !(model.login.value?.stage == "device" && model.login.value?.phase == AuthPhase.BROWSER)) delay(10)
                }
                assertEquals("Polling must not fail behind Chrome and discard the pending device login", 0, blocked.get())
                assertEquals("FIXTURE-CODE", model.login.value?.device?.code)
                assertNull(store.credential(ProviderId.CODEX))
                assertEquals(1, server.requestCount)
                if (cancel) {
                    model.cancelLogin()
                    foreground.set(true); model.setUiActive(true)
                    delay(150)
                    assertNull(model.login.value)
                    assertNull(store.credential(ProviderId.CODEX))
                    assertEquals(1, server.requestCount)
                    return@use
                }
                foreground.set(true); model.setUiActive(true)
                val ready = withTimeout(5000) { model.states.first { rows -> rows.any { it.id == ProviderId.CODEX && it.connected && !it.loading && (it.snapshot != null || it.error != null) } } }.first { it.id == ProviderId.CODEX }
                assertNull(ready.error)
                assertEquals("fixture-access", store.credential(ProviderId.CODEX)?.accessToken)
                assertNull(model.login.value)
                assertEquals("fixture-workspace", store.credential(ProviderId.CODEX)?.accountId)
                assertEquals(70.0, ready.snapshot!!.windows.single().remainingPercent!!, 0.01)
                assertEquals(4, server.requestCount)
                server.takeRequest(); server.takeRequest() // initial request was consumed above; now poll + exchange
                val usage = server.takeRequest()
                assertEquals("/fixture/usage", usage.path)
                assertEquals("Bearer fixture-access", usage.getHeader("Authorization"))
                assertEquals("fixture-workspace", usage.getHeader("ChatGPT-Account-Id"))
            } finally { owner.clear() }
        }
    }
}
