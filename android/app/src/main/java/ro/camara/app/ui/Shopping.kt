package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import ro.camara.app.data.PurchaseHistory
import ro.camara.app.data.RecipeMath
import ro.camara.app.data.ShoppingItem
import ro.camara.app.data.ShoppingAisle
import ro.camara.app.data.Snapshot

@Composable
fun ShoppingScreen(snapshot: Snapshot, canWrite: Boolean, onCheck: (ShoppingItem) -> Unit, onDelete: (ShoppingItem) -> Unit,
    modifier: Modifier, onStore: () -> Unit, onRepeat: (String, Set<Int>, () -> Unit) -> Unit, onPrices: () -> Unit,
    onAisleChange: ((ShoppingItem) -> Unit)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("pending") }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var aisleItemId by rememberSaveable { mutableStateOf<String?>(null) }
    val history = remember(snapshot.purchases, snapshot.receipts) { PurchaseHistory.entries(snapshot) }
    val pending = snapshot.shopping.count { !it.checked }
    val completed = snapshot.shopping.size - pending
    val visible = remember(snapshot.shopping, query, filter) {
        val needle = RecipeMath.normalize(query)
        snapshot.shopping.filter { item ->
            (filter == "all" || item.checked == (filter == "checked")) && RecipeMath.normalize(item.name).contains(needle)
        }.sortedBy { it.checked }
    }
    val activeByAisle = remember(visible) { visible.filterNot { it.checked }.groupBy { ShoppingAisle.forProduct(it.name, it.aisle) } }
    val checked = remember(visible) { visible.filter { it.checked } }
    LazyColumn(modifier.fillMaxSize(), state = rememberLazyListState(),
        contentPadding = PaddingValues(screenGutter, 12.dp, screenGutter, 100.dp), verticalArrangement = Arrangement.spacedBy(cardGap)) {
        item(key = "header") {
            Column(Modifier.padding(bottom = sectionGap - cardGap), verticalArrangement = Arrangement.spacedBy(sectionGap)) {
                PageHeader("Lista de cumpărături", countLabel(pending, "produs de cumpărat", "produse de cumpărat"))
                Column(verticalArrangement = Arrangement.spacedBy(cardGap)) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(cardGap)) {
                        PrimaryBrowseAction("În magazin", Icons.Outlined.ShoppingCart, onStore, canWrite,
                            modifier = Modifier.weight(.65f).fillMaxHeight())
                        SecondaryBrowseAction("Istoric de prețuri", onPrices, Modifier.weight(.35f).fillMaxHeight(), minHeight = 50.dp)
                    }
                    if (history.isNotEmpty()) SecondaryBrowseAction("Cumpăr din nou", { showHistory = true }, Modifier)
                }
                BrowseControls(query, { query = it }, "Caută un produs", filter,
                    listOf("all" to "Toate", "pending" to "De cumpărat", "checked" to "Bifate"),
                    { filter = it }, counts = mapOf("checked" to completed), horizontalFilters = true)
            }
        }
        if (visible.isEmpty()) item(key = "empty") {
            val (title, detail) = when {
                snapshot.shopping.isEmpty() -> "Lista este liberă" to "Adaugă un produs sau completează lista din planul meselor."
                query.isNotBlank() -> "Niciun rezultat" to "Schimbă căutarea sau filtrul."
                filter == "checked" -> "Niciun produs bifat" to "Produsele bifate apar aici."
                else -> "Totul este bifat" to "Alege filtrul Bifate ca să vezi produsele."
            }
            EmptyPanel(if (query.isBlank()) Icons.Outlined.ShoppingBasket else Icons.Outlined.Search, title, detail)
        }
        ShoppingAisle.entries.forEach { aisle ->
            val products = activeByAisle[aisle].orEmpty()
            if (products.isNotEmpty()) {
                item(key = "aisle:${aisle.id}") { SectionTitle(aisle.label) }
                items(products, key = { "product:${it.id}" }) { item ->
                    ShoppingRow(item, canWrite, onCheck, onDelete, onAisleChange?.let { { aisleItemId = item.id } })
                }
            }
        }
        if (checked.isNotEmpty()) {
            item(key = "checked") { SectionTitle(countLabel(checked.size, "produs bifat", "produse bifate")) }
            items(checked, key = { "product:${it.id}" }) { item ->
                ShoppingRow(item, canWrite, onCheck, onDelete, onAisleChange?.let { { aisleItemId = item.id } })
            }
        }
    }
    if (showHistory) PurchaseHistoryDialog(history, canWrite, { showHistory = false }, onRepeat)
    snapshot.shopping.firstOrNull { it.id == aisleItemId }?.let { item ->
        var aisle by rememberSaveable(item.id) { mutableStateOf(item.aisle) }
        AlertDialog(onDismissRequest = { aisleItemId = null }, title = { Text(item.name) },
            text = { AisleField(item.name, aisle) { aisle = it } },
            confirmButton = { TextButton(onClick = { onAisleChange?.invoke(item.copy(aisle = aisle)); aisleItemId = null },
                enabled = canWrite) { Text("Salvează") } },
            dismissButton = { TextButton(onClick = { aisleItemId = null }) { Text("Anulează") } })
    }
}
@Composable
private fun ShoppingRow(item: ShoppingItem, enabled: Boolean, onCheck: (ShoppingItem) -> Unit, onDelete: (ShoppingItem) -> Unit,
    onAisle: (() -> Unit)?) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest, border = cardOutline()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(item.checked, { onCheck(item) }, enabled = enabled)
            Column(Modifier.weight(1f).padding(horizontal = 5.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                    color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (item.quantity.isNotBlank()) Text(item.quantity, style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onAisle != null) IconButton(onClick = onAisle, enabled = enabled) {
                Icon(Icons.Outlined.Tune, "Schimbă raionul pentru ${item.name}", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onDelete(item) }, enabled = enabled) { Icon(Icons.Outlined.Close, "Elimină ${item.name}", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
