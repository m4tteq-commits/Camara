package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID
import ro.camara.app.data.*

private val romanian = Locale.forLanguageTag("ro-RO")
private fun slotName(slot: String) = when (slot) { "breakfast" -> "Mic dejun"; "lunch" -> "Prânz"; else -> "Cină" }

@Composable
fun PlanDialog(recipes: List<Recipe>, date: LocalDate, initialRecipe: Recipe?, editing: MealPlan?, enabled: Boolean,
               onDismiss: () -> Unit, prepared: List<PreparedBatch> = emptyList(), initialBatch: PreparedBatch? = null, onSave: (MealPlan) -> Unit) {
    var recipe by remember { mutableStateOf(editing?.recipe ?: initialBatch?.recipe ?: initialRecipe ?: recipes.firstOrNull()) }
    var batchId by remember { mutableStateOf(editing?.prepared_batch_id ?: initialBatch?.id) }
    var day by remember { mutableStateOf(editing?.on_date ?: date.toString()) }
    var slot by remember { mutableStateOf(editing?.slot ?: "dinner") }
    var portions by remember { mutableIntStateOf(editing?.servings ?: if (initialBatch != null) 1 else recipe?.servings ?: 1) }
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val id = remember { editing?.id ?: UUID.randomUUID().toString() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Planifică o masă") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text((if (batchId != null) "Deja gătit · " else "") + (recipe?.title ?: "Alege o rețetă")) }
                DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = 300.dp)) {
                    (listOfNotNull(editing?.recipe, initialRecipe) + recipes).distinctBy { it.id }.forEach { value ->
                        DropdownMenuItem(text = { Text(value.title) }, onClick = { recipe = value; batchId = null; portions = value.servings; expanded = false })
                    }
                    prepared.filter { it.remaining > 0 }.forEach { batch -> DropdownMenuItem(text = { Text("Deja gătit · ${batch.recipe.title} · ${batch.remaining} porții") }, onClick = { recipe = batch.recipe; batchId = batch.id; portions = 1; expanded = false }) }
                }
            }
            OutlinedTextField(day, { day = it.take(10) }, label = { Text("Data · AAAA-LL-ZZ") }, singleLine = true)
            listOf("breakfast", "lunch", "dinner").forEach { value -> FilterChip(slot == value, { slot = value }, label = { Text(slotName(value)) }) }
            PortionPicker(portions, enabled) { portions = it }
            batchId?.let { id -> prepared.firstOrNull { it.id == id }?.let { Text("Termen introdus: ${it.expires_on} · ${it.remaining} porții rămase", style = MaterialTheme.typography.bodySmall) } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        if (runCatching { LocalDate.parse(day) }.isFailure || recipe == null) error = "Alege rețeta și o dată în format AAAA-LL-ZZ."
        else onSave(MealPlan(id, day, slot, recipe!!, portions, prepared_batch_id = batchId))
    }, enabled = enabled && recipe != null) { Text("Salvează") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } })
}

@Composable
fun PortionPicker(value: Int, enabled: Boolean, outlined: Boolean = false, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Porții", Modifier.weight(1f))
        if (outlined) {
            OutlinedIconButton(onClick = { onChange(value - 1) }, enabled = enabled && value > 1, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Remove, "Scade porțiile")
            }
            Text("$value", Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.titleMedium.tabularNumbers())
            OutlinedIconButton(onClick = { onChange(value + 1) }, enabled = enabled && value < 8, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Add, "Crește porțiile")
            }
        } else {
            IconButton(onClick = { onChange(value - 1) }, enabled = enabled && value > 1) { Icon(Icons.Outlined.Remove, "Scade porțiile") }
            Text("$value")
            IconButton(onClick = { onChange(value + 1) }, enabled = enabled && value < 8) { Icon(Icons.Outlined.Add, "Crește porțiile") }
        }
    }
}

@Composable
fun CookingDialog(snapshot: Snapshot, recipe: Recipe, initialServings: Int, enabled: Boolean, onDismiss: () -> Unit, initialKeepBatch: Boolean = false,
    onSave: (List<StockUse>, Int, BatchDetails?) -> Unit) {
    var servings by remember { mutableIntStateOf(initialServings) }
    val preview = remember(snapshot.pantry, recipe.id, servings) { RecipeMath.cooking(snapshot, recipe, servings) }
    var amounts by remember(preview) { mutableStateOf(preview.uses.associate { it.item.id to RecipeMath.number(it.quantity) }) }
    var error by remember { mutableStateOf<String?>(null) }
    var keepBatch by remember { mutableStateOf(initialKeepBatch) }
    var cookedOn by remember { mutableStateOf(LocalDate.now().toString()) }
    var expiresOn by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("frigider") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Am gătit · confirmă consumul") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(recipe.title)
            PortionPicker(servings, enabled) { servings = it }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(keepBatch, { keepBatch = it }, enabled = enabled); Text("Păstrează porții pentru alte zile") }
            if (keepBatch) {
                OutlinedTextField(cookedOn, { cookedOn = it.take(10) }, label = { Text("Data gătirii · AAAA-LL-ZZ") })
                OutlinedTextField(expiresOn, { expiresOn = it.take(10) }, label = { Text("Termen ales de tine · AAAA-LL-ZZ") }, supportingText = { Text("Aplicația nu stabilește automat durata de păstrare.") })
                Row { FilterChip(location == "frigider", { location = "frigider" }, label = { Text("Frigider") }); Spacer(Modifier.width(8.dp)); FilterChip(location == "congelator", { location = "congelator" }, label = { Text("Congelator") }) }
            }
            Text("Ajustează cantitățile folosite. Zero înseamnă că păstrezi alimentul. Schimbarea porțiilor recalculează lista.", style = MaterialTheme.typography.bodySmall)
            preview.uses.forEach { use ->
                OutlinedTextField(amounts[use.item.id].orEmpty(), { amounts = amounts + (use.item.id to it.take(12)) },
                    label = { Text("${use.item.name} · ${use.item.unit}") }, supportingText = { Text("Disponibil: ${RecipeMath.number(use.item.quantity)} · termen ${use.item.expires_on ?: "nespecificat"}") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            }
            preview.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
            Text("Poți anula ulterior din Buget → Istoric.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            val uses = preview.uses.mapNotNull { use ->
                val amount = amounts[use.item.id]?.replace(',', '.')?.toDoubleOrNull()
                require(amount != null && amount.isFinite() && amount >= 0 && amount <= use.item.quantity) { "Verifică ${use.item.name}: cantitatea trebuie să fie între 0 și stocul disponibil." }
                if (amount == 0.0) null else use.copy(quantity = amount)
            }
            require(keepBatch || uses.isNotEmpty()) { "Nu există cantități de scăzut din inventar." }
            val details = if (keepBatch) {
                require(LocalDate.parse(cookedOn) <= LocalDate.now() && LocalDate.parse(expiresOn) >= LocalDate.parse(cookedOn)) { "Verifică data gătirii și termenul ales." }
                BatchDetails(cookedOn, expiresOn, location)
            } else null
            onSave(uses, servings, details)
        }.onFailure { error = it.message }
    }, enabled = enabled && (keepBatch || preview.uses.isNotEmpty())) { Text(if (keepBatch) "Salvează porțiile" else "Confirmă consumul") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } })
}

@Composable
fun ShoppingPreviewDialog(preview: ShoppingPreview, enabled: Boolean, onDismiss: () -> Unit, onSave: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Cumpărături pentru plan") }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (preview.lines.isEmpty()) Text("Lista și inventarul acoperă ingredientele care pot fi calculate.")
            preview.lines.forEach { Text(it) }
            preview.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
            if (preview.lines.isNotEmpty()) Text("Aceste cantități vor completa lista comună. Produsele deja trecute în cantități suficiente sunt păstrate.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onSave, enabled = enabled && preview.changes.isNotEmpty()) { Text("Actualizează lista") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Închide") } })
}
