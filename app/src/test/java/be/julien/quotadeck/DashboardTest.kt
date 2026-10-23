package be.julien.quotadeck

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import be.julien.quotadeck.core.*
import be.julien.quotadeck.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardTest {
    @get:Rule val compose = createComposeRule()
    @Test fun rendersProvidersAndConnectButtonWorks() {
        var chosen: ProviderId? = null
        compose.setContent { QuotaDeckTheme { DashboardScreen(ProviderId.entries.map { ProviderState(it) }, false, {}, { chosen = it }, {}, {}, {}) } }
        compose.onNodeWithText("0 / 4 comptes connectés").assertIsDisplayed()
        compose.onNodeWithText("Connecter Claude").performClick()
        assertEquals(ProviderId.CLAUDE, chosen)
        compose.onNodeWithTag("provider-list").performScrollToNode(hasText("Connecter OpenCode Go"))
        compose.onNodeWithText("Connecter OpenCode Go").assertIsDisplayed()
    }
    @Test fun displaysActualRemainingQuotaAndNeverMakesUpUnknownQuota() {
        val snapshot = ProviderSnapshot(ProviderId.CODEX, windows = listOf(QuotaWindow("Session", 73.0), QuotaWindow("Sans donnée")))
        compose.setContent { QuotaDeckTheme { DashboardScreen(listOf(ProviderState(ProviderId.CODEX, true, snapshot = snapshot)), false, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("73 % restant").assertIsDisplayed()
        compose.onNodeWithText("Non communiqué").assertIsDisplayed()
    }
}
