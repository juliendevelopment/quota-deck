package be.julien.quotadeck.auth

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import be.julien.quotadeck.core.ProviderId
import be.julien.quotadeck.core.ProviderSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** No JavaScript bridge. Only the provider's session cookie is passed to the local encrypted store. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLogin(id: ProviderId, error: String?, onCookie: (String, String, ProviderSnapshot?) -> Unit, onCancel: () -> Unit) {
    val host = if (id == ProviderId.CLAUDE) "claude.ai" else "opencode.ai"
    val start = if (id == ProviderId.CLAUDE) "https://claude.ai/login" else "https://opencode.ai/auth"
    var currentHost by remember { mutableStateOf(host) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var reading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun cookie(): String = CookieManager.getInstance().getCookie("https://$host").orEmpty()
    BackHandler { if (webView?.canGoBack() == true) webView?.goBack() else onCancel() }
    DisposableEffect(Unit) {
        onDispose {
            webView?.apply { stopLoading(); clearCache(true); clearHistory(); destroy() }
            // WebView's cookie DB is never our credential store; remove it when the login view leaves.
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onCancel) { Text("Fermer") }
                TextButton(enabled = !reading, onClick = {
                    val view = webView ?: return@TextButton
                    reading = true
                    scope.launch {
                        try {
                            val snapshot = try { WebSessionReader.read(view, id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                            onCookie(cookie(), view.settings.userAgentString, snapshot)
                        } finally { reading = false }
                    }
                }) { Text(if (reading) "Lecture…" else "Terminer") }
            }
            Text("Connexion directe · $currentHost", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
            Text("Email recommandé. Si Google/GitHub bloque le navigateur intégré, utilisez la page officielle ; OpenCode propose aussi une connexion par clé.", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.safeBrowsingEnabled = true
                    settings.setGeolocationEnabled(false)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            val remote = uri.host.orEmpty()
                            val allowed = listOf(host, "anthropic.com", "google.com", "github.com", "githubusercontent.com").any { remote == it || remote.endsWith(".$it") }
                            return uri.scheme != "https" || !allowed
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            currentHost = Uri.parse(url).host.orEmpty()
                        }
                    }
                    loadUrl(start)
                }
            })
        }
    }
}
