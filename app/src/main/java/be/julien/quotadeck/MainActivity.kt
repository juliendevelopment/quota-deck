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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val states by model.states.collectAsStateWithLifecycle()
            val login by model.login.collectAsStateWithLifecycle()
            var about by remember { mutableStateOf(false) }
            // Block screenshots only during login, not on the harmless dashboard.
            DisposableEffect(login != null) {
                if (login != null) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
                        { openUrl(it.homeUrl) }, { about = true })
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
        runCatching { CustomTabsIntent.Builder().build().launchUrl(this, uri) }
    }
}

@Composable
private fun LoginDialog(state: LoginState, cancel: () -> Unit, start: () -> Unit, open: (String) -> Unit, emailLogin: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(onDismissRequest = cancel, title = { Text("Connecter ${state.provider.title}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when (state.stage) {
                    "device" -> {
                        Text("1. Copiez le code.\n2. Ouvrez OpenAI, connectez-vous et autorisez le code.\n3. Revenez ici : la connexion se termine automatiquement.")
                        SelectionContainer { Text(state.device?.code.orEmpty(), style = MaterialTheme.typography.headlineMedium) }
                        TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Code OpenAI", state.device?.code)) }) { Text("Copier le code") }
                        Text("N’utilisez que le code que vous venez de demander ici. Dans ChatGPT, la connexion par code d’appareil peut nécessiter une activation dans les réglages de sécurité.", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    "claude" -> {
                        Text("Terminez la connexion Claude/Google dans le navigateur système, puis revenez ici. Le retour se fait sur ce téléphone. Délai maximum : 5 minutes.")
                        Text("Client public Claude Code · accès demandé : profil et quotas uniquement, sans génération ni historique des conversations.", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    "google" -> { Text("Terminez la connexion Google dans le navigateur, puis revenez ici. L’autorisation utilise le client public installé d’Antigravity ; elle n’est pas propre à QuotaDeck."); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    "working" -> { Text("Connexion en cours…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
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
                "working" -> Unit
                else -> TextButton(onClick = start) { Text("Continuer chez ${state.provider.title}") }
            }
        },
        dismissButton = { TextButton(onClick = cancel) { Text("Annuler") } },
    )
}
