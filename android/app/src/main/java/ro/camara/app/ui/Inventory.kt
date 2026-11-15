package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Locale
import ro.camara.app.data.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PantryScreen(items: List<PantryItem>, onEdit: (PantryItem) -> Unit, onDelete: (PantryItem) -> Unit,
    onBulk: () -> Unit, canWrite: Boolean, bulkAvailable: Boolean, onWaste: (PantryItem) -> Unit, modifier: Modifier,
    onOpenMinimums: () -> Unit = {}, onUse: (PantryItem) -> Unit = {}, stockTools: Boolean = true, onScanBatch: () -> Unit = {},
    reviewTerms: Boolean = false, onTermsOpened: () -> Unit = {}, wide: Boolean = false, onQuickAdd: ((String) -> Unit)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("all") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()
    LaunchedEffect(reviewTerms) {
        if (reviewTerms) {
            query = ""
            location = "due"
            grid.scrollToItem(0)
            onTermsOpened()
        }
    }
    val today = LocalDate.now()
    val cutoff = today.plusDays(3).toString()
    val soon = remember(items, cutoff) { items.count { it.expires_on != null && it.expires_on <= cutoff } }
    val filtered = remember(items, query, location, cutoff) {
        val needle = RecipeMath.normalize(query)
        items.filter { RecipeMath.normalize(it.name).contains(needle) &&
            (location == "all" || location == it.location || (location == "due" && it.expires_on != null && it.expires_on <= cutoff)) }
            .sortedWith(compareBy<PantryItem> { it.expires_on ?: "9999" }.thenBy { RecipeMath.normalize(it.name) })
    }
    val selectedItem = filtered.firstOrNull { it.id == selectedId }
    ListDetailLayout(modifier, wide, list = { listModifier ->
        LazyVerticalGrid(columns = if (wide) GridCells.Fixed(1) else GridCells.Adaptive(280.dp),
            state = grid, modifier = listModifier, contentPadding = PaddingValues(screenGutter, 12.dp, screenGutter, 100.dp),
            verticalArrangement = Arrangement.spacedBy(cardGap), horizontalArrangement = Arrangement.spacedBy(cardGap)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = sectionGap - cardGap), verticalArrangement = Arrangement.spacedBy(sectionGap)) {
                    PageHeader("Cămara ta", countLabel(items.size, "aliment la îndemână", "alimente la îndemână"))
                    Column(verticalArrangement = Arrangement.spacedBy(cardGap)) {
                        PrimaryBrowseAction("Scanare în serie", Icons.Outlined.QrCodeScanner, onScanBatch, canWrite)
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(cardGap)) {
                            SecondaryBrowseAction("Stoc minim", onOpenMinimums, Modifier.weight(1f).fillMaxHeight(),
                                icon = Icons.Outlined.Inventory2)
                            SecondaryBrowseAction("Din text", onBulk, Modifier.weight(1f).fillMaxHeight(),
                                enabled = canWrite && bulkAvailable, icon = Icons.Outlined.EditNote)
                        }
                    }
                    BrowseControls(query, { query = it }, "Caută un aliment", location,
                        listOf("all" to "Toate", "frigider" to "Frigider", "camara" to "Cămară", "congelator" to "Congelate", "due" to "Termene"),
                        { location = it }, counts = mapOf("due" to soon), horizontalFilters = true)
                }
            }
            if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyPanel(if (items.isEmpty()) Icons.Outlined.Kitchen else Icons.Outlined.Search,
                        if (items.isEmpty()) "Aici începe cămara ta" else "Niciun rezultat",
                        if (items.isEmpty()) "Scanează sau adaugă un aliment ca să începi." else "Schimbă căutarea sau filtrul.")
                    if (items.isEmpty() && onQuickAdd != null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            listOf("Lapte", "Ouă", "Pâine").forEach { name ->
                                AssistChip(onClick = { onQuickAdd(name) }, enabled = canWrite, label = { Text(name) },
                                    leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)) })
                            }
                        }
                    }
                }
            }
            items(filtered, key = { it.id }) { item ->
                if (wide) {
                    val selected = selectedItem?.id == item.id
                    Card(onClick = { selectedId = item.id }, modifier = Modifier.semantics { this.selected = selected },
                        shape = MaterialTheme.shapes.medium, border = cardOutline(),
                        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLowest)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium)
                                Text("${RecipeMath.number(item.quantity)} ${item.unit} · ${storageName(item.location)}", style = MaterialTheme.typography.bodySmall.tabularNumbers())
                            }
                            Icon(Icons.Outlined.ChevronRight, null)
                        }
                    }
                    return@items
                }
                var menu by remember { mutableStateOf(false) }
                val urgent = item.expires_on != null && item.expires_on <= cutoff
                val expired = item.expires_on != null && item.expires_on < today.toString()
                Card(onClick = { onEdit(item) }, enabled = canWrite, shape = MaterialTheme.shapes.medium, border = cardOutline(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconTile(when (item.location) { "congelator" -> Icons.Outlined.AcUnit; "frigider" -> Icons.Outlined.Kitchen; else -> Icons.Outlined.Inventory2 })
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium)
                                Text("${RecipeMath.number(item.quantity)} ${item.unit} · ${storageName(item.location)}",
                                    style = MaterialTheme.typography.bodySmall.tabularNumbers(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box {
                                IconButton(onClick = { menu = true }, enabled = canWrite) { Icon(Icons.Outlined.MoreVert, "Opțiuni pentru ${item.name}") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("Editează") }, onClick = { menu = false; onEdit(item) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                                    DropdownMenuItem(text = { Text("Am aruncat") }, onClick = { menu = false; onWaste(item) }, leadingIcon = { Icon(Icons.Outlined.RestoreFromTrash, null) })
                                    DropdownMenuItem(text = { Text("Elimină alimentul") }, onClick = { menu = false; onDelete(item) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) })
                                }
                            }
                        }
                        SmallPill(expiryText(item.expires_on, today), Icons.Outlined.Schedule,
                            if (expired) MaterialTheme.colorScheme.error else if (urgent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!expired && stockTools) TextButton(onClick = { onUse(item) }, enabled = canWrite, modifier = Modifier.align(Alignment.End)) {
                            Icon(Icons.Outlined.RemoveCircleOutline, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Am folosit")
                        }
                    }
                }
            }
        }
    }, detail = { pane ->
        if (selectedItem == null) DetailPlaceholder(pane, Icons.Outlined.Kitchen, "Detalii aliment", "Alege un aliment din listă.")
        else key(selectedItem.id) {
            PantryItemDetails(selectedItem, today, canWrite, stockTools, pane, { selectedId = null }, onEdit, onUse, onWaste, onDelete)
        }
    })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PantryItemDetails(item: PantryItem, today: LocalDate, enabled: Boolean, stockTools: Boolean, modifier: Modifier,
    onClose: () -> Unit, onEdit: (PantryItem) -> Unit, onUse: (PantryItem) -> Unit,
    onWaste: (PantryItem) -> Unit, onDelete: (PantryItem) -> Unit) {
    val expired = item.expires_on?.let { it < today.toString() } == true
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp).padding(bottom = 76.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(item.name, storageName(item.location)) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Închide detaliile") }
        }
        Text("${RecipeMath.number(item.quantity)} ${item.unit}", style = MaterialTheme.typography.headlineSmall)
        SmallPill(expiryText(item.expires_on, today), Icons.Outlined.Schedule,
            if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        if (!expired && stockTools) PrimaryBrowseAction("Am folosit", Icons.Outlined.RemoveCircleOutline, { onUse(item) }, enabled)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onEdit(item) }, enabled = enabled) { Text("Editează") }
            OutlinedButton(onClick = { onWaste(item) }, enabled = enabled) { Text("Am aruncat") }
            TextButton(onClick = { onDelete(item) }, enabled = enabled) { Text("Elimină") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickUseDialog(item: PantryItem, enabled: Boolean, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    fun amountLabel(value: Double) = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    var text by rememberSaveable(item.id) { mutableStateOf(if (item.unit == "buc" && item.quantity >= 1.0) "1" else "") }
    val amount = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= item.quantity }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Am folosit · ${item.name}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Disponibil · ${amountLabel(item.quantity)} ${item.unit}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { text = it.take(32) }, label = { Text("Cantitate folosită") }, suffix = { Text(item.unit) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, isError = text.isNotBlank() && amount == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.unit == "buc" && item.quantity >= 1.0) AssistChip(onClick = { text = "1" }, label = { Text("1 buc") })
                AssistChip(onClick = { text = amountLabel(item.quantity / 2) }, label = { Text("Jumătate") })
                AssistChip(onClick = { text = amountLabel(item.quantity) }, label = { Text("Tot") })
            }
            if (amount != null) {
                val left = (item.quantity - amount).coerceAtLeast(0.0)
                Text("Rămân ${RecipeMath.number(left)} ${item.unit}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = {
        TextButton(onClick = { amount?.let(onConfirm) }, enabled = amount != null && enabled) { Text("Confirmă consumul") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } })
}
internal fun storageName(value: String) = when (value) { "congelator" -> "Congelator"; "camara" -> "Cămară"; else -> "Frigider" }
internal fun expiryText(value: String?, today: LocalDate = LocalDate.now()): String {
    val date = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return "Fără termen introdus"
    return when { date < today -> "Termen depășit · ${date.format(DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ro-RO")))}"; date == today -> "Termen astăzi"; date == today.plusDays(1) -> "Termen mâine"; else -> "Termen ${date.format(DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ro-RO")))}" }
}
