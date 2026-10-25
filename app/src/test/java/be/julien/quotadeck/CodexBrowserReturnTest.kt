package be.julien.quotadeck

import android.app.Application
import android.app.PendingIntent
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodexBrowserReturnTest {
    @Test fun codexBrowserHasAnImmutableNavigationOnlyReturnAction() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = providerBrowserIntent(context, Uri.parse("https://auth.openai.com/codex/device")).intent
        val menu = intent.getParcelableArrayListExtra<Bundle>(CustomTabsIntent.EXTRA_MENU_ITEMS)!!
        assertEquals(1, menu.size)
        assertEquals("Revenir dans QuotaDeck", menu.first().getString(CustomTabsIntent.KEY_MENU_ITEM_TITLE))
        val back = menu.first().getParcelable<PendingIntent>(CustomTabsIntent.KEY_PENDING_INTENT)!!
        assertTrue(back.isImmutable)
        assertEquals(context.packageName, back.creatorPackage)
        val navigation = shadowOf(back).savedIntent
        assertEquals("quotadeck://oauth/return", navigation.dataString)
        assertEquals(MainActivity::class.java.name, navigation.component!!.className)
        assertNull(navigation.data!!.query)
        assertTrue(navigation.extras == null || navigation.extras!!.isEmpty)
    }
    @Test fun otherProviderBrowserIntentsAreUnchanged() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        for (url in listOf("https://opencode.ai/console", "https://claude.com/cai/oauth/authorize", "https://accounts.google.com/o/oauth2/v2/auth")) {
            val intent = providerBrowserIntent(context, Uri.parse(url)).intent
            assertFalse(intent.hasExtra(CustomTabsIntent.EXTRA_MENU_ITEMS))
        }
    }
}
