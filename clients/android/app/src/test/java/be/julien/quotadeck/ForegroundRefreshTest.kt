package be.julien.quotadeck

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import be.julien.quotadeck.core.Credential
import be.julien.quotadeck.core.ProviderId
import be.julien.quotadeck.core.ProviderSnapshot
import be.julien.quotadeck.core.QuotaWindow
import be.julien.quotadeck.data.ProviderReader
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundRefreshTest {
    @Before fun mainDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun refreshesConnectedQuotasOnInitialAndReturningForeground() = runBlocking {
        val store = MemoryAccountStore().apply {
            save(ProviderId.CLAUDE, Credential(accessToken = "fixture-token"))
        }
        val reads = AtomicInteger()
        val reader = object : ProviderReader {
            override suspend fun fetch(id: ProviderId, credential: Credential): Pair<Credential, ProviderSnapshot> {
                reads.incrementAndGet()
                return credential to ProviderSnapshot(id, windows = listOf(QuotaWindow("Fixture", 75.0)))
            }
        }
        val model = DashboardViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            store,
            reader,
        )
        val owner = ViewModelStore().apply { put("foreground-refresh", model) }
        try {
            assertEquals("Quota reads wait until the dashboard becomes visible", 0, reads.get())

            model.onUiResumed()
            withTimeout(3_000) { while (reads.get() < 1) delay(10) }

            model.setUiActive(false) // User presses Home; the process and ViewModel remain alive.
            model.onUiResumed() // User opens QuotaDeck again.
            withTimeout(3_000) { while (reads.get() < 2) delay(10) }

            assertEquals("Every foreground entry refreshes the connected account", 2, reads.get())
        } finally {
            owner.clear()
        }
    }
}
