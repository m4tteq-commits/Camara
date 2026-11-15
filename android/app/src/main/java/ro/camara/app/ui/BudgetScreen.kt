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
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import ro.camara.app.data.*

fun money(cents: Long) = BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',') + " lei"
fun parseMoney(value: String): Int? = runCatching {
    BigDecimal(value.trim().replace(',', '.')).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).intValueExact().also { require(it in 0..100_000_000) }
}.getOrNull()

@Composable
fun BudgetScreen(snapshot: Snapshot, enabled: Boolean, onExpense: (Expense?) -> Unit, onDelete: (Expense) -> Unit,
                 onBudget: (String) -> Unit, onUndo: (FoodEvent) -> Unit, modifier: Modifier) {
    var offset by rememberSaveable { mutableLongStateOf(0) }
    var history by rememberSaveable { mutableStateOf(false) }
    val month = YearMonth.now().plusMonths(offset).toString()
    val expenses = snapshot.expenses.filter { it.spent_on.startsWith(month) }
    val events = snapshot.events.filter { it.occurred_on.startsWith(month) }
    val active = events.filterNot { it.undone }
    val spent = expenses.sumOf { it.amount_cents.toLong() }
    val limit = snapshot.budgets.firstOrNull { it.month == month }?.limit_cents ?: 0
    val waste = active.filter { it.kind == "wasted" }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Buget și risipă", style = MaterialTheme.typography.headlineMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { offset-- }) { Icon(Icons.Outlined.ChevronLeft, "Luna anterioară") }
                Text(month, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { offset++ }) { Icon(Icons.Outlined.ChevronRight, "Luna următoare") }
            }
            Card {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cheltuit: ${money(spent)}", style = MaterialTheme.typography.titleLarge)
                    Text(if (limit > 0) "Limită: ${money(limit.toLong())}" else "Fără limită lunară stabilită")
                    if (limit > 0) {
                        LinearProgressIndicator(progress = { (spent.toFloat() / limit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text(if (spent > limit) "Depășire: ${money(spent - limit)}" else "Disponibil: ${money(limit - spent)}")
                    }
                    TextButton(onClick = { onBudget(month) }, enabled = enabled) { Text("Stabilește bugetul lunii") }
                    Text("${active.count { it.kind == "cooked" }} mese gătite · ${waste.size} înregistrări de risipă")
                    Text("Valoare risipă: ${money(waste.sumOf { it.value_cents?.toLong() ?: 0L })} · ${waste.count { it.value_cents == null }} neevaluate", style = MaterialTheme.typography.bodySmall)
                    Text("Risipă înseamnă valoarea introdusă de tine; nu se adaugă din nou la cheltuieli.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!history, { history = false }, label = { Text("Cheltuieli") })
                FilterChip(history, { history = true }, label = { Text("Istoric") })
            }
            if (!history) OutlinedButton(onClick = { onExpense(null) }, enabled = enabled) { Text("Adaugă cheltuială") }
        }
        if (!history) {
            if (expenses.isEmpty()) item { Text("Nu ai înregistrat cheltuieli în această lună.") }
            items(expenses.sortedByDescending { it.spent_on }, key = { it.id }) { expense ->
                OutlinedCard(onClick = { if (enabled) onExpense(expense) }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(expense.label, style = MaterialTheme.typography.titleMedium); Text("${expense.spent_on} · ${expense.category}", style = MaterialTheme.typography.bodySmall); Text(money(expense.amount_cents.toLong())) }
                        IconButton(onClick = { onDelete(expense) }, enabled = enabled) { Icon(Icons.Outlined.DeleteOutline, "Elimină cheltuiala") }
                    }
                }
            }
        } else {
            if (events.isEmpty()) item { Text("Aici apar mesele gătite, consumul și risipa.") }
            items(events.sortedByDescending { it.occurred_on }, key = { it.id }) { event ->
                OutlinedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val label = when (event.kind) {
                            "used" -> "Folosit"
                            "cooked" -> "Gătit"
                            "prepared" -> "Porții pregătite"
                            "served" -> "Porții mâncate"
                            else -> "Aruncat"
                        }
                        Text("$label · ${event.title}", style = MaterialTheme.typography.titleMedium)
                        event.portions?.let { Text("$it porții", style = MaterialTheme.typography.bodySmall) }
                        Text(event.occurred_on, style = MaterialTheme.typography.bodySmall)
                        event.allocations.forEach { Text("${it.item.name}: ${RecipeMath.number(it.quantity)} ${it.item.unit}", style = MaterialTheme.typography.bodySmall) }
                        if (event.reason.isNotBlank()) Text(event.reason)
                        if (event.kind == "wasted") Text(event.value_cents?.let { money(it.toLong()) } ?: "Valoare necompletată")
                        if (event.undone) Text("Anulat · cantitățile au fost restabilite", color = MaterialTheme.colorScheme.primary)
                        else TextButton(onClick = { onUndo(event) }, enabled = enabled) { Text("Anulează și restabilește stocul") }
                    }
                }
            }
        }
    }
}

@Composable
fun ExpenseDialog(editing: Expense?, enabled: Boolean, onDismiss: () -> Unit, onSave: (Expense) -> Unit) {
    val id = remember { editing?.id ?: UUID.randomUUID().toString() }
    var label by remember { mutableStateOf(editing?.label ?: "") }
    var amount by remember { mutableStateOf(editing?.amount_cents?.let { BigDecimal.valueOf(it.toLong(), 2).toPlainString() } ?: "") }
    var date by remember { mutableStateOf(editing?.spent_on ?: LocalDate.now().toString()) }
    var category by remember { mutableStateOf(editing?.category ?: "Alimente") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Cheltuială") }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(label, { label = it.take(100) }, label = { Text("Descriere · magazin sau produse") })
            OutlinedTextField(amount, { amount = it.take(14) }, label = { Text("Suma · lei") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(date, { date = it.take(10) }, label = { Text("Data · AAAA-LL-ZZ") })
            OutlinedTextField(category, { category = it.take(100) }, label = { Text("Categorie") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val cents = parseMoney(amount)
        if (label.isBlank() || category.isBlank() || cents == null || cents <= 0 || runCatching { LocalDate.parse(date) }.isFailure) error = "Completează descrierea, categoria, data și o sumă pozitivă cu maximum două zecimale."
        else onSave(Expense(id, label.trim(), cents, date, category.trim()))
    }, enabled = enabled) { Text("Salvează") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } })
}

@Composable
fun BudgetDialog(month: String, current: Int, enabled: Boolean, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var amount by remember { mutableStateOf(BigDecimal.valueOf(current.toLong(), 2).toPlainString()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Buget pentru $month") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(amount, { amount = it.take(14) }, label = { Text("Limită · lei") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Text("Zero elimină limita pentru această lună.", style = MaterialTheme.typography.bodySmall)
            if (error) Text("Introdu o sumă validă cu maximum două zecimale.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { parseMoney(amount)?.let(onSave) ?: run { error = true } }, enabled = enabled) { Text("Salvează") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } })
}

@Composable
fun WasteDialog(item: PantryItem, enabled: Boolean, onDismiss: () -> Unit, onSave: (Double, String, Int?) -> Unit) {
    var quantity by remember { mutableStateOf(RecipeMath.number(item.quantity)) }
    var reason by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Am aruncat · ${item.name}") }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(quantity, { quantity = it.take(12) }, label = { Text("Cantitate · ${item.unit}") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Text("Disponibil: ${RecipeMath.number(item.quantity)} ${item.unit}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(reason, { reason = it.take(200) }, label = { Text("Motiv · opțional") })
            OutlinedTextField(value, { value = it.take(14) }, label = { Text("Valoarea aruncată · lei, opțional") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Text("Scădem numai cantitatea confirmată. Poți anula din Buget → Istoric.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val amount = quantity.replace(',', '.').toDoubleOrNull()
        val cents = if (value.isBlank()) null else parseMoney(value)
        if (amount == null || !amount.isFinite() || amount <= 0 || amount > item.quantity || (value.isNotBlank() && cents == null)) error = "Verifică stocul disponibil și valoarea în lei."
        else onSave(amount, reason.trim(), cents)
    }, enabled = enabled) { Text("Confirmă risipa") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } })
}
