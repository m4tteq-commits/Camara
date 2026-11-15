package ro.camara.app.data

import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDate
import java.util.UUID
import kotlin.math.min
import kotlin.math.max
import kotlin.math.round
import kotlinx.serialization.json.JsonObject

data class CookingPreview(val uses: List<StockUse>, val warnings: List<String>, val missing: List<Ingredient> = emptyList())
data class ShoppingPreview(val changes: List<Change>, val lines: List<String>, val warnings: List<String>, val guards: Map<String, List<JsonObject>> = emptyMap())

object RecipeMath {
    private val diacritics = Regex("\\p{M}+")
    private val whitespace = Regex("\\s+")
    private val quantityPattern = Regex("^\\s*([0-9]+(?:[.,][0-9]+)?)\\s*(buc|kg|g|ml|l)\\s*$", RegexOption.IGNORE_CASE)

    fun normalize(value: String) = Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
        .replace(diacritics, "").trim().replace(whitespace, " ")
    fun canonical(value: String, aliases: Map<String, String>) = normalize(value).let { aliases[it] ?: it }
    fun factor(unit: String) = if (unit == "kg" || unit == "l") 1000.0 else 1.0
    fun baseUnit(unit: String) = when (unit) { "kg" -> "g"; "l" -> "ml"; else -> unit }
    fun amount(value: String): Pair<Double, String>? {
        val m = quantityPattern.matchEntire(value) ?: return null
        val number = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase(java.util.Locale.ROOT)
        val quantity = number * factor(unit)
        return if (quantity.isFinite()) quantity to baseUnit(unit) else null
    }
    fun number(value: Double): String {
        if (!value.isFinite()) return value.toString()
        val rounded = round(value * 10000) / 10000
        val display = if (!rounded.isFinite() || (rounded == 0.0 && value != 0.0)) value else rounded
        return BigDecimal.valueOf(display).stripTrailingZeros().toPlainString()
    }
    private fun eligible(item: PantryItem, date: LocalDate) = item.expires_on == null || item.expires_on >= date.toString()
    private fun stockByIngredient(snapshot: Snapshot) = snapshot.pantry
        .sortedWith(compareBy<PantryItem> { it.expires_on == null }.thenBy { it.expires_on }.thenBy { it.id })
        .groupBy { canonical(it.name, snapshot.aliases) to baseUnit(it.unit) }

    fun expiring(items: List<PantryItem>, today: LocalDate = LocalDate.now()): List<PantryItem> {
        val from = today.toString()
        val until = today.plusDays(3).toString()
        return items.filter { it.expires_on != null && it.expires_on in from..until }.sortedBy { it.expires_on }
    }

    fun expiringSavedIdeas(snapshot: Snapshot, today: LocalDate = LocalDate.now()): List<UseSoonIdea> {
        val due = expiring(snapshot.pantry, today).associateBy { it.id }
        if (due.isEmpty()) return emptyList()
        return snapshot.allRecipes().mapNotNull { recipe ->
            val uses = cooking(snapshot, recipe, recipe.servings, today)
            val foods = uses.uses.mapNotNull { due[it.item.id]?.name }.distinct()
            if (foods.isEmpty()) null else UseSoonIdea(recipe.copy(model = "Din cămară", missing_ingredients = uses.missing), foods) to uses.warnings.size
        }.sortedWith(compareBy<Pair<UseSoonIdea, Int>> { it.second }.thenByDescending { it.first.foods.size }.thenBy { it.first.recipe.minutes })
            .take(8).map { it.first }
    }

    fun cooking(snapshot: Snapshot, recipe: Recipe, servings: Int, today: LocalDate = LocalDate.now()): CookingPreview {
        require(servings in 1..8)
        val remaining = snapshot.pantry.associate { it.id to it.quantity }.toMutableMap()
        val used = linkedMapOf<String, StockUse>()
        val warnings = mutableListOf<String>()
        val missing = mutableListOf<Ingredient>()
        val stock = stockByIngredient(snapshot)
        for (part in recipe.ingredients) {
            val key = canonical(part.name, snapshot.aliases)
            if (key == "apa") continue
            val parsed = amount(part.quantity)
            if (parsed == null) {
                warnings += "${part.name} · ${part.quantity}. Cantitatea nu se scade automat din inventar."
                missing += part
                continue
            }
            var need = parsed.first * servings / recipe.servings
            for (item in stock[key to parsed.second].orEmpty()) {
                if (need <= 0.000001) break
                if (!eligible(item, today)) continue
                val taken = min(remaining.getValue(item.id) * factor(item.unit), need)
                if (taken <= 0.000001) continue
                val quantity = taken / factor(item.unit)
                used[item.id] = StockUse(item, (used[item.id]?.quantity ?: 0.0) + quantity)
                remaining[item.id] = remaining.getValue(item.id) - quantity
                need = max(0.0, need - taken)
            }
            if (need > 0.000001) {
                val quantity = "${number(need)} ${parsed.second}"
                missing += Ingredient(part.name, quantity)
                warnings += "${part.name}: $quantity nu sunt disponibile în unitatea potrivită; nu le scad din inventar."
            }
        }
        return CookingPreview(used.values.toList(), warnings, missing)
    }

    fun consume(snapshot: Snapshot, title: String, uses: List<StockUse>, wasted: Boolean = false,
                reason: String = "", cents: Int? = null, today: LocalDate = LocalDate.now(), planId: String? = null,
                quickUse: Boolean = false): PendingAction {
        require(uses.size in 1..40 && uses.map { it.item.id }.distinct().size == uses.size) { "Alege între 1 și 40 de loturi diferite pentru consum." }
        require(!quickUse || (uses.size == 1 && !wasted && planId == null && eligible(uses.single().item, today))) {
            "Pentru consumul rapid, alege un aliment cu termen valabil."
        }
        val accepted = uses.map { use ->
            val current = snapshot.pantry.firstOrNull { it.id == use.item.id }
            require(current == use.item) { "Inventarul s-a schimbat. Redeschide previzualizarea." }
            require(use.quantity.isFinite() && use.quantity > 0 && use.quantity <= current.quantity + 0.000001) { "Cantitatea depășește stocul disponibil." }
            use.copy(quantity = min(use.quantity, current.quantity))
        }
        val changes = accepted.map { use ->
            val current = use.item
            val left = current.quantity - use.quantity
            Change("pantry", current.id, ActionRules.record(current), if (left == 0.0) null else ActionRules.record(current.copy(quantity = left)))
        }.toMutableList()
        val kind = when { quickUse -> "used"; wasted -> "wasted"; else -> "cooked" }
        val label = when { quickUse -> "Am folosit"; wasted -> "Am aruncat"; else -> "Am gătit" }
        val event = FoodEvent(UUID.randomUUID().toString(), kind, title.take(100), today.toString(), accepted, cents, reason.take(200), plan_id = planId)
        changes += Change("events", event.id, after = ActionRules.record(event))
        if (planId != null) {
            val plan = snapshot.plans.firstOrNull { it.id == planId }
            require(plan != null && !plan.completed) { "Masa a fost deja gătită sau eliminată din plan." }
            changes += ActionRules.change(snapshot, "plans", plan.id, ActionRules.record(plan.copy(completed = true)))
        }
        return ActionRules.action("$label · $title", changes, rebasable = false)
    }

    fun undo(snapshot: Snapshot, eventId: String): PendingAction {
        val event = snapshot.events.firstOrNull { it.id == eventId }
        require(event != null && !event.undone) { "Această înregistrare a fost deja anulată." }
        val changes = event.allocations.map { use ->
            val current = snapshot.pantry.firstOrNull { it.id == use.item.id }
            require(current == null || current.copy(quantity = use.item.quantity) == use.item) { "Alimentul a fost schimbat între timp. Restabilește cantitatea manual." }
            val restored = (current ?: use.item.copy(quantity = 0.0)).copy(quantity = (current?.quantity ?: 0.0) + use.quantity)
            require(restored.quantity <= 100000)
            ActionRules.change(snapshot, "pantry", restored.id, ActionRules.record(restored))
        }.toMutableList()
        event.batch_id?.let { id ->
            val batch = snapshot.prepared.firstOrNull { it.id == id } ?: error("Lotul gătit nu mai există.")
            if (event.kind == "prepared") {
                require(batch.remaining == batch.total && snapshot.plans.none { !it.completed && it.prepared_batch_id == id }) { "Anulează întâi consumul porțiilor și rezervările din plan." }
                changes += ActionRules.change(snapshot, "prepared", id, null)
            } else {
                val next = batch.remaining + (event.portions ?: 0)
                require(next <= batch.total)
                changes += ActionRules.change(snapshot, "prepared", id, ActionRules.record(batch.copy(remaining = next)))
            }
        }
        changes += ActionRules.change(snapshot, "events", event.id, ActionRules.record(event.copy(undone = true)))
        snapshot.plans.firstOrNull { it.id == event.plan_id && it.completed }?.let { plan ->
            changes += ActionRules.change(snapshot, "plans", plan.id, ActionRules.record(plan.copy(completed = false)))
        }
        return ActionRules.action("Anulează: ${event.title}", changes, rebasable = false,
            guards = if (event.batch_id != null) mapOf("plans" to ActionRules.records(snapshot, "plans")) else emptyMap())
    }

    fun shopping(snapshot: Snapshot, meals: List<MealPlan>, today: LocalDate = LocalDate.now()): ShoppingPreview {
        val remaining = snapshot.pantry.associate { it.id to it.quantity * factor(it.unit) }.toMutableMap()
        val stock = stockByIngredient(snapshot)
        val needed = linkedMapOf<Pair<String, String>, Pair<String, Double>>()
        val unmeasured = linkedMapOf<String, Ingredient>()
        val warnings = mutableListOf<String>()
        for (meal in meals.filter { !it.completed && it.prepared_batch_id == null }.sortedWith(compareBy<MealPlan> { it.on_date }.thenBy { it.slot })) {
            val date = maxOf(today, LocalDate.parse(meal.on_date))
            for (part in meal.recipe.ingredients) {
                val key = canonical(part.name, snapshot.aliases)
                if (key == "apa") continue
                val parsed = amount(part.quantity)
                if (parsed == null) {
                    warnings += "${part.name} · ${part.quantity}. Cantitatea nu intră în calculul stocului."
                    if (stock.none { (identity, items) -> identity.first == key && items.any { eligible(it, date) } }) {
                        unmeasured.putIfAbsent(key, part)
                    }
                    continue
                }
                var need = parsed.first * meal.servings / meal.recipe.servings
                for (item in stock[key to parsed.second].orEmpty()) {
                    if (need <= 0.000001) break
                    if (!eligible(item, date)) continue
                    val taken = min(need, remaining.getValue(item.id))
                    need -= taken
                    remaining[item.id] = remaining.getValue(item.id) - taken
                }
                if (need > 0.000001) {
                    val identity = key to parsed.second
                    needed[identity] = part.name to ((needed[identity]?.second ?: 0.0) + need)
                }
            }
        }
        val changes = mutableListOf<Change>()
        val lines = mutableListOf<String>()
        for ((key, required) in needed) {
            val existing = snapshot.shopping.filter { !it.checked && canonical(it.name, snapshot.aliases) == key.first }
            val compatible = existing.filter { amount(it.quantity)?.second == key.second }
            val already = compatible.sumOf { amount(it.quantity)!!.first }
            if (already + 0.000001 >= required.second) continue
            if (existing.isNotEmpty() && compatible.isEmpty()) {
                warnings += "${required.first}: există deja pe listă într-o cantitate neclară; verifică ambele poziții."
            }
            val item = compatible.firstOrNull()
            val addition = required.second - already
            val next = if (item != null) item.copy(quantity = "${number(amount(item.quantity)!!.first + addition)} ${key.second}")
                else ShoppingItem(UUID.randomUUID().toString(), required.first, "${number(addition)} ${key.second}")
            changes += ActionRules.change(snapshot, "shopping", next.id, ActionRules.record(next))
            lines += "${next.name} · ${next.quantity}"
        }
        for ((key, part) in unmeasured) {
            if (needed.keys.any { it.first == key } || snapshot.shopping.any { !it.checked && canonical(it.name, snapshot.aliases) == key }) continue
            val item = ShoppingItem(UUID.randomUUID().toString(), part.name, part.quantity.takeIf { it.length <= 80 }.orEmpty())
            changes += ActionRules.change(snapshot, "shopping", item.id, ActionRules.record(item))
            lines += "${part.name} · ${part.quantity}"
        }
        val guards = if (changes.isEmpty()) emptyMap() else listOf("pantry", "plans", "shopping").associateWith { ActionRules.records(snapshot, it) }
        return ShoppingPreview(changes, lines, warnings.distinct(), guards)
    }
}
