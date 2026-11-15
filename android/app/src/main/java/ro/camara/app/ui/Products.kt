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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.util.UUID
import ro.camara.app.data.*

@Composable
fun ValueChoice(value: String, values: List<Pair<String, String>>, enabled: Boolean = true, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text(values.firstOrNull { it.first == value }?.second ?: value) }
        DropdownMenu(expanded, { expanded = false }) { values.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onChange(key); expanded = false }) } }
    }
}
val units = listOf("buc", "g", "kg", "ml", "l").map { it to it }
val locations = listOf("frigider" to "Frigider", "camara" to "Cămară", "congelator" to "Congelator")

@Composable
fun ProductsScreen(products: List<Product>, enabled: Boolean, onAdd: () -> Unit, onScan: () -> Unit, onEdit: (Product) -> Unit,
    onStock: (Product) -> Unit, onDelete: (Product) -> Unit, modifier: Modifier) {
    var search by remember { mutableStateOf("") }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Catalogul tău", style = MaterialTheme.typography.headlineMedium)
            Text("Memorăm ambalajul o singură dată. Codurile cunoscute funcționează și fără conexiune.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(search, { search = it.take(100) }, label = { Text("Caută nume sau cod") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = onScan, enabled = enabled) { Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scanează codul") }
            TextButton(onClick = onAdd, enabled = enabled) { Text("Adaugă produs manual") }
        }
        if (products.isEmpty()) item { Text("La prima scanare completezi numele și cantitatea unui ambalaj. La următoarele adaugi doar numărul de ambalaje și termenul.") }
        items(products.filter { it.name.contains(search, true) || it.barcode.contains(search) }.sortedBy { it.name }, key = { it.id }) { product ->
            OutlinedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(product.name, style = MaterialTheme.typography.titleLarge)
                    Text("${RecipeMath.number(product.package_quantity)} ${product.unit} / ambalaj · ${product.barcode.ifBlank { "fără cod" }}")
                    Button(onClick = { onStock(product) }, enabled = enabled) { Text("Adaugă în cămară") }
                    Row {
                        TextButton(onClick = { onEdit(product) }, enabled = enabled) { Text("Editează") }
                        TextButton(onClick = { onDelete(product) }, enabled = enabled) { Text("Elimină") }
                    }
                }
            }
        }
    }
}

@Composable
fun ProductDialog(editing: Product?, scannedCode: String, enabled: Boolean, onDismiss: () -> Unit, onSave: (Product) -> Unit) {
    val id = remember { editing?.id ?: UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var code by remember { mutableStateOf(editing?.barcode ?: scannedCode) }
    var quantity by remember { mutableStateOf(editing?.package_quantity?.let { RecipeMath.number(it) } ?: "1") }
    var unit by remember { mutableStateOf(editing?.unit ?: "buc") }
    var location by remember { mutableStateOf(editing?.location ?: "frigider") }
    var aliases by remember { mutableStateOf(editing?.receipt_names?.joinToString("\n") ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Produs din catalog") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Denumire") })
            OutlinedTextField(code, { code = it.take(14) }, label = { Text("Cod de bare · opțional") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(quantity, { quantity = it.take(12) }, label = { Text("Cantitatea unui ambalaj") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            ValueChoice(unit, units, enabled) { unit = it }
            ValueChoice(location, locations, enabled) { location = it }
            OutlinedTextField(aliases, { aliases = it.take(2000) }, label = { Text("Denumiri de pe bon · câte una pe rând") }, minLines = 2)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val amount = quantity.replace(',', '.').toDoubleOrNull()
        val names = aliases.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (name.isBlank() || amount == null || !amount.isFinite() || amount <= 0 || amount > 100000 || !Regex("^(?:[0-9]{8}|[0-9]{12,14})?$").matches(code) || names.size > 20 || names.any { it.length > 100 }) error = "Verifică numele, cantitatea și codul (8, 12, 13 sau 14 cifre)."
        else onSave(Product(id, name.trim(), code, amount, unit, location, names))
    }, enabled = enabled) { Text("Salvează") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } })
}

@Composable
fun ProductStockDialog(product: Product, enabled: Boolean, onDismiss: () -> Unit, onSave: (String, PantryInput) -> Unit) {
    val id = remember { UUID.randomUUID().toString() }
    var count by remember { mutableStateOf("1") }
    var expiry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val total = count.toIntOrNull()?.let { it * product.package_quantity }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(product.name) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Un ambalaj: ${RecipeMath.number(product.package_quantity)} ${product.unit}")
            OutlinedTextField(count, { count = it.take(5) }, label = { Text("Număr de ambalaje") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            total?.let { Text("Adăugăm ${RecipeMath.number(it)} ${product.unit}") }
            OutlinedTextField(expiry, { expiry = it.take(10) }, label = { Text("Termen opțional · AAAA-LL-ZZ") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val number = count.toIntOrNull()
        if (number == null || number <= 0 || total == null || total <= 0 || total > 100000 || (expiry.isNotBlank() && runCatching { LocalDate.parse(expiry) }.isFailure)) error = "Verifică numărul de ambalaje și termenul."
        else onSave(id, product.stockInput(number, expiry.ifBlank { null }))
    }, enabled = enabled) { Text("Adaugă") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } })
}
