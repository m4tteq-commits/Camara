package ro.camara.app.ui

import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ro.camara.app.data.*
import java.time.LocalDate
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoPlanScreen(snapshot: Snapshot, enabled: Boolean, online: Boolean, busy: Boolean,
    onPreview: (WeekRequest, (WeekPreview) -> Unit) -> Unit, onAccept: (WeekPreview) -> Unit, modifier: Modifier) {
    val id = rememberSaveable { UUID.randomUUID().toString() }
    var start by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var days by rememberSaveable { mutableStateOf("7") }
    var budget by rememberSaveable { mutableStateOf("") }
    var servings by rememberSaveable { mutableIntStateOf(1) }
    var slots by rememberSaveable { mutableStateOf(listOf("dinner")) }
    var minutes by rememberSaveable { mutableIntStateOf(30) }
    var diet by rememberSaveable { mutableStateOf("any") }
    var equipment by rememberSaveable { mutableStateOf(listOf("plita", "tigaie", "oala")) }
    var preferences by rememberSaveable { mutableStateOf("") }
    var exclude by rememberSaveable { mutableStateOf("") }
    var useAi by rememberSaveable { mutableStateOf(true) }
    var preview by remember { mutableStateOf<WeekPreview?>(null) }
    var priceFields by remember { mutableStateOf<Map<String, Pair<String, String>>>(emptyMap()) }
    var enteredPrices by remember { mutableStateOf<Map<String, PlanPrice>>(emptyMap()) }
    var dirty by remember { mutableStateOf(true) }
    var formChanged by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var calculation by remember { mutableIntStateOf(0) }
    var proposalOffset by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val motion = LocalCamaraMotion.current
    LaunchedEffect(calculation) {
        if (calculation > 0) {
            delay(150)
            val target = proposalOffset.coerceIn(0, scroll.maxValue)
            if (motion) scroll.animateScrollTo(target) else scroll.scrollTo(target)
        }
    }
    fun changed() { dirty = true; formChanged = true }
    fun calculate() {
        runCatching {
            val dayCount = days.toIntOrNull()
            val cents = CheckoutRules.cents(budget)
            require(dayCount != null && dayCount in 1..7 && cents != null && slots.isNotEmpty() && equipment.isNotEmpty()) { "Alege 1–7 zile, mesele, echipamentele și un buget pozitiv." }
            require(LocalDate.parse(start) >= LocalDate.now()) { "Data de început nu poate fi în trecut." }
            val prices = preview?.lines?.mapNotNull { line ->
                val fields = priceFields["${line.name}|${line.unit}"] ?: return@mapNotNull null
                if (fields.second.isBlank()) return@mapNotNull null
                val quantity = fields.first.replace(',', '.').toDoubleOrNull()
                val price = CheckoutRules.cents(fields.second)
                require(quantity != null && quantity.isFinite() && quantity > 0 && quantity <= 100000 && price != null) { "Verifică ambalajul și prețul pentru ${line.name}." }
                PlanPrice(line.name, quantity, line.unit, price)
            }.orEmpty()
            val exclusions = exclude.split(',').map { it.trim() }.filter { it.isNotBlank() }
            require(exclusions.size <= 20 && exclusions.all { it.length <= 100 }) { "Introdu cel mult 20 de ingrediente, fiecare cu maximum 100 de caractere." }
            val mergedPrices = enteredPrices + prices.associateBy { "${it.name}|${it.unit}" }
            require(mergedPrices.size <= 100) { "Ai prea multe estimări în această propunere. Începe un plan nou." }
            val request = WeekRequest(id, start, dayCount, slots, servings, cents, minutes, equipment, diet, preferences, useAi,
                seed = if (formChanged) "" else preview?.seed.orEmpty(), prices = mergedPrices.values.toList(), exclude = exclusions)
            error = null
            onPreview(request) { value ->
                enteredPrices = mergedPrices; preview = value
                priceFields = value.lines.associate { "${it.name}|${it.unit}" to (RecipeMath.number(it.package_quantity) to (it.package_cents?.let { cents -> java.math.BigDecimal.valueOf(cents.toLong(), 2).toPlainString() } ?: "")) }
                dirty = false; formChanged = false; calculation++
            }
        }.onFailure { error = it.message }
    }
    val ready = preview?.action != null && !dirty
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(20.dp, 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageHeader("Plan pe buget", "Mesele tale, pornind de la ce ai deja.")
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Zile și buget", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(start, { start = it.take(10); changed() }, label = { Text("Prima zi") }, placeholder = { Text("AAAA-LL-ZZ") }, enabled = !busy, singleLine = true, modifier = Modifier.weight(1.4f))
                        OutlinedTextField(days, { days = it.take(1); changed() }, label = { Text("Zile · 1–7") }, enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(budget, { budget = it.take(12); changed() }, label = { Text("Buget pentru cumpărături") }, suffix = { Text("lei") }, enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Note("Estimăm doar cumpărăturile suplimentare. Ce ai deja nu se plătește din nou.")
                    PortionPicker(servings, !busy) { servings = it; changed() }
                    Text("Mese pentru fiecare zi", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf("breakfast" to "Mic dejun", "lunch" to "Prânz", "dinner" to "Cină").forEach { (key,label) ->
                        FilterChip(key in slots, { slots = if (key in slots) slots - key else slots + key; changed() }, enabled = !busy, label = { Text(label) })
                    } }
                }
            }
            ExpandablePanel("Preferințe și echipamente", "$minutes min · ${when (diet) { "vegan" -> "vegan"; "vegetarian" -> "vegetarian"; else -> "orice meniu" }} · ${equipment.size} echipamente", advanced, { advanced = !advanced }, icon = Icons.Outlined.Tune) {
                Text("Timp maxim pentru o masă", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf(15,30,45,60).forEach { value -> FilterChip(minutes == value, { minutes = value; changed() }, enabled = !busy, label = { Text("$value min") }) } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf("any" to "Orice", "vegetarian" to "Vegetarian", "vegan" to "Vegan").forEach { (key,label) -> FilterChip(diet == key, { diet = key; changed() }, enabled = !busy, label = { Text(label) }) } }
                Text("Cu ce gătești", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf("plita" to "Plită", "tigaie" to "Tigaie", "oala" to "Oală", "cuptor" to "Cuptor", "microunde" to "Microunde").forEach { (key,label) -> FilterChip(key in equipment, { equipment = if (key in equipment) equipment - key else equipment + key; changed() }, enabled = !busy, label = { Text(label) }) } }
                OutlinedTextField(exclude, { exclude = it.take(500); changed() }, label = { Text("Ingrediente excluse") }, supportingText = { Text("Nume separate prin virgulă. Verifică ingredientele propunerii.") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(preferences, { preferences = it.take(500); changed() }, label = { Text("Preferințe pentru alegerea AI") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(useAi, { useAi = it; changed() }, enabled = !busy); Text("AI local pentru propunere", style = MaterialTheme.typography.bodyMedium) }
                Note("Pentru acest plan, AI-ul rulează pe serverul tău.")
            }
            if (!online) Note("Conectează-te la server pentru calculul planului.", true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            preview?.let { value ->
                Column(Modifier.onGloballyPositioned { proposalOffset = it.positionInParent().y.toInt() }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("Propunerea ta")
                    Text("${countLabel(value.meals.size, "masă nouă", "mese noi")} · ${value.existing_meals} existente păstrate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SmallPill(if (value.ai_used) "Cu alegere AI" else "Propunere calculată", Icons.Outlined.AutoAwesome)
                    value.meals.forEach { meal ->
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconTile(if (meal.prepared_batch_id != null) Icons.Outlined.TakeoutDining else Icons.Outlined.Restaurant)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${meal.on_date} · ${when (meal.slot) { "breakfast" -> "Mic dejun"; "lunch" -> "Prânz"; else -> "Cină" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(meal.recipe.title, style = MaterialTheme.typography.titleMedium)
                                    Text("${portionsLabel(meal.servings)} · ${if (meal.prepared_batch_id != null) "deja gătite" else "${meal.recipe.minutes} min"}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (value.lines.isNotEmpty()) {
                        SectionTitle("Prețuri de verificat")
                        Note("Prețul este pentru întregul ambalaj indicat. Cumpărarea se rotunjește la acest ambalaj.")
                    }
                    value.lines.forEach { line ->
                        val key = "${line.name}|${line.unit}"
                        val fields = priceFields[key] ?: (RecipeMath.number(line.package_quantity) to "")
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(line.name, style = MaterialTheme.typography.titleMedium)
                                Text("De cumpărat: ${RecipeMath.number(line.buy_quantity)} ${line.unit}", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(fields.first, { priceFields = priceFields + (key to (it.take(12) to fields.second)); dirty = true }, label = { Text("Ambalaj · ${line.unit}") }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                                    OutlinedTextField(fields.second, { priceFields = priceFields + (key to (fields.first to it.take(12))); dirty = true }, label = { Text("Preț · lei") }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                                }
                                Text(line.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    value.warnings.forEach { Note(it, true) }
                    if (value.lines.isEmpty()) Note("Ingredientele și porțiile disponibile acoperă acest plan.")
                    if (dirty) Note("Ai făcut modificări. Recalculează pentru un total actualizat.")
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 5.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                preview?.let { value -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (value.total_cents == null) "Prețuri de completat" else "Total estimat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value.total_cents?.let { money(it.toLong()) } ?: money(value.known_cents.toLong()) + " + ?", style = MaterialTheme.typography.titleSmall)
                } }
                Button(onClick = { if (ready) onAccept(checkNotNull(preview)) else calculate() }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    enabled = enabled && !busy && (ready || online && (preview == null || dirty))) {
                    if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(when { busy -> "Se pregătește propunerea…"; ready -> "Salvează planul și lista"; preview == null -> "Generează planul"; dirty -> "Recalculează planul"; preview?.total_cents == null -> "Completează prețurile"; else -> "Ajustează opțiunile" })
                }
            }
        }
    }
}
