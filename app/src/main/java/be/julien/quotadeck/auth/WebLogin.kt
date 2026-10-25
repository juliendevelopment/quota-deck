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
fun WebLogin(id: ProviderId, error: String?, onCookie: (String, String, ProviderSnapshot?) -> Unit, onCancel: () -> Unit, onExternalLogin: () -> Unit = {}) {
    val host = if (id == ProviderId.CLAUDE) "claude.ai" else "opencode.ai"
    val start = if (id == ProviderId.CLAUDE) "https://claude.ai/login" else "https://opencode.ai/auth"
    var currentHost by remember { mutableStateOf(host) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var reading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var navigation by remember { mutableIntStateOf(0) }
    LaunchedEffect(navigation, loading) {
        if (loading) {
            kotlinx.coroutines.delay(45_000)
            webView?.stopLoading()
            loading = false
            notice = "Loading stopped after 45 seconds. Use system-browser sign-in or try email sign-in again."
        }
    }
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
                TextButton(onClick = onCancel) { Text("Close") }
                TextButton(enabled = !reading, onClick = {
                    val view = webView ?: return@TextButton
                    reading = true
                    scope.launch {
                        try {
                            val snapshot = try { WebSessionReader.read(view, id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                            onCookie(cookie(), view.settings.userAgentString, snapshot)
                        } finally { reading = false }
                    }
                }) { Text(if (reading) "Reading…" else "Finish") }
            }
            Text("Direct sign-in · $currentHost", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
            Text("Email sign-in only. For Google, use system-browser sign-in.", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            notice?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                TextButton(onClick = onExternalLogin, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Use browser sign-in") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                            val remote = uri.host.orEmpty().lowercase()
                            if (remote == "accounts.google.com" || remote.endsWith(".accounts.google.com") || remote == "accounts.youtube.com") {
                                loading = false
                                notice = "Do not use Google sign-in in this embedded browser. Use browser sign-in instead."
                                view.stopLoading()
                                return true
                            }
                            if (!request.isForMainFrame) return false
                            val allowed = listOf(host, "claude.com", "anthropic.com", "github.com", "githubusercontent.com").any { remote == it || remote.endsWith(".$it") }
                            val blocked = uri.scheme != "https" || !allowed
                            if (blocked) {
                                loading = false
                                notice = "Redirect to $remote blocked. Use browser sign-in instead."
                            }
                            return blocked
                        }
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            currentHost = Uri.parse(url).host.orEmpty()
                            navigation += 1
                            loading = true
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            currentHost = Uri.parse(url).host.orEmpty()
                            loading = false
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                            if (request.isForMainFrame) {
                                loading = false
                                notice = "Sign-in page unavailable. Use browser sign-in or try again."
                            }
                        }
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame && response.statusCode >= 400) {
                                loading = false
                                notice = "The provider rejected the sign-in page (HTTP ${response.statusCode}). Use browser sign-in."
                            }
                        }
                    }
                    loadUrl(start)
                }
            })
        }
    }
}
