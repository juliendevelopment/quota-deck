package be.julien.quotadeck

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.julien.quotadeck.auth.WebLogin
import be.julien.quotadeck.core.*
import be.julien.quotadeck.ui.*

class MainActivity : ComponentActivity() {
    private val model: DashboardViewModel by viewModels()
    override fun onResume() { super.onResume(); model.onUiResumed() }
    override fun onPause() { model.setUiActive(false); super.onPause() }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Return-to-app navigation only. Never read or trust OAuth data from this intent.
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val states by model.states.collectAsStateWithLifecycle()
            val login by model.login.collectAsStateWithLifecycle()
            var about by remember { mutableStateOf(false) }
            // Error screens for OAuth contain no secrets and must remain capturable for support.
            val protect = login != null && (login?.error == null || login?.stage == "web" || login?.stage == "device" || login?.provider == ProviderId.OPENCODE)
            DisposableEffect(protect) {
                if (protect) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                onDispose { }
            }
            QuotaDeckTheme {
                if (login?.stage == "web") WebLogin(login!!.provider, login!!.error, model::browserCredential, model::cancelLogin,
                    onExternalLogin = { model.beginConnection(ProviderId.CLAUDE); model.startConnection(::openUrl) })
                else if (login?.provider == ProviderId.OPENCODE && login?.stage != "working") {
                    OpenCodeConnectionScreen(login?.error, { openUrl("https://opencode.ai/console") }, model::importApiKey, model::cancelLogin)
                }
                else Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    DashboardScreen(states, states.any { it.loading }, model::refreshAll, model::beginConnection, model::disconnect,
                        { openUrl(it.homeUrl) }, { about = true },
                        { id -> states.firstOrNull { it.id == id }?.diagnostic?.let { copyDiagnostic(this@MainActivity, it) } })
                }
                if (about) AlertDialog(onDismissRequest = { about = false }, confirmButton = { TextButton(onClick = { about = false }) { Text("Close") } },
                    title = { Text("QuotaDeck ${BuildConfig.VERSION_NAME}") }, text = { Text("Unofficial personal app. No AI generation requests, purchases or telemetry. Sessions and recent quota readings are encrypted with Android Keystore and excluded from backups. Quota interfaces may change. Disconnect removes local data; revoke permissions in the provider account settings. Quotas are only verified after a successful reading.") })
                login?.takeIf { it.stage != "web" && (it.provider != ProviderId.OPENCODE || it.stage == "working") }?.let { state -> LoginDialog(state, model::cancelLogin, { model.startConnection(::openUrl) }, ::openUrl, model::startEmailConnection) }
            }
        }
    }
    private fun openUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return
        runCatching { providerBrowserIntent(this, uri).launchUrl(this, uri) }
    }
}

internal fun providerBrowserIntent(context: Context, uri: Uri): CustomTabsIntent {
    val builder = CustomTabsIntent.Builder()
    if (uri.scheme == "https" && uri.host == "auth.openai.com" && uri.path?.trimEnd('/') == "/codex/device") {
        val navigation = android.content.Intent(context, MainActivity::class.java)
            .setAction(android.content.Intent.ACTION_VIEW)
            .setData(Uri.parse("quotadeck://oauth/return"))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val back = android.app.PendingIntent.getActivity(context, 0x434458, navigation,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        builder.addMenuItem("Return to QuotaDeck", back)
    }
    return builder.build()
}

private fun copyDiagnostic(context: Context, diagnostic: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("QuotaDeck diagnostic without secrets", diagnostic))
}

@Composable
private fun LoginDialog(state: LoginState, cancel: () -> Unit, start: () -> Unit, open: (String) -> Unit, emailLogin: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(onDismissRequest = cancel, title = { Text("Connect ${state.provider.title}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.error?.let {
                    SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("Stage: ${state.phase.label}", style = MaterialTheme.typography.bodySmall)
                    state.diagnostic?.let { diagnostic ->
                        TextButton(onClick = { copyDiagnostic(context, diagnostic) }) { Text("Copy diagnostic") }
                    }
                }
                when (state.stage) {
                    "device" -> {
                        Text("1. Copy the code.\n2. Open OpenAI and finish authorization until you see the success message.\n3. Close the tab with × or Back, or use ⋮ → Return to QuotaDeck. Verification resumes in the app; OpenAI does not automatically redirect back to it.")
                        SelectionContainer { Text(state.device?.code.orEmpty(), style = MaterialTheme.typography.headlineMedium) }
                        TextButton(onClick = {
                            val clip = ClipData.newPlainText("OpenAI code", state.device?.code)
                            if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                        }) { Text("Copy code") }
                        Text("The same code is kept during this attempt (up to 15 minutes). Do not start another sign-in after approving it. You may need to enable device-code sign-in in your ChatGPT security settings.", style = MaterialTheme.typography.bodySmall)
                        Text("Stage: ${state.phase.label}", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    "claude" -> {
                        Text("Finish signing in to Claude/Google in your system browser, then return here. The callback runs on this phone. Timeout: 5 minutes.")
                        Text("Public Claude Code client · requested access: profile and quotas only, with no generation or conversation history.", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    "google" -> { Text("Finish signing in to Google in your browser, then return here. Authorization uses the public Antigravity installed-app client, not a QuotaDeck-specific client."); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    "callback" -> { Text("Authorization received. Sign-in resumes as soon as QuotaDeck is in the foreground."); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    "working" -> { Text("${state.phase.label}…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    else -> {
                        Text(when (state.provider) {
                            ProviderId.CLAUDE -> "Claude opens in your system browser, which supports Google sign-in and two-factor authentication. The local callback uses PKCE. This public Claude Code client requests only the user:profile scope to read quotas; this integration is unofficial."
                            ProviderId.CODEX -> "Sign in to OpenAI in your browser using a device code. No password is entered in QuotaDeck."
                            ProviderId.ANTIGRAVITY -> "Google opens in your browser. The public Antigravity client requests Cloud Platform access and its usual technical scopes. QuotaDeck only reads quotas; it does not create projects or enable billing."
                            ProviderId.OPENCODE -> "1. Open OpenCode in your browser and sign in with Google.\n2. Copy your Go workspace API key from the console.\n3. Return here and paste it. You only need to do this once.\n\nUse your existing subscription; do not purchase a new one. The key is only used to read quotas, with no generation. Your Chrome session cannot be transferred automatically to this app."
                        })
                        if (state.provider == ProviderId.CLAUDE) {
                            TextButton(onClick = emailLogin) { Text("Alternative: embedded email sign-in") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state.stage) {
                "device" -> TextButton(onClick = { state.url?.let(open) }) { Text("Open OpenAI") }
                "google" -> TextButton(onClick = { state.url?.let(open) }) { Text("Reopen Google") }
                "claude" -> TextButton(onClick = { state.url?.let(open) }) { Text("Reopen Claude") }
                "working", "callback" -> Unit
                else -> TextButton(onClick = start) { Text("Continue with ${state.provider.title}") }
            }
        },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } },
    )
}
