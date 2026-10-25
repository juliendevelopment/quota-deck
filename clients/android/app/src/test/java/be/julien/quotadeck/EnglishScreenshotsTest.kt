package be.julien.quotadeck

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import be.julien.quotadeck.core.*
import be.julien.quotadeck.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/** Real Compose rendering with explicitly synthetic fixtures, not a live-account quota reading. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnglishScreenshotsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun displayFixtures() {
        val now = Instant.now()
        val states = ProviderId.entries.map { id ->
            val windows = when (id) {
                ProviderId.CLAUDE -> listOf(QuotaWindow("Session · 5 hours", 75.0, now.plusSeconds(4 * 3600L).toString()), QuotaWindow("Week · all models", 50.0, now.plusSeconds(3 * 86400L).toString()))
                ProviderId.CODEX -> listOf(QuotaWindow("Session · 5 hours", 60.0, now.plusSeconds(2 * 3600L).toString()), QuotaWindow("Week", 85.0, now.plusSeconds(6 * 86400L).toString()), QuotaWindow("Credits", detail = "Reported balance: 0"))
                ProviderId.ANTIGRAVITY -> listOf(QuotaWindow("Gemini · weekly limit", 40.0, now.plusSeconds(5 * 86400L).toString()), QuotaWindow("Gemini · 5-hour limit", 60.0, now.plusSeconds(3 * 3600L).toString()))
                ProviderId.OPENCODE -> listOf(QuotaWindow("Session · 5 hours", 70.0), QuotaWindow("Week", 80.0))
            }
            ProviderState(id, connected = true, snapshot = ProviderSnapshot(id, plan = when (id) {
                ProviderId.CLAUDE -> "Pro"; ProviderId.CODEX -> "Plus"; ProviderId.ANTIGRAVITY -> "Google AI Pro"; ProviderId.OPENCODE -> "Go"
            }, windows = windows))
        }
        compose.activity.setContent { QuotaDeckTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) { DashboardScreen(states, false, {}, {}, {}, {}, {}) } } }
    }
    private fun save(name: String) {
        val image = compose.runOnIdle { compose.activity.window.decorView.drawToBitmap() }
        val colors = mutableSetOf<Int>()
        for (x in 0 until image.width step 7) for (y in 0 until image.height step 7) colors.add(image.getPixel(x, y))
        assertTrue(colors.size > 20)
        val file = File(System.getProperty("user.dir"), "build/qa/$name")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(file.length() > 1000)
    }
    @Test fun englishOverviewRendersEvenOnFrenchLocale() {
        displayFixtures()
        compose.onNodeWithText("AI at a glance.").assertIsDisplayed()
        compose.onNodeWithText("4 / 4 accounts connected").assertIsDisplayed()
        compose.onNodeWithText("75% left").assertIsDisplayed()
        compose.onNodeWithText("Refresh").assertIsDisplayed()
        save("dashboard-claude-codex-en.png")
    }
    @Test fun englishProviderDetailsRenderAndScroll() {
        displayFixtures()
        compose.onNodeWithTag("provider-list").performScrollToIndex(2)
        compose.onNodeWithText("Reported balance: 0").assertIsDisplayed()
        compose.onNodeWithText("Antigravity").assertIsDisplayed()
        save("dashboard-codex-antigravity-en.png")
    }
}
