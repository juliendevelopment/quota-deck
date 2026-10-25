package be.julien.quotadeck

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import be.julien.quotadeck.auth.WebLogin
import be.julien.quotadeck.core.ProviderId
import be.julien.quotadeck.ui.QuotaDeckTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebLoginRegressionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun claudeDoesNotEmbedGoogleSso() = verify(ProviderId.CLAUDE)
    @Test fun openCodeDoesNotEmbedGoogleSso() = verify(ProviderId.OPENCODE)

    private fun verify(id: ProviderId) {
        var web: WebView? = null
        compose.setContent { QuotaDeckTheme { WebLogin(id, null, { _, _, _ -> }, {}) } }
        compose.runOnIdle {
            val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first()
            web = findWebView(resumed.window.decorView)
            assertNotNull(web)
            val request = object : WebResourceRequest {
                override fun getUrl() = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth?client_id=provider-client")
                override fun isForMainFrame() = true
                override fun isRedirect() = false
                override fun hasGesture() = true
                override fun getMethod() = "GET"
                override fun getRequestHeaders() = emptyMap<String, String>()
            }
            assertTrue("Google SSO must not continue inside an embedded WebView after 2FA", web!!.webViewClient.shouldOverrideUrlLoading(web!!, request))
        }
        compose.onNodeWithText("Use browser sign-in").assertIsDisplayed()
    }
    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        return null
    }
}
