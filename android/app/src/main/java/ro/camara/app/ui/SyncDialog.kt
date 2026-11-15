package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import ro.camara.app.data.*

private fun summary(value: JsonObject?): String {
    if (value == null) return "Eliminat / inexistent"
    fun text(key: String) = value[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    return when {
        "name" in value -> listOf(text("name"), text("quantity"), text("unit"), if (text("checked") == "true") "bifat" else "").filter { it.isNotBlank() }.joinToString(" · ")
        "label" in value -> "${text("label")} · ${text("amount_cents").toLongOrNull()?.let(::money) ?: ""}"
        "recipe" in value -> "${value["recipe"]?.jsonObject?.get("title")?.jsonPrimitive?.content} · ${text("on_date")} · ${text("servings")} porții"
        "limit_cents" in value -> "${text("month")} · ${text("limit_cents").toLongOrNull()?.let(::money)}"
        else -> text("title")
    }
}

@Composable
fun SyncDialog(pending: List<PendingAction>, server: Snapshot, enabled: Boolean, onDismiss: () -> Unit, onSync: () -> Unit, onResolve: (String, Boolean) -> Unit, onDiscardStale: () -> Unit,
    status: String? = null) {
    val conflict = pending.firstOrNull()?.takeIf { it.issue != null }
    val hasStale = pending.any { staleEpoch(it, server) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Sincronizare") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            status?.let { SmallPill(it) }
            if (pending.isEmpty()) Text("Toate modificările salvate au ajuns la server.")
            else {
                Text("${pending.size} acțiuni păstrate pe acest dispozitiv. Sincronizarea se reia automat când serverul este accesibil.")
                pending.forEach { Text("• ${it.mutation.label}${if (it.issue != null || staleEpoch(it, server)) " · necesită verificare" else " · în așteptare"}", style = MaterialTheme.typography.bodySmall) }
            }
            if (hasStale) {
                Text("Serverul a fost restaurat. Editările vechi rămân în jurnal până alegi să renunți la ele.")
                OutlinedButton(onClick = onDiscardStale, enabled = enabled) { Text("Renunță la schimbările vechi") }
            }
            conflict?.let { action ->
                HorizontalDivider()
                Text(action.issue.orEmpty(), color = MaterialTheme.colorScheme.error)
                action.mutation.changes.filter { it.before != it.after }.forEach { change ->
                    Text("Pe server: ${summary(ActionRules.find(server, change.kind, change.id))}", style = MaterialTheme.typography.bodySmall)
                    Text("Modificarea ta: ${summary(change.after)}", style = MaterialTheme.typography.bodySmall)
                }
                if (!staleEpoch(action, server) && action.rebasable) {
                    Text("Păstrarea modificării tale va înlocui valorile curente de pe server pentru această acțiune.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onResolve(action.id, true) }, enabled = enabled) { Text("Păstrează modificarea mea") }
                } else if (!staleEpoch(action, server)) Text("Reia operațiunea după renunțare, folosind datele actualizate.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onResolve(action.id, false) }, enabled = enabled) { Text("Renunță la această acțiune") }
            }
        }
    }, confirmButton = { TextButton(onClick = onSync, enabled = enabled) { Text("Sincronizează acum") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Închide") } })
}
