package ro.camara.app.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import ro.camara.app.UiState
import ro.camara.app.data.*

private val recordLabels = mapOf("pantry" to "alimente", "shopping" to "cumpărături", "recipes" to "recomandări", "plans" to "mese planificate",
    "expenses" to "cheltuieli", "events" to "consum și risipă", "budgets" to "bugete", "products" to "produse din catalog", "personal_recipes" to "rețete personale", "receipts" to "bonuri", "prepared" to "loturi gătite", "purchases" to "cumpărături confirmate")
internal fun backupTimestamp(value: String) = runCatching { OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Europe/Bucharest")).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) }.getOrDefault(value)
private fun backupKind(kind: String) = when (kind) { "auto" -> "Automat"; "pre_restore" -> "Înainte de restaurare"; else -> "Manual" }

@Composable
fun BackupsScreen(state: UiState, onRefresh: () -> Unit, onCreate: () -> Unit, onConfigure: (BackupSettings) -> Unit,
    onPreview: (BackupInfo) -> Unit, modifier: Modifier) {
    val overview = state.backupOverview
    var settings by remember(overview?.settings) { mutableStateOf(overview?.settings ?: BackupSettings()) }
    val context = LocalContext.current
    val enabled = state.online && !state.busy && !state.generating
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Backup și restaurare", style = MaterialTheme.typography.headlineMedium)
            Text("Copiile sunt păstrate pe ZimaOS. Se salvează numai datele deja sincronizate cu serverul.")
            TextButton(onClick = onRefresh, enabled = !state.busy) { Text("Actualizează copiile") }
            if (overview == null) Text(if (state.online) "Se încarcă lista copiilor…" else "Conectează-te la server pentru gestionarea copiilor.")
            overview?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (overview != null) item {
            OutlinedCard { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Backup zilnic", Modifier.weight(1f)); Switch(settings.enabled, { settings = settings.copy(enabled = it) }, enabled = enabled) }
                OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> settings = settings.copy(hour = h, minute = m) }, settings.hour, settings.minute, true).show() }, enabled = enabled && settings.enabled) {
                    Text("Ora ${"%02d:%02d".format(settings.hour, settings.minute)} · București")
                }
                Text("Copii automate păstrate")
                ValueChoice(settings.keep_daily.toString(), listOf(7, 14, 30, 60).map { it.toString() to "$it copii" }, enabled) { settings = settings.copy(keep_daily = it.toInt()) }
                Button(onClick = { onConfigure(settings) }, enabled = enabled) { Text("Salvează programarea") }
                Text("Serverul verifică programarea la fiecare minut. Dacă a fost oprit la ora aleasă, face copia la următoarea pornire din aceeași zi.", style = MaterialTheme.typography.bodySmall)
            } }
        }
        item {
            Button(onClick = onCreate, enabled = enabled && state.pending.isEmpty()) { Text("Creează o copie acum") }
            if (state.pending.isNotEmpty()) Text("Sincronizează cele ${state.pending.size} acțiuni locale înainte de backup sau restaurare.", style = MaterialTheme.typography.bodySmall)
        }
        items(overview?.backups.orEmpty(), key = { it.id }) { backup ->
            OutlinedCard { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(backupTimestamp(backup.created_at), style = MaterialTheme.typography.titleLarge)
                Text("${backupKind(backup.kind)} · ${backup.size_bytes / 1024} KB")
                Text(backup.counts.filter { it.value > 0 }.entries.joinToString(" · ") { "${it.value} ${recordLabels[it.key] ?: it.key}" }.ifBlank { "Copie fără înregistrări" }, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onPreview(backup) }, enabled = enabled && state.pending.isEmpty()) { Text("Verifică restaurarea") }
            } }
        }
    }
}

@Composable
fun RestoreDialog(preview: RestorePreview, enabled: Boolean, onDismiss: () -> Unit, onRestore: (String) -> Unit) {
    var confirmed by remember { mutableStateOf(false) }
    val requestId = remember { UUID.randomUUID().toString() }
    AlertDialog(onDismissRequest = { if (enabled) onDismiss() }, title = { Text("Restaurezi această copie?") }, text = {
        LazyColumn(Modifier.heightIn(max = 450.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Copia din ${backupTimestamp(preview.backup.created_at)} va înlocui datele comune de pe server.", color = MaterialTheme.colorScheme.error)
                Text("Înainte de înlocuire se creează automat o copie a datelor curente.")
            }
            recordLabels.forEach { (key, label) -> item { Text("$label: acum ${preview.current_counts[key] ?: 0} → copie ${preview.backup.counts[key] ?: 0}", style = MaterialTheme.typography.bodySmall) } }
            item {
                Text("Schimbările vechi rămase offline pe oricare dispozitiv vor cere verificare și refacere, pentru a proteja datele restaurate.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(confirmed, { confirmed = it }, enabled = enabled); Text("Confirm înlocuirea datelor cu această copie.", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }, confirmButton = { TextButton(onClick = { onRestore(requestId) }, enabled = enabled && confirmed) { Text("Restaurează datele") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = enabled) { Text("Anulează") } })
}
