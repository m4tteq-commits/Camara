package ro.camara.app.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PantryInput(
    val name: String,
    val quantity: Double,
    val unit: String = "buc",
    val location: String = "frigider",
    val expires_on: String? = null,
)

@Serializable
data class PantryItem(
    val id: String,
    val name: String,
    val quantity: Double,
    val unit: String = "buc",
    val location: String = "frigider",
    val expires_on: String? = null,
) {
    fun input() = PantryInput(name, quantity, unit, location, expires_on)
}

@Serializable
data class StockMinimum(val id: String, val name: String, val quantity: Double, val unit: String)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ShoppingInput(val name: String, val quantity: String = "", val checked: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val aisle: String = "")

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ShoppingItem(val id: String, val name: String, val quantity: String = "", val checked: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val aisle: String = "") {
    fun input() = ShoppingInput(name, quantity, checked, aisle)
}

@Serializable
data class Ingredient(val name: String, val quantity: String)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class Recipe(
    val id: String,
    val title: String,
    val minutes: Int,
    val servings: Int,
    val ingredients: List<Ingredient>,
    val steps: List<String>,
    val missing_ingredients: List<Ingredient> = emptyList(),
    val model: String,
    val generated_at: String,
    val generation_ms: Int,
    val selection_cached: Boolean = false,
    val request_key: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val expiring_ingredients: List<ExpiringIngredient> = emptyList(),
)

@Serializable
data class ExpiringIngredient(val name: String, val expires_on: String)

@Serializable
data class UseSoonIdea(val recipe: Recipe, val foods: List<String>)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class RecipeRequest(
    val max_minutes: Int = 30,
    val servings: Int = 1,
    val equipment: List<String> = listOf("plită", "tigaie"),
    val preferences: String = "",
    val pantry_only: Boolean = false,
    val diet: String = "any",
    val category: String = "any",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val required_ingredients: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val excluded_ingredients: List<String> = emptyList(),
)

@Serializable
data class Snapshot(
    val pantry: List<PantryItem> = emptyList(),
    val shopping: List<ShoppingItem> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val plans: List<MealPlan> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val events: List<FoodEvent> = emptyList(),
    val budgets: List<Budget> = emptyList(),
    val aliases: Map<String, String> = emptyMap(),
    val products: List<Product> = emptyList(),
    val personal_recipes: List<PersonalRecipe> = emptyList(),
    val receipts: List<ReceiptRecord> = emptyList(),
    val prepared: List<PreparedBatch> = emptyList(),
    val purchases: List<Purchase> = emptyList(),
    val stock_minimums: List<StockMinimum> = emptyList(),
    val stock_tools: Boolean = false,
    val epoch: String = "",
    val restored_at: String? = null,
)

@Serializable
data class AiStatus(val available: Boolean, val model: String, val detail: String)

internal val cloudAiLabels = mapOf("groq" to "GroqCloud", "openrouter" to "OpenRouter")

@Serializable
data class BulkTextRequest(val text: String)
@Serializable
data class BulkPreviewRow(val item: PantryInput, val warnings: List<String> = emptyList())
@Serializable
data class BulkPreview(val rows: List<BulkPreviewRow>)
@Serializable
data class PantryBatch(val items: List<PantryItem>)
@Serializable
data class RecipeOption(val id: String, val title: String, val missing_count: Int)
@Serializable
data class DeviceOptions(val fingerprint: String, val prompt: String, val options: List<RecipeOption>, val allow_none: Boolean = false)
@Serializable
data class DeviceChoice(val request: RecipeRequest, val fingerprint: String, val recipe_id: String,
    val id: String, val generation_ms: Int, val selection_cached: Boolean = false)
@Serializable
data class DeviceModelInfo(val available: Boolean, val name: String, val bytes: Long, val sha256: String, val path: String)

@Serializable
data class MealPlan(val id: String, val on_date: String, val slot: String = "dinner", val recipe: Recipe, val servings: Int, val completed: Boolean = false, val prepared_batch_id: String? = null)
@Serializable
data class Expense(val id: String, val label: String, val amount_cents: Int, val spent_on: String, val category: String = "Alimente")
@Serializable
data class Budget(val id: String, val month: String, val limit_cents: Int = 0)
@Serializable
data class StockUse(val item: PantryItem, val quantity: Double)
@Serializable
data class FoodEvent(val id: String, val kind: String, val title: String, val occurred_on: String,
    val allocations: List<StockUse>, val value_cents: Int? = null, val reason: String = "", val undone: Boolean = false, val plan_id: String? = null,
    val batch_id: String? = null, val portions: Int? = null)
@Serializable
data class Change(val kind: String, val id: String, val before: JsonObject? = null, val after: JsonObject? = null)
@Serializable
data class Mutation(val label: String, val changes: List<Change>, val guards: Map<String, List<JsonObject>> = emptyMap(), val epoch: String = "")
@Serializable
data class PendingAction(val id: String, val mutation: Mutation, val rebasable: Boolean = true, val issue: String? = null)
@Serializable
data class LocalState(val server: Snapshot = Snapshot(), val savedAt: Long? = null, val pending: List<PendingAction> = emptyList(), val revision: Long = 0) {
    fun projected() = pending.asSequence().filterNot { staleEpoch(it, server) }.takeWhile { it.issue == null }
        .fold(server) { result, action -> ActionRules.apply(result, action.mutation) }
}

fun staleEpoch(action: PendingAction, server: Snapshot) = action.mutation.epoch != server.epoch &&
    (action.mutation.epoch.isNotEmpty() || server.restored_at != null)

@Serializable
internal data class MutationResult(val replayed: Boolean = false)

@Serializable
data class Product(val id: String, val name: String, val barcode: String = "", val package_quantity: Double,
    val unit: String = "buc", val location: String = "frigider", val receipt_names: List<String> = emptyList())
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class PersonalRecipe(val id: String, val title: String, val minutes: Int, val servings: Int,
    val ingredients: List<Ingredient>, val steps: List<String>, val equipment: List<String> = emptyList(),
    val diet: String = "any", val category: String = "main", val updated_at: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val minutes_estimated: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val favorite: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val collections: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val notes: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val actual_minutes: Int? = null) {
    fun recipe() = Recipe(id, title, minutes, servings, ingredients, steps, model = "Rețeta mea", generated_at = updated_at, generation_ms = 0)
}
fun Snapshot.allRecipes() = (personal_recipes.map { it.recipe() } + recipes + plans.map { it.recipe } + prepared.map { it.recipe }).distinctBy { it.id }
fun Snapshot.reminderItems() = pantry + prepared.filter { it.remaining > 0 }.map { PantryItem("prepared:${it.id}", "Gătit · ${it.recipe.title}", it.remaining.toDouble(), "buc", it.location, it.expires_on) }
@Serializable
data class ReceiptLine(val name: String, val quantity: Double = 1.0, val unit: String = "buc", val location: String = "frigider",
    val line_cents: Int? = null, val include_pantry: Boolean = true, val warnings: List<String> = emptyList())
@Serializable
data class ReceiptPreview(val image_sha256: String, val merchant: String, val spent_on: String? = null,
    val total_cents: Int? = null, val lines: List<ReceiptLine> = emptyList(), val raw_text: String,
    val warnings: List<String> = emptyList())
@Serializable
data class ReceiptRecord(val id: String, val image_sha256: String, val merchant: String, val spent_on: String,
    val total_cents: Int, val expense_id: String, val imported_at: String, val lines: List<ReceiptLine> = emptyList())
@Serializable
data class BackupSettings(val enabled: Boolean = true, val hour: Int = 3, val minute: Int = 0, val keep_daily: Int = 14)
@Serializable
data class BackupInfo(val id: String, val created_at: String, val kind: String, val size_bytes: Long, val sha256: String, val counts: Map<String, Int>)
@Serializable
data class BackupOverview(val settings: BackupSettings, val backups: List<BackupInfo>, val error: String? = null)
@Serializable
data class RestorePreview(val backup: BackupInfo, val current_counts: Map<String, Int>, val fingerprint: String)
@Serializable
data class RestoreRequest(val request_id: String, val expected_fingerprint: String, val backup_sha256: String, val confirmation: String = "RESTAUREAZĂ")

@Serializable
data class PreparedBatch(val id: String, val recipe: Recipe, val cooked_on: String, val expires_on: String,
    val location: String = "frigider", val total: Int, val remaining: Int)
data class BatchDetails(val cookedOn: String, val expiresOn: String, val location: String)
@Serializable
data class PurchaseLine(val name: String, val quantity: Double, val unit: String, val location: String,
    val line_cents: Int, val include_pantry: Boolean, val warnings: List<String> = emptyList(), val shopping_id: String? = null,
    val pantry_id: String? = null, val expires_on: String? = null)
@Serializable
data class Purchase(val id: String, val merchant: String, val spent_on: String, val total_cents: Int, val expense_id: String, val lines: List<PurchaseLine>)
@Serializable
data class CartRow(val id: String, val name: String, val quantity: String = "1", val unit: String = "buc", val price: String = "",
    val location: String = "camara", val expires: String = "", val selected: Boolean = true, val includePantry: Boolean = true,
    val shoppingId: String? = null, val shoppingBefore: ShoppingItem? = null, val aisle: String = shoppingBefore?.aisle.orEmpty())
@Serializable
data class ShoppingCart(val id: String, val merchant: String = "Magazin", val date: String, val rows: List<CartRow>,
    val revision: Long = 0, val epoch: String = "")
@Serializable
data class PlanPrice(val name: String, val quantity: Double, val unit: String, val cents: Int)
@Serializable
data class WeekRequest(val id: String, val start: String, val days: Int = 7, val slots: List<String> = listOf("dinner"), val servings: Int = 1,
    val budget_cents: Int, val max_minutes: Int = 30, val equipment: List<String> = listOf("plita", "tigaie", "oala"), val diet: String = "any",
    val preferences: String = "", val use_ai: Boolean = true, val seed: String = "", val prices: List<PlanPrice> = emptyList(), val exclude: List<String> = emptyList())
@Serializable
data class PlanCost(val name: String, val quantity: Double, val unit: String, val buy_quantity: Double, val package_quantity: Double,
    val package_cents: Int? = null, val estimated_cents: Int? = null, val source: String)
@Serializable
data class WeekPreview(val meals: List<MealPlan>, val lines: List<PlanCost>, val total_cents: Int? = null, val known_cents: Int, val budget_cents: Int,
    val warnings: List<String>, val ai_used: Boolean, val seed: String, val action: Mutation? = null, val existing_meals: Int, val unfilled: Int)
