package be.julien.quotadeck.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** A real form screen: avoid editable inputs inside AlertDialog's intrinsic measurement. */
@Composable
fun OpenCodeConnectionScreen(error: String?, onOpenBrowser: () -> Unit, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var key by remember { mutableStateOf("") }
    BackHandler(onBack = onCancel)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Connecter OpenCode Go", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("1. Ouvrez la console et connectez-vous avec Google.\n2. Copiez la clé API de votre espace Go.\n3. Revenez ici et collez-la — une seule fois suffit.")
            OutlinedButton(onClick = onOpenBrowser, modifier = Modifier.fillMaxWidth()) { Text("Ouvrir OpenCode avec Google") }
            Text("Utilisez votre abonnement existant. Ne souscrivez rien de nouveau. Chrome ne partage pas ses cookies avec cette app.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Clé API OpenCode Go") },
                modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
            Button(onClick = { val value = key.trim(); key = ""; onSubmit(value) }, enabled = key.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Connecter la clé") }
            Text("Clé chiffrée sur ce téléphone. Elle sert uniquement à lire les quotas ; aucun modèle n’est appelé. Ne la partagez pas dans Discord.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Annuler") }
        }
    }
}
