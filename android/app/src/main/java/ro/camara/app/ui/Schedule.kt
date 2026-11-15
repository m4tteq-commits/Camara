package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.temporal.ChronoUnit
import java.util.Locale
import ro.camara.app.data.*

private val planLocale = Locale.forLanguageTag("ro-RO")
private val weekStartFormat = DateTimeFormatter.ofPattern("d MMM", planLocale)
private val weekEndFormat = DateTimeFormatter.ofPattern("d MMM yyyy", planLocale)
private val planDayFormat = DateTimeFormatter.ofPattern("EEEE, d MMM", planLocale)
private val planSlots = listOf("breakfast", "lunch", "dinner")

@Composable
fun PlanScreen(snapshot: Snapshot, enabled: Boolean, onAdd: (LocalDate) -> Unit, onEdit: (MealPlan) -> Unit,
    onDelete: (MealPlan) -> Unit, onCook: (MealPlan) -> Unit, onShopping: (List<MealPlan>) -> Unit, modifier: Modifier,
    onAutomatic: () -> Unit = {}, onPrepared: () -> Unit = {}, onRecipe: (Recipe) -> Unit = {},
    focusDate: String? = null, onDateOpened: () -> Unit = {}) {
    var offset by rememberSaveable { mutableLongStateOf(0) }
    var dayIndex by rememberSaveable { mutableIntStateOf(LocalDate.now().dayOfWeek.value - 1) }
    val list = rememberLazyListState()
    val currentMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    LaunchedEffect(focusDate) {
        if (focusDate != null) {
            val date = LocalDate.parse(focusDate)
            val targetMonday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            offset = ChronoUnit.WEEKS.between(currentMonday, targetMonday)
            dayIndex = date.dayOfWeek.value - 1
            list.scrollToItem(0)
            onDateOpened()
        }
    }
    val monday = currentMonday.plusWeeks(offset)
    val meals = remember(snapshot.plans, monday) {
        val start = monday.toString()
        val end = monday.plusDays(6).toString()
        snapshot.plans.filter { it.on_date in start..end }
    }
    val day = monday.plusDays(dayIndex.toLong())
    val selectedMeals = remember(meals, day) {
        val selectedDate = day.toString()
        meals.filter { it.on_date == selectedDate }.sortedBy { planSlots.indexOf(it.slot) }
    }
    val hasChoices = rememberRecipeLibrary(snapshot).isNotEmpty()
    LazyColumn(modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeader("Planul tău", countLabel(meals.size, "masă în această săptămână", "mese în această săptămână")) {
            TextButton(onClick = onAutomatic, enabled = enabled) { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Plan AI") }
        } }
        item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { offset-- }) { Icon(Icons.Outlined.ChevronLeft, "Săptămâna anterioară") }
                        Text("${monday.format(weekStartFormat)} — ${monday.plusDays(6).format(weekEndFormat)}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        IconButton(onClick = { offset++ }) { Icon(Icons.Outlined.ChevronRight, "Săptămâna următoare") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf("Lu", "Ma", "Mi", "Jo", "Vi", "Sâ", "Du").forEachIndexed { index, label ->
                            val date = monday.plusDays(index.toLong())
                            val chosen = dayIndex == index
                            Surface(shape = MaterialTheme.shapes.small, color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                                contentColor = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).selectable(chosen, role = Role.Tab, onClick = { dayIndex = index })) {
                                Column(Modifier.padding(vertical = 11.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(label, style = MaterialTheme.typography.labelSmall)
                                    Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleSmall)
                                    Text(if (meals.any { it.on_date == date.toString() }) "•" else " ", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(day.format(planDayFormat).replaceFirstChar { it.titlecase(planLocale) }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onAdd(day) }, enabled = enabled && hasChoices) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text("Masă") }
            }
        }
        if (selectedMeals.isEmpty()) item { EmptyPanel(Icons.Outlined.CalendarToday, "O zi încă liberă", "Adaugă o masă sau pregătește un plan automat cu buget.") }
        items(selectedMeals, key = { it.id }) { meal ->
            var menu by remember { mutableStateOf(false) }
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmallPill(when (meal.slot) { "breakfast" -> "Mic dejun"; "lunch" -> "Prânz"; else -> "Cină" }, Icons.Outlined.Restaurant)
                        Spacer(Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Outlined.MoreVert, "Opțiuni pentru masă") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Editează masa") }, enabled = !meal.completed, onClick = { menu = false; onEdit(meal) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                                DropdownMenuItem(text = { Text("Elimină masa") }, onClick = { menu = false; onDelete(meal) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) })
                            }
                        }
                    }
                    Text(meal.recipe.title, style = MaterialTheme.typography.titleLarge)
                    Text("${portionsLabel(meal.servings)} · ${if (meal.prepared_batch_id != null) "din porțiile deja gătite" else "${meal.recipe.minutes} min"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (meal.completed) SmallPill("Masă încheiată", Icons.Outlined.CheckCircle)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!meal.completed) Button(onClick = { onCook(meal) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(if (meal.prepared_batch_id != null) "Am mâncat" else "Am gătit") }
                        OutlinedButton(onClick = { onRecipe(meal.recipe) }, modifier = Modifier.weight(1f)) { Text("Rețeta și pașii") }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { onShopping(meals) }, enabled = enabled && meals.any { !it.completed }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.ShoppingBasket, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Lista pentru întreaga săptămână")
            }
            TextButton(onClick = onPrepared, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.TakeoutDining, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Vezi porțiile deja gătite") }
        }
    }
}
