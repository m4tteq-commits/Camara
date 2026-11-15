package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ro.camara.app.data.ProductPrices
import ro.camara.app.data.PurchaseHistory
import ro.camara.app.data.RecipeMath
import ro.camara.app.data.Snapshot

@Composable
internal fun PriceHistoryScreen(snapshot: Snapshot, modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val products = remember(snapshot.purchases, snapshot.receipts, snapshot.products, snapshot.aliases) { PurchaseHistory.byProduct(snapshot) }
    val search = RecipeMath.normalize(query)
    val filtered = remember(products, search) {
        products.filter { product ->
            RecipeMath.normalize(product.name).contains(search) || product.quotes.any { RecipeMath.normalize(it.item.name).contains(search) }
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            PageHeader("Istoric de prețuri", "Compară ce ai plătit pe kg, litru sau bucată în bonurile și coșurile confirmate.")
        }
        if (products.isNotEmpty()) item {
            OutlinedTextField(query, { query = it.take(100) }, label = { Text("Caută un produs") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) })
        }
        if (filtered.isEmpty()) item {
            EmptyPanel(Icons.Outlined.ShowChart, if (products.isEmpty()) "Niciun preț înregistrat" else "Niciun produs găsit",
                if (products.isEmpty()) "Confirmă un bon sau un coș cu prețuri ca să începi comparația." else "Încearcă o altă denumire.")
        }
        items(filtered, key = { it.key }) { product ->
            OutlinedCard(onClick = { selected = product.key }) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(product.name, style = MaterialTheme.typography.titleMedium)
                        Text(countLabel(product.quotes.size, "preț în lei/${product.unit}", "prețuri în lei/${product.unit}"),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(product.quotes.first().unitPrice.label, style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }
        }
    }
    products.firstOrNull { it.key == selected }?.let { PriceDetails(it) { selected = null } }
}

@Composable
private fun PriceDetails(product: ProductPrices, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(product.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("De la cea mai recentă cumpărătură · lei/${product.unit}", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(product.quotes) { quote ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(quote.entry.merchant, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text(quote.unitPrice.label, style = MaterialTheme.typography.labelLarge)
                            }
                            Text("${quote.entry.date} · ${money(checkNotNull(quote.item.cents).toLong())} pentru " +
                                "${RecipeMath.number(quote.item.quantity)} ${quote.item.unit}", style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider(Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Închide") } },
    )
}

