package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.LocalDate
import java.util.UUID
import java.math.BigDecimal
import ro.camara.app.UiState
import ro.camara.app.data.*

@Composable
fun ReceiptsScreen(state: UiState, onCamera: () -> Unit, onGallery: () -> Unit, onOpen: (ReceiptRecord) -> Unit, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Bonurile tale", style = MaterialTheme.typography.headlineMedium)
            Text("Fotografia este citită pe ZimaOS. Verifici și corectezi produsele și totalul înainte de salvare.")
            Button(onClick = onCamera, enabled = state.online && !state.busy) { Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text("Fotografiază bonul") }
            OutlinedButton(onClick = onGallery, enabled = state.online && !state.busy) { Text("Alege o fotografie") }
            if (!state.online) Text("Conectează-te la server pentru citirea unei fotografii.", style = MaterialTheme.typography.bodySmall)
            if (state.receiptReading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Se citește bonul local pe server…") }
            if (state.snapshot.receipts.isEmpty()) Text("Bonurile confirmate vor apărea aici. Fotografiile nu sunt arhivate pe server.", style = MaterialTheme.typography.bodySmall)
        }
        items(state.snapshot.receipts.sortedByDescending { it.imported_at }, key = { it.id }) { receipt ->
            OutlinedCard(onClick = { onOpen(receipt) }) { Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text(receipt.merchant, style = MaterialTheme.typography.titleLarge)
                Text("${receipt.spent_on} · ${money(receipt.total_cents.toLong())}")
                Text("${receipt.lines.size} poziții verificate", style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}

private data class ReceiptRow(val id: String = UUID.randomUUID().toString(), val name: String, val quantity: String, val unit: String,
    val location: String, val price: String, val include: Boolean, val expiry: String = "", val warnings: List<String>)

@Composable
fun ReceiptPreviewDialog(preview: ReceiptPreview, enabled: Boolean, onDismiss: () -> Unit,
    onSave: (String, String, Int, List<ReceiptLine>, List<PantryItem>) -> Unit) {
    var merchant by remember { mutableStateOf(preview.merchant) }
    var date by remember { mutableStateOf(preview.spent_on ?: LocalDate.now().toString()) }
    var total by remember { mutableStateOf(preview.total_cents?.let { BigDecimal.valueOf(it.toLong(), 2).toPlainString() } ?: "") }
    var raw by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(preview.lines.map { ReceiptRow(name = it.name, quantity = RecipeMath.number(it.quantity), unit = it.unit,
        location = it.location, price = it.line_cents?.let { cents -> BigDecimal.valueOf(cents.toLong(), 2).toPlainString() } ?: "", include = it.include_pantry, warnings = it.warnings) }) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { if (enabled) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Verifică bonul", style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(merchant, { merchant = it.take(100) }, label = { Text("Magazin / descriere cheltuială") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(date, { date = it.take(10) }, label = { Text("Data · AAAA-LL-ZZ") })
                            OutlinedTextField(total, { total = it.take(14) }, label = { Text("Totalul bonului · lei") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            preview.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                            Text("Cheltuiala folosește totalul de mai sus. Bifează numai produsele care trebuie adăugate în cămară.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { raw = !raw }) { Text(if (raw) "Ascunde textul citit" else "Arată textul citit") }
                            if (raw) Text(preview.raw_text, style = MaterialTheme.typography.bodySmall)
                            if (rows.isEmpty()) Text("Nu au fost identificate poziții. Poți salva cheltuiala și adăuga alimentele manual.")
                        }
                    }
                    itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                        fun change(next: ReceiptRow) { rows = rows.mapIndexed { i, old -> if (index == i) next else old } }
                        OutlinedCard { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(row.include, { change(row.copy(include = it)) }, enabled = enabled); Text("Adaugă în cămară") }
                            OutlinedTextField(row.name, { change(row.copy(name = it.take(100))) }, label = { Text("Produs") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(row.price, { change(row.copy(price = it.take(14))) }, label = { Text("Prețul poziției · lei, opțional") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            if (row.include) {
                                OutlinedTextField(row.quantity, { change(row.copy(quantity = it.take(12))) }, label = { Text("Cantitatea totală de adăugat") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                ValueChoice(row.unit, units, enabled) { change(row.copy(unit = it)) }
                                ValueChoice(row.location, locations, enabled) { change(row.copy(location = it)) }
                                OutlinedTextField(row.expiry, { change(row.copy(expiry = it.take(10))) }, label = { Text("Termen opțional · AAAA-LL-ZZ") })
                            }
                            row.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                        } }
                    }
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = enabled) { Text("Anulează") }
                    Button(onClick = {
                        runCatching {
                            val cents = parseMoney(total)
                            require(merchant.isNotBlank() && cents != null && cents > 0) { "Verifică magazinul și totalul pozitiv al bonului." }
                            LocalDate.parse(date)
                            val pantry = mutableListOf<PantryItem>()
                            val lines = rows.map { row ->
                                val amount = row.quantity.replace(',', '.').toDoubleOrNull()
                                val price = if (row.price.isBlank()) null else parseMoney(row.price)
                                require(row.name.isNotBlank() && amount != null && amount.isFinite() && amount > 0 && amount <= 100000 && (row.price.isBlank() || price != null)) { "Verifică numele, cantitatea și prețul fiecărui produs." }
                                if (row.include) {
                                    if (row.expiry.isNotBlank()) LocalDate.parse(row.expiry)
                                    pantry += PantryItem(row.id, row.name.trim(), amount, row.unit, row.location, row.expiry.ifBlank { null })
                                }
                                ReceiptLine(row.name.trim(), amount, row.unit, row.location, price, row.include)
                            }
                            onSave(merchant.trim(), date, cents, lines, pantry)
                        }.onFailure { error = it.message ?: "Verifică datele bonului." }
                    }, enabled = enabled) { Text("Confirmă bonul") }
                }
            }
        }
    }
}

@Composable
fun ReceiptDetailsDialog(receipt: ReceiptRecord, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(receipt.merchant) }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("${receipt.spent_on} · ${money(receipt.total_cents.toLong())}"); Text("Total confirmat la import", style = MaterialTheme.typography.bodySmall) }
            items(receipt.lines) { Text("${it.name} · ${RecipeMath.number(it.quantity)} ${it.unit}${it.line_cents?.let { cents -> " · ${money(cents.toLong())}" } ?: ""}") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Închide") } })
}
