package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ro.camara.app.data.*
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreparedScreen(snapshot: Snapshot, enabled: Boolean, onCook: (Recipe) -> Unit, onPlan: (PreparedBatch) -> Unit,
    onServe: (PreparedBatch, Int, Boolean) -> Unit, modifier: Modifier, onOpenPlan: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    var serving by remember { mutableStateOf<PreparedBatch?>(null) }
    var portions by remember { mutableIntStateOf(1) }
    var wasted by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("available") }
    val visible = snapshot.prepared.filter { filter == "all" || it.remaining > 0 && (filter != "frozen" || it.location == "congelator") }
        .sortedWith(compareBy<PreparedBatch> { it.remaining == 0 }.thenBy { it.expires_on })
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeader("Pentru alte zile", "Porțiile pe care le-ai pregătit.") {
            Box {
                TextButton(onClick = { menu = true }, enabled = enabled && snapshot.allRecipes().isNotEmpty()) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text("Gătește") }
                DropdownMenu(menu, { menu = false }, Modifier.heightIn(max = 350.dp)) { snapshot.allRecipes().distinctBy { it.title to it.ingredients }.forEach { recipe -> DropdownMenuItem(text = { Text(recipe.title) }, onClick = { menu = false; onCook(recipe) }) } }
            }
        } }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf("available" to "Cu porții rămase", "frozen" to "Congelate", "all" to "Toate").forEach { (key,label) -> FilterChip(filter == key, { filter = key }, label = { Text(label) }) } } }
        if (visible.isEmpty()) item { EmptyPanel(Icons.Outlined.TakeoutDining, "O masă pentru mai târziu", "Alege o rețetă și păstrează porțiile la confirmarea gătirii.") }
        items(visible, key = { it.id }) { batch ->
            var options by remember { mutableStateOf(false) }
            val reserved = PreparedRules.reserved(snapshot, batch.id)
            val expired = batch.expires_on < LocalDate.now().toString()
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(if (batch.location == "congelator") Icons.Outlined.AcUnit else Icons.Outlined.TakeoutDining)
                        Text(batch.recipe.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Box {
                            IconButton(onClick = { options = true }, enabled = enabled && batch.remaining > reserved) { Icon(Icons.Outlined.MoreVert, "Opțiuni pentru porții") }
                            DropdownMenu(options, { options = false }) { DropdownMenuItem(text = { Text("Am aruncat porții") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { options = false; serving = batch; portions = 1; wasted = true }) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${batch.remaining}", style = MaterialTheme.typography.headlineMedium)
                        Text("din ${portionsLabel(batch.total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        if (reserved > 0) SmallPill(countLabel(reserved, "rezervată", "rezervate"), Icons.Outlined.CalendarToday)
                    }
                    Text("${storageName(batch.location)} · gătit ${batch.cooked_on}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SmallPill(expiryText(batch.expires_on), Icons.Outlined.Schedule, if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                    if (batch.remaining > 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { serving = batch; portions = 1; wasted = false }, enabled = enabled && !expired && batch.remaining > reserved, modifier = Modifier.weight(1f)) { Text("Am mâncat") }
                            OutlinedButton(onClick = { onPlan(batch) }, enabled = enabled && !expired && batch.remaining > reserved, modifier = Modifier.weight(1f)) { Text("Planifică") }
                        }
                        if (reserved > 0 && onOpenPlan != null) TextButton(onClick = onOpenPlan) { Text("Vezi rezervările din plan") }
                    }
                }
            }
        }
        item { Note("Ingredientele se scad la gătire. Când mănânci, scădem doar porțiile pregătite. Termenul este cel introdus de tine.") }
    }
    serving?.let { batch ->
        val free = batch.remaining - PreparedRules.reserved(snapshot, batch.id)
        AlertDialog(onDismissRequest = { serving = null }, title = { Text(if (wasted) "Porții aruncate" else "Porții mâncate") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(batch.recipe.title, style = MaterialTheme.typography.titleMedium); PortionPicker(portions, enabled) { portions = it.coerceIn(1, maxOf(1, free)) }; Note("Disponibile fără rezervare: $free") }
        }, confirmButton = { TextButton(onClick = { onServe(batch, portions, wasted); serving = null }, enabled = enabled && free >= portions) { Text("Confirmă") } }, dismissButton = { TextButton(onClick = { serving = null }) { Text("Anulează") } })
    }
}
