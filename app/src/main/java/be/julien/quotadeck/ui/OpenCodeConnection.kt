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
            Text("Connect OpenCode Go", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("1. Open the console and sign in with Google.\n2. Copy your Go workspace API key.\n3. Return here and paste it — you only need to do this once.")
            OutlinedButton(onClick = onOpenBrowser, modifier = Modifier.fillMaxWidth()) { Text("Open OpenCode with Google") }
            Text("Use your existing subscription. Do not purchase a new one. Chrome does not share its cookies with this app.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("OpenCode Go API key") },
                modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
            Button(onClick = { val value = key.trim(); key = ""; onSubmit(value) }, enabled = key.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Connect key") }
            Text("Your key is encrypted on this phone. It is only used to read quotas; no model is called. Never share it in chat.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
