package ro.camara.app.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

/** Pure projection rules, shared by the durable outbox and the UI. */
object ActionRules {
    val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true }
    inline fun <reified T> record(value: T): JsonObject = json.encodeToJsonElement(value).jsonObject
    private inline fun <reified T> records(rows: List<T>, ids: Set<String>?, crossinline id: (T) -> String): List<JsonObject> =
        if (ids == null) rows.map { record(it) }
        else rows.asSequence().filter { id(it) in ids }.map { record(it) }.toList()

    fun records(snapshot: Snapshot, kind: String, ids: Set<String>? = null): List<JsonObject> = when (kind) {
        "pantry" -> records(snapshot.pantry, ids, PantryItem::id)
        "shopping" -> records(snapshot.shopping, ids, ShoppingItem::id)
        "plans" -> records(snapshot.plans, ids, MealPlan::id)
        "expenses" -> records(snapshot.expenses, ids, Expense::id)
        "events" -> records(snapshot.events, ids, FoodEvent::id)
        "budgets" -> records(snapshot.budgets, ids, Budget::id)
        "products" -> records(snapshot.products, ids, Product::id)
        "personal_recipes" -> records(snapshot.personal_recipes, ids, PersonalRecipe::id)
        "receipts" -> records(snapshot.receipts, ids, ReceiptRecord::id)
        "prepared" -> records(snapshot.prepared, ids, PreparedBatch::id)
        "purchases" -> records(snapshot.purchases, ids, Purchase::id)
        "stock_minimums" -> records(snapshot.stock_minimums, ids, StockMinimum::id)
        else -> error("Tip de date necunoscut.")
    }
    fun find(snapshot: Snapshot, kind: String, id: String) = records(snapshot, kind, setOf(id)).firstOrNull()
    fun change(snapshot: Snapshot, kind: String, id: String, after: JsonObject?) = Change(kind, id, find(snapshot, kind, id), after)
    internal fun newActionId(now: Long = System.currentTimeMillis()): String {
        val random = UUID.randomUUID()
        val high = ((now and 0xffffffffffffL) shl 16) or 0x7000L or (random.mostSignificantBits and 0xfffL)
        return UUID(high, random.leastSignificantBits).toString()
    }

    fun action(label: String, changes: List<Change>, rebasable: Boolean = true, guards: Map<String, List<JsonObject>> = emptyMap()) =
        PendingAction(newActionId(), Mutation(label.take(100), changes, guards), rebasable)
    fun apply(snapshot: Snapshot, mutation: Mutation): Snapshot = mutation.changes.groupBy { it.kind }.entries.fold(snapshot) { current, (kind, changes) ->
        when (kind) {
            "pantry" -> current.copy(pantry = updated(current.pantry, changes, PantryItem::id))
            "shopping" -> current.copy(shopping = updated(current.shopping, changes, ShoppingItem::id))
            "plans" -> current.copy(plans = updated(current.plans, changes, MealPlan::id))
            "expenses" -> current.copy(expenses = updated(current.expenses, changes, Expense::id))
            "events" -> current.copy(events = updated(current.events, changes, FoodEvent::id))
            "budgets" -> current.copy(budgets = updated(current.budgets, changes, Budget::id))
            "products" -> current.copy(products = updated(current.products, changes, Product::id))
            "personal_recipes" -> current.copy(personal_recipes = updated(current.personal_recipes, changes, PersonalRecipe::id))
            "receipts" -> current.copy(receipts = updated(current.receipts, changes, ReceiptRecord::id))
            "prepared" -> current.copy(prepared = updated(current.prepared, changes, PreparedBatch::id))
            "purchases" -> current.copy(purchases = updated(current.purchases, changes, Purchase::id))
            "stock_minimums" -> current.copy(stock_minimums = updated(current.stock_minimums, changes, StockMinimum::id))
            else -> error("Tip de date necunoscut.")
        }
    }

    private inline fun <reified T : Any> updated(values: List<T>, changes: List<Change>, id: (T) -> String): List<T> {
        val latest = LinkedHashMap<String, JsonObject?>()
        for (change in changes) {
            latest.remove(change.id)
            latest[change.id] = change.after
        }
        return values.filterNot { id(it) in latest } + latest.values.mapNotNull { it?.let { record -> json.decodeFromJsonElement<T>(record) } }
    }
}
