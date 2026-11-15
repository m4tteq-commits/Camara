package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ro.camara.app.data.*
import java.time.LocalDate
import java.util.UUID

@Composable
internal fun StockMinimumScreen(
    snapshot: Snapshot,
    enabled: Boolean,
    onSave: (StockMinimum?, StockMinimum, () -> Unit) -> Unit,
    onDelete: (StockMinimum, () -> Unit) -> Unit,
    onReplenish: (ShoppingPreview) -> Unit,
    modifier: Modifier,
) {
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<StockMinimum?>(null) }
    var deleting by remember { mutableStateOf<StockMinimum?>(null) }
    var preview by remember { mutableStateOf<ShoppingPreview?>(null) }
    val today = LocalDate.now()
    val report = remember(snapshot.pantry, snapshot.shopping, snapshot.stock_minimums, snapshot.aliases, today) { StockMinimumRules.report(snapshot, today) }
    val needed = report.count { it.missing > 0 && !it.needsQuantityCheck }

    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                PageHeader("Stoc minim", "Ce vrei să ai mereu în casă.") {
                    TextButton(onClick = { editing = null; editor = true }, enabled = enabled && report.size < 200) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Text("Prag nou")
                    }
                }
            }
            if (!snapshot.stock_tools) item {
                Note("Pragurile se activează după sincronizarea cu serverul actualizat.")
            }
            if (report.isEmpty()) item {
                EmptyPanel(Icons.Outlined.Inventory2, "Alege alimentele de bază", "De exemplu, 6 ouă sau 1 litru de lapte. Verificăm și ce ai trecut deja pe lista de cumpărături.")
            }
            items(report, key = { it.minimum.id }) { row ->
                MinimumCard(row, enabled, { editing = row.minimum; editor = true }, { deleting = row.minimum })
            }
        }
        if (needed > 0) Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(countLabel(needed, "aliment de completat", "alimente de completat"), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { preview = StockMinimumRules.preview(snapshot) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("Pregătește reaprovizionarea")
                }
            }
        }
    }
    if (editor) MinimumDialog(editing, snapshot, enabled, { editor = false }) { minimum -> onSave(editing, minimum) { editor = false } }
    deleting?.let { minimum ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Elimini pragul?") },
            text = { Text("Ștergi pragul de stoc minim pentru ${minimum.name}.") },
            confirmButton = { TextButton(onClick = { onDelete(minimum) { deleting = null } }, enabled = enabled) { Text("Elimină") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Anulează") } })
    }
    preview?.let { value ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text("Reaprovizionare") }, text = {
            Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cantitățile de mai jos completează stocul după ce scădem ce ai deja pe listă.")
                value.lines.forEach { Text(it, style = MaterialTheme.typography.titleSmall) }
                value.warnings.forEach { Note(it, warning = true) }
            }
        }, confirmButton = {
            TextButton(onClick = {
                preview = null
                onReplenish(value)
            }, enabled = enabled && value.changes.isNotEmpty()) { Text("Adaugă pe listă") }
        }, dismissButton = { TextButton(onClick = { preview = null }) { Text("Anulează") } })
    }
}

@Composable
private fun MinimumCard(row: Replenishment, enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val unit = row.minimum.unit
    fun amount(value: Double) = "${StockMinimumRules.amountLabel(value)} $unit"
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconTile(Icons.Outlined.Inventory2, Modifier.size(38.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.minimum.name, style = MaterialTheme.typography.titleMedium)
                    Text("Prag ${amount(row.minimum.quantity)} · disponibil ${amount(row.available)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onEdit, enabled = enabled) { Icon(Icons.Outlined.Edit, "Editează pragul pentru ${row.minimum.name}") }
                IconButton(onClick = onDelete, enabled = enabled) { Icon(Icons.Outlined.DeleteOutline, "Elimină pragul pentru ${row.minimum.name}") }
            }
            if (row.planned > 0) Text("Pe listă · ${amount(row.planned)}", style = MaterialTheme.typography.bodySmall)
            when {
                row.needsQuantityCheck -> SmallPill("Verifică unitatea sau cantitatea de pe listă", Icons.Outlined.Info, MaterialTheme.colorScheme.secondary)
                row.missing > 0 -> SmallPill("Mai trebuie ${amount(row.missing)}", Icons.Outlined.ShoppingBasket, MaterialTheme.colorScheme.secondary)
                row.available + .00005 < row.minimum.quantity -> SmallPill("Acoperit de lista de cumpărături", Icons.Outlined.Check)
                else -> SmallPill("Stoc suficient", Icons.Outlined.Check)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MinimumDialog(editing: StockMinimum?, snapshot: Snapshot, enabled: Boolean, onDismiss: () -> Unit, onSave: (StockMinimum) -> Unit) {
    var name by remember(editing) { mutableStateOf(editing?.name.orEmpty()) }
    var quantity by remember(editing) { mutableStateOf(editing?.quantity?.let(StockMinimumRules::amountLabel).orEmpty()) }
    var unit by remember(editing) { mutableStateOf(editing?.unit ?: "buc") }
    var error by remember { mutableStateOf<String?>(null) }
    val id = remember(editing) { editing?.id ?: UUID.randomUUID().toString() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (editing == null) "Prag de stoc nou" else "Editează pragul") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Aliment") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(quantity, { quantity = it.take(12) }, label = { Text("Cantitate minimă") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StockMinimumRules.units.forEach { value -> FilterChip(unit == value, { unit = value }, label = { Text(value) }) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val raw = quantity.trim().replace(',', '.').toBigDecimalOrNull()?.stripTrailingZeros()
            val amount = raw?.toDouble()
            val key = RecipeMath.canonical(name, snapshot.aliases)
            error = when {
                name.isBlank() || raw == null || amount == null || amount !in 0.0001..100000.0 || raw.scale() > 4 -> "Introdu un aliment și o cantitate pozitivă cu cel mult patru zecimale."
                snapshot.stock_minimums.any { it.id != id && RecipeMath.canonical(it.name, snapshot.aliases) == key } -> "Alimentul are deja un prag. Îl poți edita din listă."
                else -> null
            }
            if (error == null && amount != null) onSave(StockMinimum(id, name.trim(), amount, unit))
        }, enabled = enabled) { Text("Salvează pragul") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } })
}
