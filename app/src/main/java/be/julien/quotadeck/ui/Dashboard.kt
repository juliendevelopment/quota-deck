package be.julien.quotadeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.julien.quotadeck.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val Mint = Color(0xFF9EDBC3)
private val Background = Color(0xFF10131B)
private val Surface = Color(0xFF1A1E29)
private val Muted = Color(0xFFAAB1C2)
fun providerColor(id: ProviderId) = when (id) {
    ProviderId.CLAUDE -> Color(0xFFDEAA8B)
    ProviderId.CODEX -> Mint
    ProviderId.ANTIGRAVITY -> Color(0xFFA9C7FF)
    ProviderId.OPENCODE -> Color(0xFFD4B6F8)
}

@Composable
fun QuotaDeckTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Mint, background = Background, surface = Surface,
        onBackground = Color(0xFFF1F3F8), onSurface = Color(0xFFF1F3F8),
        surfaceVariant = Color(0xFF252B39), onSurfaceVariant = Muted,
    ), content = content)
}

@Composable
fun DashboardScreen(
    states: List<ProviderState>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onConnect: (ProviderId) -> Unit,
    onDisconnect: (ProviderId) -> Unit,
    onOpenUsage: (ProviderId) -> Unit,
    onAbout: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("provider-list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("QUOTADECK", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Le plein d’IA.", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onAbout) { Icon(Icons.Outlined.Info, "À propos et confidentialité", tint = Muted) }
                }
                Text("Vos quotas, au même endroit.", color = Muted, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val count = states.count { it.connected }
                    Text("$count / 4 comptes connectés", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRefresh, enabled = !refreshing && count > 0) {
                        if (refreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (refreshing) "Actualisation" else "Actualiser")
                    }
                }
            }
            items(states, key = { it.id.name }) { state ->
                ProviderCard(state, { onConnect(state.id) }, { onDisconnect(state.id) }, { onOpenUsage(state.id) })
            }
            item {
                Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp), tint = Muted)
                    Text("Comptes chiffrés sur ce téléphone. Aucun serveur QuotaDeck. Les connexions passent directement par les fournisseurs.", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(state: ProviderState, onConnect: () -> Unit, onDisconnect: () -> Unit, onOpenUsage: () -> Unit) {
    val color = providerColor(state.id)
    var menu by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(color.copy(alpha = 0.13f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Text(when(state.id) { ProviderId.CLAUDE -> "C"; ProviderId.CODEX -> ">_"; ProviderId.ANTIGRAVITY -> "A"; ProviderId.OPENCODE -> "O" }, color = color, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.id.title, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                    Text(state.snapshot?.plan ?: state.id.subtitle, color = Muted, fontSize = 12.sp)
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.MoreVert, "Options ${state.id.title}", tint = Muted) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Page officielle") }, onClick = { menu = false; onOpenUsage() })
                        if (state.connected) DropdownMenuItem(text = { Text("Déconnecter") }, onClick = { menu = false; onDisconnect() })
                    }
                }
            }
            state.snapshot?.identity?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, fontSize = 12.sp) }
            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = color)
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                if (state.snapshot != null) Text("Dernier relevé conservé — données non actualisées", color = Color(0xFFFFCE82), fontSize = 12.sp)
            }
            val snapshot = state.snapshot
            if (snapshot != null) {
                snapshot.windows.forEach { QuotaRow(it, color) }
                snapshot.notice?.let { Text(it, color = Muted, fontSize = 12.sp) }
                val stamp = remember(snapshot.fetchedAt) {
                    DateTimeFormatter.ofPattern("dd/MM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(snapshot.fetchedAt))
                }
                Text("Relevé le $stamp", color = Muted, fontSize = 11.sp)
            }
            if (!state.connected) {
                Text("Connectez votre abonnement pour consulter les limites disponibles.", color = Muted, fontSize = 13.sp)
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Background)) {
                    Icon(Icons.Outlined.Login, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Connecter ${state.id.title}", fontWeight = FontWeight.SemiBold)
                }
            } else if (state.error != null) {
                OutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Reconnecter ${state.id.title}") }
            } else if (snapshot == null && !state.loading) {
                Text("Connecté · actualisez pour lire les quotas.", color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun QuotaRow(window: QuotaWindow, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(window.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(window.remainingPercent?.let { "${it.roundToInt()} % restant" } ?: "Non communiqué", color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        window.remainingPercent?.let { percent ->
            LinearProgressIndicator(progress = { (percent / 100.0).toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp), color = if (percent <= 10) Color(0xFFFF9E9E) else color, trackColor = Color(0xFF303747))
        }
        resetLabel(window.resetAt)?.let { Text(it, fontSize = 11.sp, color = Muted) }
        window.detail?.let { Text(it, fontSize = 11.sp, color = Muted) }
    }
}
