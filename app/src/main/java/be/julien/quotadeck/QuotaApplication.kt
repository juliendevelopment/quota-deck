package be.julien.quotadeck

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebStorage

class QuotaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Also clear an unfinished WebView login left behind by a killed process.
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
    }
}
