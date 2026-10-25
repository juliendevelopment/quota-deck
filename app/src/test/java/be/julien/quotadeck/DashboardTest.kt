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
        compose.onNodeWithText("0 / 4 accounts connected").assertIsDisplayed()
        compose.onNodeWithText("Connect Claude").performClick()
        assertEquals(ProviderId.CLAUDE, chosen)
        compose.onNodeWithTag("provider-list").performScrollToNode(hasText("Connect OpenCode Go"))
        compose.onNodeWithText("Connect OpenCode Go").assertIsDisplayed()
    }
    @Test fun displaysActualRemainingQuotaAndNeverMakesUpUnknownQuota() {
        val snapshot = ProviderSnapshot(ProviderId.CODEX, windows = listOf(QuotaWindow("Session", 73.0), QuotaWindow("No data")))
        compose.setContent { QuotaDeckTheme { DashboardScreen(listOf(ProviderState(ProviderId.CODEX, true, snapshot = snapshot)), false, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("73% left").assertIsDisplayed()
        compose.onNodeWithText("Not reported").assertIsDisplayed()
    }
}
