package ro.camara.app.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

/** Pure projection rules, shared by the durable outbox and the UI. */
object ActionRules {
    val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true }
    inline fun <reified T> record(value: T): JsonObject = json.encodeToJsonElement(value).jsonObject
    fun records(snapshot: Snapshot, kind: String): List<JsonObject> = when (kind) {
        "pantry" -> snapshot.pantry.map { record(it) }
        "shopping" -> snapshot.shopping.map { record(it) }
        "plans" -> snapshot.plans.map { record(it) }
        "expenses" -> snapshot.expenses.map { record(it) }
        "events" -> snapshot.events.map { record(it) }
        "budgets" -> snapshot.budgets.map { record(it) }
        "products" -> snapshot.products.map { record(it) }
        "personal_recipes" -> snapshot.personal_recipes.map { record(it) }
        "receipts" -> snapshot.receipts.map { record(it) }
        "prepared" -> snapshot.prepared.map { record(it) }
        "purchases" -> snapshot.purchases.map { record(it) }
        "stock_minimums" -> snapshot.stock_minimums.map { record(it) }
        else -> error("Tip de date necunoscut.")
    }
    fun find(snapshot: Snapshot, kind: String, id: String) = records(snapshot, kind).firstOrNull { it["id"]?.jsonPrimitive?.content == id }
    fun change(snapshot: Snapshot, kind: String, id: String, after: JsonObject?) = Change(kind, id, find(snapshot, kind, id), after)
    fun action(label: String, changes: List<Change>, rebasable: Boolean = true, guards: Map<String, List<JsonObject>> = emptyMap()) = PendingAction(UUID.randomUUID().toString(), Mutation(label.take(100), changes, guards), rebasable)
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
