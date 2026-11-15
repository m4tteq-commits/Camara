package ro.camara.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlinx.serialization.encodeToString
import ro.camara.app.ui.parseMoney

class RecipeMathTest {
    private val today = LocalDate.of(2026, 11, 1)
    private fun recipe(amount: String = "100 g") = Recipe("recipe", "Orez", 10, 1, listOf(Ingredient("Orez", amount)), listOf("Fierbe orezul."), model = "test", generated_at = "", generation_ms = 0)
    private fun item(id: String, quantity: Double, unit: String = "g", expiry: String? = null) = PantryItem(id, "Orez", quantity, unit, expires_on = expiry)

    @Test fun cookingConvertsUnitsScalesServingsAndUsesSoonestExpiry() {
        val late = item("late", 1.0, "kg", "2026-11-10")
        val soon = item("soon", 150.0, "g", "2026-11-02")
        val expired = item("old", 1000.0, "g", "2026-10-31")
        val preview = RecipeMath.cooking(Snapshot(pantry = listOf(late, expired, soon)), recipe(), 2, today)
        assertEquals(listOf("soon", "late"), preview.uses.map { it.item.id })
        assertEquals(150.0, preview.uses[0].quantity, 0.000001)
        assertEquals(0.05, preview.uses[1].quantity, 0.000001)
        assertTrue(preview.warnings.isEmpty())
    }
    @Test fun incompatiblePackagesAreWarnedAndNeverGuessed() {
        val preview = RecipeMath.cooking(Snapshot(pantry = listOf(item("pack", 2.0, "buc"))), recipe(), 1, today)
        assertTrue(preview.uses.isEmpty())
        assertTrue(preview.warnings.isNotEmpty())
    }
    @Test fun undoAddsOnlyConsumedQuantityAndPreservesLaterStockAdditions() {
        val original = item("one", 300.0)
        val snapshot = Snapshot(pantry = listOf(original))
        val consumed = ActionRules.apply(snapshot, RecipeMath.consume(snapshot, "Prânz", listOf(StockUse(original, 100.0)), today = today).mutation)
        val restocked = consumed.copy(pantry = listOf(original.copy(quantity = 500.0)))
        val undone = ActionRules.apply(restocked, RecipeMath.undo(restocked, consumed.events.single().id).mutation)
        assertEquals(600.0, undone.pantry.single().quantity, 0.000001)
        assertTrue(undone.events.single().undone)
        assertThrows(IllegalArgumentException::class.java) { RecipeMath.undo(undone, consumed.events.single().id) }
    }
    @Test fun fullConsumptionCanBeRestoredAndPlanCompletionReversed() {
        val original = item("one", 100.0)
        val plan = MealPlan("plan", today.toString(), recipe = recipe(), servings = 1)
        val snapshot = Snapshot(pantry = listOf(original), plans = listOf(plan))
        val consumed = ActionRules.apply(snapshot, RecipeMath.consume(snapshot, "Prânz", listOf(StockUse(original, 100.0)), today = today, planId = plan.id).mutation)
        assertTrue(consumed.pantry.isEmpty())
        assertTrue(consumed.plans.single().completed)
        val undone = ActionRules.apply(consumed, RecipeMath.undo(consumed, consumed.events.single().id).mutation)
        assertEquals(original, undone.pantry.single())
        assertFalse(undone.plans.single().completed)
    }
    @Test fun weeklyShoppingReservesStockOnceAndDoesNotDuplicateExistingList() {
        val snapshot = Snapshot(pantry = listOf(item("stock", 150.0)))
        val meals = listOf(MealPlan("p1", "2026-11-02", recipe = recipe(), servings = 1), MealPlan("p2", "2026-11-03", recipe = recipe(), servings = 1))
        val preview = RecipeMath.shopping(snapshot, meals, today)
        val projected = ActionRules.apply(snapshot, Mutation("Listă", preview.changes))
        assertEquals("50 g", projected.shopping.single().quantity)
        assertTrue(RecipeMath.shopping(projected, meals, today).changes.isEmpty())
    }
    @Test fun mealDayExpiryAndCompletedMealsAffectShopping() {
        val snapshot = Snapshot(pantry = listOf(item("stock", 1000.0, expiry = "2026-11-02")))
        val meals = listOf(MealPlan("p1", "2026-11-03", recipe = recipe(), servings = 1), MealPlan("done", "2026-11-04", recipe = recipe(), servings = 1, completed = true))
        val preview = RecipeMath.shopping(snapshot, meals, today)
        val projected = ActionRules.apply(snapshot, Mutation("Listă", preview.changes))
        assertEquals("100 g", projected.shopping.single().quantity)
    }
    @Test fun expiringSuggestionShowsMissingIngredientsFromCurrentStock() {
        val pantry = listOf(item("soon", 40.0, expiry = "2026-11-02"))
        val snapshot = Snapshot(pantry = pantry, recipes = listOf(recipe()))
        val suggestion = RecipeMath.expiringSavedIdeas(snapshot, today).single()
        assertEquals(listOf("Orez"), suggestion.foods)
        assertEquals(listOf(Ingredient("Orez", "60 g")), suggestion.recipe.missing_ingredients)
    }
    @Test fun smallQuantityRoundTripsAndPartialConsumptionDoesNotEraseTheLot() {
        assertEquals(0.0001 to "g", RecipeMath.amount("${RecipeMath.number(0.0001)} g"))
        val original = item("small", 0.0000009)
        val snapshot = Snapshot(pantry = listOf(original))
        val action = RecipeMath.consume(snapshot, "Orez", listOf(StockUse(original, 0.0000003)), today = today)
        val remaining = ActionRules.apply(snapshot, action.mutation).pantry.single()
        assertEquals(0.0000006, remaining.quantity, 1e-12)
    }
    @Test fun queuedChangesSurviveSerializationAndKeepOrder() {
        val original = item("one", 100.0)
        val first = ActionRules.action("Adaugă", listOf(Change("pantry", original.id, after = ActionRules.record(original))))
        val next = original.copy(quantity = 50.0)
        val second = ActionRules.action("Editează", listOf(Change("pantry", original.id, ActionRules.record(original), ActionRules.record(next))))
        val state = LocalState(pending = listOf(first, second))
        val loaded = ActionRules.json.decodeFromString<LocalState>(ActionRules.json.encodeToString(state))
        assertEquals(next, loaded.projected().pantry.single())
        assertEquals(2, loaded.pending.size)
    }
    @Test fun moneyUsesExactCentsAndRejectsFractionalCentsOrOverflow() {
        assertEquals(1234, parseMoney("12,34"))
        assertEquals(10, parseMoney("0.10"))
        assertNull(parseMoney("1.234"))
        assertNull(parseMoney("-1"))
        assertNull(parseMoney("999999999999"))
    }
}
