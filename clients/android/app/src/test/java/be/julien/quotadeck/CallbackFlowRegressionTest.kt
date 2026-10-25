package be.julien.quotadeck

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelStore
import be.julien.quotadeck.auth.*
import be.julien.quotadeck.core.*
import be.julien.quotadeck.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.URI
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A real callback + HTTP token exchange with a simulated Android background-network restriction. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class CallbackFlowRegressionTest {
    @Before fun mainDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun resetMain() { Dispatchers.resetMain() }
    @Test fun claudeDoesNotExchangeTokenWhileBrowserIsForeground() = runBlocking { exercise(ProviderId.CLAUDE) }
    @Test fun googleDoesNotExchangeTokenWhileBrowserIsForeground() = runBlocking { exercise(ProviderId.ANTIGRAVITY) }
    private suspend fun exercise(id: ProviderId) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"fixture-access","refresh_token":"fixture-refresh","expires_in":3600}"""))
            val visible = AtomicBoolean(true)
            val blockedNetworkAttempts = AtomicInteger(0)
            val api = ApiClient(OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).addInterceptor { chain ->
                if (!visible.get()) { blockedNetworkAttempts.incrementAndGet(); throw IOException("fixture Android background network denied") }
                chain.proceed(chain.request())
            }.build())
            val store = MemoryAccountStore()
            val reader = object : ProviderReader {
                override suspend fun fetch(id: ProviderId, credential: Credential) = credential to ProviderSnapshot(id, windows = listOf(QuotaWindow("Fixture", 75.0)))
            }
            val oauth = OAuthService(api, claudeTokenUrl = server.url("/token").toString(), googleTokenUrl = server.url("/token").toString())
            val model = DashboardViewModel(ApplicationProvider.getApplicationContext<Application>(), store, reader, oauth)
            val owner = ViewModelStore().apply { put("fixture", model) }
            try {
                val opened = CompletableDeferred<String>()
                model.beginConnection(id)
                model.startConnection { url -> visible.set(false); model.setUiActive(false); opened.complete(url) }
                val authorization = withTimeout(5000) { opened.await() }.toHttpUrl()
                val redirect = authorization.queryParameter("redirect_uri")!!
                val state = authorization.queryParameter("state")!!
                withContext(Dispatchers.IO) { URL("$redirect?code=fixture-code&state=$state").openStream().bufferedReader().use { it.readText() } }
                withTimeout(3000) {
                    while (blockedNetworkAttempts.get() == 0 && model.login.value?.stage != "callback") delay(10)
                }
                assertEquals("No external token request may run while the app is behind the browser", 0, blockedNetworkAttempts.get())
                assertEquals("callback", model.login.value?.stage)
                assertNull(store.credential(id))
                visible.set(true)
                model.setUiActive(true)
                val connected = withTimeout(5000) { model.states.first { rows -> rows.any { it.id == id && it.connected && !it.loading } } }.first { it.id == id }
                assertNull(connected.error)
                assertEquals("fixture-access", store.credential(id)?.accessToken)
                assertEquals(1, server.requestCount)
            } finally { owner.clear() }
        }
    }
    @Test fun callbackPageDoesNotClaimConnectionCompleteBeforeTokenExchange() = runBlocking {
        LoopbackReceiver().use { receiver ->
            val code = async { receiver.receive("fixture-state") }
            val uri = URI(receiver.redirectUri)
            val page = withContext(Dispatchers.IO) {
                Socket("127.0.0.1", uri.port).use {
                    it.getOutputStream().write("GET /oauth-callback?code=fixture-code&state=fixture-state HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                    it.getOutputStream().flush()
                    it.getInputStream().bufferedReader().readText()
                }
            }
            assertEquals("fixture-code", code.await())
            assertFalse("Receiving an authorization code is not a successful token exchange", page.contains("Connection complete."))
            assertTrue(page.contains("Authorization received"))
            assertTrue(page.contains("<html lang=\"en\">"))
            assertTrue(page.contains("Return to QuotaDeck"))
        }
    }
}

class MemoryAccountStore : AccountStore {
    private val credentials = ConcurrentHashMap<ProviderId, Credential>()
    private val snapshots = ConcurrentHashMap<ProviderId, ProviderSnapshot>()
    override fun credential(id: ProviderId) = credentials[id]
    override fun save(id: ProviderId, credential: Credential) { credentials[id] = credential }
    override fun snapshot(id: ProviderId) = snapshots[id]
    override fun save(snapshot: ProviderSnapshot) { snapshots[snapshot.provider] = snapshot }
    override fun forget(id: ProviderId) { credentials.remove(id); snapshots.remove(id) }
}
