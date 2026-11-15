package ro.camara.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ro.camara.app.data.*
import java.util.UUID

@Composable
fun InStoreScreen(cart: ShoppingCart?, snapshot: Snapshot, enabled: Boolean, onChange: (ShoppingCart) -> Unit,
    onCheckout: () -> Unit, onDiscard: () -> Unit, modifier: Modifier) {
    var editing by remember { mutableStateOf<CartRow?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var ordering by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (cart == null) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("aisle_order", android.content.Context.MODE_PRIVATE) }
    val merchant = RecipeMath.normalize(cart.merchant).ifBlank { "magazin" }
    var aisleOrder by remember(merchant) { mutableStateOf(ShoppingAisle.ordered(preferences.getString(merchant, null))) }
    val selected = remember(cart.rows) { cart.rows.filter { it.selected } }
    val byAisle = remember(selected) { selected.groupBy { ShoppingAisle.forProduct(it.name, it.aisle) } }
    val groups = remember(byAisle, aisleOrder) { aisleOrder.mapNotNull { aisle -> byAisle[aisle]?.let { aisle.label to it } } }
    val omitted = cart.rows.filterNot { it.selected }
    val unknown = selected.count { CheckoutRules.cents(it.price) == null }
    val total = remember(selected) { selected.sumOf { CheckoutRules.cents(it.price)?.toLong() ?: 0 } }
    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { PageHeader("Coșul tău", "Verifică ce iei cu tine acasă.") {
                TextButton(onClick = { editing = CartRow(UUID.randomUUID().toString(), "") }, enabled = enabled && cart.rows.size < 60) { Icon(Icons.Outlined.Add, null, Modifier.size(19.dp)); Text("Produs") }
            } }
            item {
                ExpandablePanel("Magazin și dată", "${cart.merchant} · ${cart.date}", details, { details = !details }, icon = Icons.Outlined.Storefront) {
                    OutlinedTextField(cart.merchant, { onChange(cart.copy(merchant = it.take(100))) }, label = { Text("Magazin") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cart.date, { onChange(cart.copy(date = it.take(10))) }, label = { Text("Data · AAAA-LL-ZZ") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SmallPill("Salvat pe telefon", Icons.Outlined.Check)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { discard = true }, enabled = enabled) { Icon(Icons.Outlined.DeleteOutline, "Golește coșul", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            } }
            item { TextButton(onClick = { ordering = true }, enabled = enabled) { Text("Aranjează raioanele") } }
            if (cart.rows.isEmpty()) item { EmptyPanel(Icons.Outlined.ShoppingCart, "Coșul este gol", "Adaugă un produs, cantitatea și prețul. Confirmi plata când ai terminat.") }
            val sections = if (omitted.isEmpty()) groups else groups + ("Scoase din coș" to omitted)
            sections.forEach { (title, rows) ->
                item(key = "aisle:$title") { SectionTitle(title) }
                items(rows, key = { it.id }) { row ->
                    Card(onClick = { editing = row }, enabled = enabled, colors = CardDefaults.cardColors(containerColor = if (row.selected) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(row.selected, { onChange(cart.copy(rows = cart.rows.map { if (it.id == row.id) it.copy(selected = !it.selected) else it })) }, enabled = enabled)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 3)
                                Text("${row.quantity} ${row.unit}${if (!row.includePantry) " · doar cheltuială" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(CheckoutRules.cents(row.price)?.let { money(it.toLong()) } ?: "Adaugă preț", style = MaterialTheme.typography.labelLarge,
                                    color = if (CheckoutRules.cents(row.price) == null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                                Icon(Icons.Outlined.Edit, "Editează ${row.name}", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (cart.rows.isNotEmpty()) item { Note("Atinge un produs pentru raion, cantitate, preț și termen. Prețul este pentru toată cantitatea poziției.") }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (snapshot.purchases.isNotEmpty()) item {
                ExpandablePanel("Confirmate recent", countLabel(snapshot.purchases.size, "cumpărătură înregistrată", "cumpărături înregistrate"), history, { history = !history }, icon = Icons.Outlined.ReceiptLong) {
                    snapshot.purchases.sortedByDescending { it.spent_on }.take(5).forEach { trip ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(trip.merchant, style = MaterialTheme.typography.titleSmall); Text(trip.spent_on, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text(money(trip.total_cents.toLong()), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 5.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Column { Text("Total · ${countLabel(selected.size, "produs", "produse")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (unknown > 0) Text(countLabel(unknown, "preț de completat", "prețuri de completat"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
                    Text(money(total), style = MaterialTheme.typography.headlineSmall)
                }
                Button(onClick = { runCatching { CheckoutRules.checkout(snapshot, cart); confirm = true }.onFailure { error = it.message } }, enabled = enabled && selected.isNotEmpty() && unknown == 0,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text(if (unknown > 0) "Completează prețurile" else "Verifică și confirmă") }
            }
        }
    }
    if (ordering) AisleOrderDialog(aisleOrder, { ordering = false }) { updated ->
        preferences.edit().putString(merchant, updated.joinToString(",") { it.id }).apply()
        aisleOrder = updated
        ordering = false
    }
    editing?.let { row -> CartRowDialog(row, snapshot, enabled, { editing = null }) { updated ->
        val rows = if (cart.rows.any { it.id == row.id }) cart.rows.map { if (it.id == row.id) updated else it } else cart.rows + updated
        onChange(cart.copy(rows = rows)); editing = null; error = null
    } }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Confirmi cumpărăturile?") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${cart.merchant} · ${cart.date}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            selected.forEach { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { Text(it.name, style = MaterialTheme.typography.titleSmall); Text("${it.quantity} ${it.unit}", style = MaterialTheme.typography.bodySmall) }
                Text(CheckoutRules.cents(it.price)?.let { money(it.toLong()) }.orEmpty(), style = MaterialTheme.typography.labelLarge)
            } }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Total: ${money(total)}", style = MaterialTheme.typography.headlineSmall)
            Note("Alimentele alese intră în cămară, lista se bifează și totalul este înregistrat o singură dată în buget.")
        }
    }, confirmButton = { TextButton(onClick = { confirm = false; onCheckout() }, enabled = enabled) { Text("Confirmă plata") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Mai verific") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Golești coșul local?") }, text = { Text("Lista comună și cumpărăturile deja confirmate rămân păstrate.") },
        confirmButton = { TextButton(onClick = { discard = false; onDiscard() }, enabled = enabled) { Text("Golește") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Păstrează") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CartRowDialog(initial: CartRow, snapshot: Snapshot, enabled: Boolean, onDismiss: () -> Unit, onSave: (CartRow) -> Unit) {
    var row by remember { mutableStateOf(initial) }
    var storage by rememberSaveable { mutableStateOf(initial.expires.isNotBlank() || initial.location != "camara") }
    var error by remember { mutableStateOf<String?>(null) }
    val history = remember(snapshot.purchases, snapshot.receipts) { PurchaseHistory.entries(snapshot) }
    val previous = remember(history, snapshot.aliases, row.name, row.unit) {
        PurchaseHistory.latest(history, snapshot.aliases, row.name, row.unit)
    }
    val quantity = row.quantity.replace(',', '.').toDoubleOrNull()
    val currentUnitPrice = PurchaseHistory.perUnit(CheckoutRules.cents(row.price), quantity, row.unit)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Produs cumpărat") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(row.name, { row = row.copy(name = it.take(100)) }, label = { Text("Nume") }, modifier = Modifier.fillMaxWidth())
            AisleField(row.name, row.aisle) { row = row.copy(aisle = it) }
            OutlinedTextField(row.quantity, { row = row.copy(quantity = it.take(12)) }, label = { Text("Cantitate totală") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("buc", "g", "kg", "ml", "l").forEach { unit -> FilterChip(row.unit == unit, { row = row.copy(unit = unit) }, label = { Text(unit) }) } }
            OutlinedTextField(row.price, { row = row.copy(price = it.take(12)) }, label = { Text("Preț total") }, suffix = { Text("lei") }, supportingText = { Text("Pentru toată cantitatea de mai sus.") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            if (currentUnitPrice != null || previous != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        currentUnitPrice?.let { Text("Acum · ${it.label}", style = MaterialTheme.typography.titleSmall) }
                        previous?.let { quote ->
                            Text("Ultima achiziție · ${quote.unitPrice.label}", style = MaterialTheme.typography.titleSmall)
                            Text("${money(checkNotNull(quote.item.cents).toLong())} pentru ${RecipeMath.number(quote.item.quantity)} ${quote.item.unit}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("${quote.entry.merchant} · ${quote.entry.date}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().clickable { row = row.copy(includePantry = !row.includePantry) }, verticalAlignment = Alignment.CenterVertically) { Checkbox(row.includePantry, { row = row.copy(includePantry = it) }); Text("Adaugă în cămară", style = MaterialTheme.typography.bodyMedium) }
            if (row.includePantry) ExpandablePanel("Depozitare și termen", storageName(row.location) + if (row.expires.isNotBlank()) " · ${row.expires}" else "", storage, { storage = !storage }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("camara" to "Cămară", "frigider" to "Frigider", "congelator" to "Congelator").forEach { (key,label) -> FilterChip(row.location == key, { row = row.copy(location = key) }, label = { Text(label) }) } }
                OutlinedTextField(row.expires, { row = row.copy(expires = it.take(10)) }, label = { Text("Termen opțional") }, placeholder = { Text("AAAA-LL-ZZ") }, modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        if (row.name.isBlank() || quantity == null || !quantity.isFinite() || quantity <= 0 || quantity > 100000 || CheckoutRules.cents(row.price) == null ||
            (row.expires.isNotBlank() && runCatching { java.time.LocalDate.parse(row.expires) }.isFailure)) error = "Verifică numele, cantitatea, prețul și termenul."
        else onSave(row)
    }, enabled = enabled) { Text("Salvează produsul") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } })
}
