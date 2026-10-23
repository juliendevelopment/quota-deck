package be.julien.quotadeck

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import be.julien.quotadeck.core.ProviderId
import be.julien.quotadeck.data.ProviderException
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthErrorScreenshotRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun browserReturnRouteReusesExistingTask() {
        val context = compose.activity
        val info = context.packageManager.getActivityInfo(android.content.ComponentName(context, MainActivity::class.java), 0)
        assertEquals(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        val route = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("quotadeck://oauth/return"))
            .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
        val resolved = context.packageManager.resolveActivity(route, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(MainActivity::class.java.name, resolved?.activityInfo?.name)
    }
    @Test fun nonSensitiveOAuthErrorCanBeScreenshotted() {
        lateinit var model: DashboardViewModel
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[DashboardViewModel::class.java]
            model.beginConnection(ProviderId.CLAUDE)
            model.startConnection { throw ProviderException("Diagnostic réseau de test") }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Diagnostic réseau de test").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Diagnostic réseau de test").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("Non-sensitive error screens must not be blacked out", 0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            assertNotNull("The visible OAuth dialog must also be checked", dialog)
            assertEquals("The dialog must not keep its inherited secure flag", 0, dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        }
        compose.onNodeWithText("Copier le diagnostic").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val report = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertTrue(report.contains("Fournisseur : CLAUDE"))
            assertTrue(report.contains("Etape : BROWSER"))
            assertFalse(report.contains("Diagnostic réseau de test"))
        }
    }
}
