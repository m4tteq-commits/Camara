package ro.camara.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ro.camara.app.data.PurchaseEntry
import ro.camara.app.data.RecipeMath

@Composable
internal fun PurchaseHistoryDialog(history: List<PurchaseEntry>, canWrite: Boolean, onDismiss: () -> Unit,
    onRepeat: (String, Set<Int>, () -> Unit) -> Unit) {
    var entryKey by rememberSaveable { mutableStateOf<String?>(null) }
    var chosen by rememberSaveable(entryKey) { mutableStateOf(emptyList<Int>()) }
    val entry = history.firstOrNull { it.key == entryKey }
    val back = { if (entry == null) onDismiss() else entryKey = null }
    AlertDialog(
        onDismissRequest = back,
        title = { Text(entry?.merchant ?: "Cumpăr din nou") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (entry == null) "Alege o cumpărătură sau un bon din istoric." else "${entry.date} · Bifează produsele pentru listă.")
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (entry == null) items(history, key = { it.key }) { purchase ->
                        Row(Modifier.fillMaxWidth().clickable { entryKey = purchase.key }.padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(purchase.merchant, style = MaterialTheme.typography.titleSmall)
                                Text(purchase.date, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(money(purchase.totalCents.toLong()), style = MaterialTheme.typography.labelLarge)
                            Icon(Icons.Outlined.ChevronRight, null)
                        }
                        HorizontalDivider()
                    } else itemsIndexed(entry.items) { index, item ->
                        val checked = index in chosen
                        Row(Modifier.fillMaxWidth().toggleable(checked, enabled = canWrite, role = Role.Checkbox) {
                            chosen = if (it) chosen + index else chosen - index
                        }.heightIn(min = 48.dp).padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, onCheckedChange = null, enabled = canWrite)
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.titleSmall)
                                Text("${RecipeMath.number(item.quantity)} ${item.unit}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (entry != null) Text("Produsele deja trecute pe listă își păstrează cantitatea.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (entry != null) TextButton(onClick = { onRepeat(entry.key, chosen.toSet(), onDismiss) }, enabled = canWrite && chosen.isNotEmpty()) {
                Text("Adaugă pe listă")
            }
        },
        dismissButton = { TextButton(onClick = back) { Text(if (entry == null) "Închide" else "Înapoi la istoric") } },
    )
}
