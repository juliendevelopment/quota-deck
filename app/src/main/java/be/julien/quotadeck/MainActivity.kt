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
    override fun onResume() { super.onResume(); model.setUiActive(true) }
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
                if (about) AlertDialog(onDismissRequest = { about = false }, confirmButton = { TextButton(onClick = { about = false }) { Text("Fermer") } },
                    title = { Text("QuotaDeck ${BuildConfig.VERSION_NAME}") }, text = { Text("Application personnelle, non officielle. Aucune requête de génération IA, aucun achat, aucune télémétrie. Les sessions et derniers relevés sont chiffrés par Android Keystore, hors sauvegarde. Les interfaces de quotas peuvent changer. Déconnecter efface les données locales ; pour révoquer une autorisation, utilisez les réglages du fournisseur. Les quotas ne sont vérifiés qu’après un relevé réussi.") })
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
        builder.addMenuItem("Revenir dans QuotaDeck", back)
    }
    return builder.build()
}

private fun copyDiagnostic(context: Context, diagnostic: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("Diagnostic QuotaDeck sans secrets", diagnostic))
}

@Composable
private fun LoginDialog(state: LoginState, cancel: () -> Unit, start: () -> Unit, open: (String) -> Unit, emailLogin: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(onDismissRequest = cancel, title = { Text("Connecter ${state.provider.title}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.error?.let {
                    SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("Étape : ${state.phase.label}", style = MaterialTheme.typography.bodySmall)
                    state.diagnostic?.let { diagnostic ->
                        TextButton(onClick = { copyDiagnostic(context, diagnostic) }) { Text("Copier le diagnostic") }
                    }
                }
                when (state.stage) {
                    "device" -> {
                        Text("1. Copiez le code.\n2. Ouvrez OpenAI et terminez l’autorisation jusqu’au message de réussite.\n3. Fermez l’onglet avec × ou Retour, ou utilisez le menu ⋮ → Revenir dans QuotaDeck. La vérification reprend dans l’app ; OpenAI ne redirige pas automatiquement vers elle.")
                        SelectionContainer { Text(state.device?.code.orEmpty(), style = MaterialTheme.typography.headlineMedium) }
                        TextButton(onClick = {
                            val clip = ClipData.newPlainText("Code OpenAI", state.device?.code)
                            if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                        }) { Text("Copier le code") }
                        Text("Le même code est conservé pendant la tentative (15 minutes maximum). Ne recommencez pas une nouvelle connexion après l’avoir validé. Dans ChatGPT, la connexion par code d’appareil peut nécessiter une activation dans les réglages de sécurité.", style = MaterialTheme.typography.bodySmall)
                        Text("Étape : ${state.phase.label}", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    "claude" -> {
                        Text("Terminez la connexion Claude/Google dans le navigateur système, puis revenez ici. Le retour se fait sur ce téléphone. Délai maximum : 5 minutes.")
                        Text("Client public Claude Code · accès demandé : profil et quotas uniquement, sans génération ni historique des conversations.", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    "google" -> { Text("Terminez la connexion Google dans le navigateur, puis revenez ici. L’autorisation utilise le client public installé d’Antigravity ; elle n’est pas propre à QuotaDeck."); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    "callback" -> { Text("Autorisation reçue. La finalisation reprend dès que QuotaDeck est au premier plan."); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    "working" -> { Text("${state.phase.label}…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    else -> {
                        Text(when (state.provider) {
                            ProviderId.CLAUDE -> "Claude s’ouvre dans le navigateur système, qui prend en charge Google et sa double authentification. Le retour utilise une autorisation locale avec PKCE. Ce client public Claude Code demande uniquement le scope user:profile pour lire les quotas ; cette intégration reste non officielle."
                            ProviderId.CODEX -> "OpenAI vous connecte dans votre navigateur avec un code d’appareil. Aucun mot de passe n’est saisi dans QuotaDeck."
                            ProviderId.ANTIGRAVITY -> "Google s’ouvre dans votre navigateur. Le client public Antigravity demande les accès Cloud Platform et ses scopes techniques habituels. QuotaDeck n’effectue que des lectures de quotas ; il ne crée aucun projet et n’active aucune facturation."
                            ProviderId.OPENCODE -> "1. Ouvrez OpenCode dans le navigateur et connectez-vous avec Google.\n2. Copiez la clé API de votre espace Go dans la console.\n3. Revenez ici et collez-la. Une seule fois suffit.\n\nNe souscrivez rien de nouveau : utilisez votre abonnement existant. La clé sert uniquement à lire les quotas, sans génération. La session de Chrome ne peut pas être transférée automatiquement à cette app."
                        })
                        if (state.provider == ProviderId.CLAUDE) {
                            TextButton(onClick = emailLogin) { Text("Alternative : connexion email intégrée") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state.stage) {
                "device" -> TextButton(onClick = { state.url?.let(open) }) { Text("Ouvrir OpenAI") }
                "google" -> TextButton(onClick = { state.url?.let(open) }) { Text("Rouvrir Google") }
                "claude" -> TextButton(onClick = { state.url?.let(open) }) { Text("Rouvrir Claude") }
                "working", "callback" -> Unit
                else -> TextButton(onClick = start) { Text("Continuer chez ${state.provider.title}") }
            }
        },
        dismissButton = { TextButton(onClick = cancel) { Text("Annuler") } },
    )
}
