package ro.camara.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import ro.camara.app.UiState
import ro.camara.app.data.MealPlan
import ro.camara.app.data.RecipeMath
import ro.camara.app.data.StockMinimumRules

private val dayLabel = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ro-RO"))
private val shortDayLabel = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ro-RO"))
private val mealSlots = listOf("breakfast", "lunch", "dinner")

internal fun nextPlannedMeal(meals: List<MealPlan>, today: LocalDate): MealPlan? {
    val date = today.toString()
    return meals.asSequence().filter { !it.completed && it.on_date >= date }.minWithOrNull(
        compareBy<MealPlan> { it.on_date }.thenBy { mealSlots.indexOf(it.slot).takeIf { slot -> slot >= 0 } ?: mealSlots.size }
    )
}

@Composable
internal fun TodayScreen(state: UiState, onNavigate: (Destination) -> Unit, onBarcode: () -> Unit, onReceipt: () -> Unit, modifier: Modifier,
    onAdd: () -> Unit = {}, onReviewTerms: () -> Unit = { onNavigate(Destination.Pantry) }, onOpenMeal: (String) -> Unit = { onNavigate(Destination.Plan) }) {
    val today = LocalDate.now()
    val snapshot = state.snapshot
    val soon = remember(snapshot.pantry, today) { RecipeMath.expiring(snapshot.pantry, today) }
    val expired = snapshot.pantry.count { it.expires_on != null && it.expires_on < today.toString() }
    val restock = remember(snapshot, today) { StockMinimumRules.report(snapshot, today).count { it.missing > 0 } }
    val next = remember(snapshot.plans, today) { nextPlannedMeal(snapshot.plans, today) }
    val shopping = snapshot.shopping.filterNot { it.checked }
    val prepared = snapshot.prepared.filter { it.remaining > 0 }.sortedBy { it.expires_on }
    val month = YearMonth.from(today).toString()
    val spent = snapshot.expenses.filter { it.spent_on.startsWith(month) }.sumOf { it.amount_cents.toLong() }
    val budget = snapshot.budgets.firstOrNull { it.month == month }?.limit_cents ?: 0
    val canWrite = !state.busy && !state.generating

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(screenGutter, 12.dp, screenGutter, 28.dp),
        verticalArrangement = Arrangement.spacedBy(cardGap)) {
        item(key = "quick-actions") {
            Column(Modifier.padding(bottom = sectionGap - cardGap), verticalArrangement = Arrangement.spacedBy(sectionGap)) {
                PageHeader("Astăzi", today.format(dayLabel))
                Column(verticalArrangement = Arrangement.spacedBy(cardGap)) {
                    PrimaryBrowseAction("Scanează", Icons.Outlined.QrCodeScanner, onBarcode, canWrite)
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(cardGap)) {
                        TodayQuickAction("Adaugă aliment", Icons.Outlined.Add, Modifier.weight(1f), canWrite, onAdd)
                        TodayQuickAction("Adaugă bon", Icons.AutoMirrored.Outlined.ReceiptLong, Modifier.weight(1f), canWrite, onReceipt)
                        TodayQuickAction("Plan automat", Icons.Outlined.AutoAwesome, Modifier.weight(1f), canWrite) { onNavigate(Destination.Automatic) }
                    }
                }
            }
        }
        if (soon.isNotEmpty() || expired > 0) item(key = "expiry") {
            Card(shape = MaterialTheme.shapes.medium, border = cardOutline(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Termene de verificat", "Vezi în cămară", onReviewTerms)
                    soon.take(3).forEach { lot ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(lot.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            Text(expiryText(lot.expires_on, today), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (soon.size > 3) Text(countLabel(soon.size - 3, "alt aliment cu termen apropiat", "alte alimente cu termen apropiat"),
                        style = MaterialTheme.typography.bodySmall)
                    if (expired > 0) Text(countLabel(expired, "aliment cu termen depășit", "alimente cu termen depășit"),
                        style = MaterialTheme.typography.bodySmall)
                    if (soon.isNotEmpty()) TextButton(onClick = { onNavigate(Destination.UseSoon) }) {
                        Icon(Icons.Outlined.RestaurantMenu, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Gătește cu ce expiră")
                    }
                }
            }
        }
        item(key = "next-meal") {
            val title = next?.recipe?.title ?: "Planifică o masă"
            val detail = next?.let {
                val date = if (it.on_date == today.toString()) "Astăzi" else LocalDate.parse(it.on_date).format(shortDayLabel)
                val slot = when (it.slot) { "breakfast" -> "Mic dejun"; "lunch" -> "Prânz"; else -> "Cină" }
                "$date · $slot · ${portionsLabel(it.servings)}"
            } ?: "Alege o rețetă pentru următoarea masă."
            TodayLink("Următoarea masă", title, detail, Icons.Outlined.RestaurantMenu,
                { if (next == null) onNavigate(Destination.Recipes) else onOpenMeal(next.on_date) })
        }
        item(key = "shopping") {
            val detail = if (shopping.isEmpty()) "Toate cumpărăturile sunt la zi."
                else shopping.take(3).joinToString(" · ") { it.name } + if (shopping.size > 3) " · +${shopping.size - 3}" else ""
            TodayLink("Cumpărături", countLabel(shopping.size, "produs de cumpărat", "produse de cumpărat"),
                detail, Icons.Outlined.ShoppingBasket, { onNavigate(Destination.Shopping) })
        }
        if (restock > 0) item(key = "restock") {
            TodayLink(null, "Reaprovizionare", countLabel(restock, "aliment sub prag", "alimente sub prag"),
                Icons.Outlined.Inventory2, { onNavigate(Destination.Minimums) })
        }
        if (prepared.isNotEmpty()) item(key = "prepared") {
            TodayLink(null, "Porții gătite", prepared.take(2).joinToString(" · ") { it.recipe.title },
                Icons.Outlined.TakeoutDining, { onNavigate(Destination.Prepared) })
        }
        item(key = "budget") {
            Card(onClick = { onNavigate(Destination.Budget) }, modifier = Modifier.padding(top = sectionGap - cardGap),
                shape = MaterialTheme.shapes.medium, border = cardOutline(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(cardGap), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Outlined.AccountBalanceWallet, Modifier.size(40.dp), MaterialTheme.colorScheme.tertiary)
                        Column(Modifier.weight(1f)) {
                            Text("Bugetul lunii", style = MaterialTheme.typography.titleSmall)
                            Text(if (budget > 0) "${money(spent)} din ${money(budget.toLong())}" else "${money(spent)} cheltuiți",
                                style = MaterialTheme.typography.titleLarge.tabularNumbers())
                        }
                    }
                    if (budget > 0) {
                        val progress = (spent.toFloat() / budget).coerceIn(0f, 1f)
                        val shown = if (LocalCamaraMotion.current) {
                            val animated by animateFloatAsState(progress, tween(300), label = "Buget")
                            animated
                        } else progress
                        LinearProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = if (spent > budget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text(if (spent <= budget) "${money(budget - spent)} disponibili" else "Depășit cu ${money(spent - budget)}",
                            style = MaterialTheme.typography.bodySmall.tabularNumbers())
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayQuickAction(title: String, icon: ImageVector, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Card(onClick = onClick, modifier = modifier.fillMaxHeight().pressScale(interaction), enabled = enabled,
        shape = MaterialTheme.shapes.medium, border = cardOutline(), interactionSource = interaction,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
            IconTile(icon, Modifier.size(40.dp),
                if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f))
            Text(title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TodayLink(section: String?, title: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, shape = MaterialTheme.shapes.medium, border = cardOutline(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon, Modifier.size(40.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (section != null) Text(section, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium.tabularNumbers())
                Text(detail, style = MaterialTheme.typography.bodySmall.tabularNumbers(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
