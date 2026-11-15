package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.LocalDate
import java.util.UUID
import ro.camara.app.data.*

private data class EditableBulkRow(val id: String, val name: String, val quantity: String, val unit: String,
    val location: String, val expiry: String, val warnings: List<String>)

@Composable
fun BulkAddDialog(busy: Boolean, serverMessage: String?, onDismiss: () -> Unit,
    onPreview: (String, (BulkPreview) -> Unit) -> Unit, onSave: (List<PantryItem>) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<EditableBulkRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (rows == null) "Adaugă dintr-un text" else "Verifică înainte de salvare", style = MaterialTheme.typography.titleLarge)
                if (rows == null) {
                    Text("Scrie lista cu cantități. Poți include un termen în format AAAA-LL-ZZ.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(text, { text = it.take(3000) }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 7,
                        placeholder = { Text("6 ouă, 1 litru de lapte, 500 g de paste\nIaurt expiră 2026-11-05") }, enabled = !busy)
                } else {
                    Text("${rows!!.size} alimente. Cantitățile neclare sunt marcate pentru verificare.", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(rows!!, key = { _, row -> row.id }) { index, row ->
                            fun change(next: EditableBulkRow) { rows = rows!!.mapIndexed { i, value -> if (i == index) next else value } }
                            OutlinedCard {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row {
                                        OutlinedTextField(row.name, { change(row.copy(name = it.take(100))) }, label = { Text("Aliment") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !busy)
                                        IconButton(onClick = { rows = rows!!.filter { it.id != row.id } }, enabled = !busy) { Icon(Icons.Outlined.Close, "Elimină din import") }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(row.quantity, { change(row.copy(quantity = it.take(12))) }, label = { Text("Cantitate") }, singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f), enabled = !busy)
                                        ChoiceMenu(row.unit, listOf("buc", "g", "kg", "ml", "l").map { it to it }, !busy) { change(row.copy(unit = it)) }
                                    }
                                    ChoiceMenu(when (row.location) { "camara" -> "Cămară"; "congelator" -> "Congelator"; else -> "Frigider" },
                                        listOf("frigider" to "Frigider", "camara" to "Cămară", "congelator" to "Congelator"), !busy) { change(row.copy(location = it)) }
                                    OutlinedTextField(row.expiry, { change(row.copy(expiry = it.take(10))) }, label = { Text("Termen opțional · AAAA-LL-ZZ") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                                    row.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                                }
                            }
                        }
                    }
                }
                (error ?: serverMessage)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { if (rows == null) onDismiss() else { rows = null; error = null } }, enabled = !busy) { Text(if (rows == null) "Anulează" else "Înapoi") }
                    Button(onClick = {
                        error = null
                        if (rows == null) onPreview(text) { result ->
                            rows = result.rows.map { row -> EditableBulkRow(UUID.randomUUID().toString(), row.item.name, row.item.quantity.toString(), row.item.unit, row.item.location, row.item.expires_on.orEmpty(), row.warnings) }
                        } else runCatching {
                            val items = rows!!.map { row ->
                                val amount = row.quantity.replace(',', '.').toDoubleOrNull()
                                require(row.name.isNotBlank() && amount != null && amount.isFinite() && amount > 0 && amount <= 100000) { "Verifică denumirea și cantitatea pentru ${row.name}." }
                                if (row.expiry.isNotBlank()) LocalDate.parse(row.expiry)
                                PantryItem(row.id, row.name.trim(), amount, row.unit, row.location, row.expiry.ifBlank { null })
                            }
                            onSave(items)
                        }.onFailure { error = "Verifică numele, cantitățile și datele. Termenul trebuie scris AAAA-LL-ZZ." }
                    }, enabled = !busy && if (rows == null) text.isNotBlank() else rows!!.isNotEmpty()) { Text(if (rows == null) "Previzualizează" else "Salvează toate") }
                }
            }
        }
    }
}

@Composable
private fun ChoiceMenu(label: String, options: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text(label) }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { (value, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(value); expanded = false }) } }
    }
}
