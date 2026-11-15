package ro.camara.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlinx.serialization.json.*

class DailyFlowsTest {
    private val today = LocalDate.now().toString()
    private val recipe = Recipe("recipe", "Paste", 15, 1, listOf(Ingredient("Paste", "100 g")), listOf("Fierbe pastele."), model = "test", generated_at = "", generation_ms = 0)
    @Test fun backendPlanGuardsSurviveNativeSerializationWithoutDroppingFields() {
        val fixture = checkNotNull(javaClass.classLoader!!.getResourceAsStream("week-contract.json")).bufferedReader().use { it.readText() }
        val preview = ActionRules.json.decodeFromString<WeekPreview>(fixture)
        val guards = checkNotNull(preview.action).guards
        val snapshot = ActionRules.json.decodeFromJsonElement<Snapshot>(JsonObject(guards.mapValues { JsonArray(it.value) }))
        for ((kind, expected) in guards) assertEquals("Backend/native guard mismatch: $kind", expected.toSet(), ActionRules.records(snapshot, kind).toSet())
        assertNotNull(ActionRules.record(snapshot.plans.single())["recipe"]!!.jsonObject["request_key"])
    }
    @Test fun checkoutCombinesStockExpenseAndListInOneStableAction() {
        val item = ShoppingItem("shopping", "Lapte", "2 l")
        val snapshot = Snapshot(shopping = listOf(item))
        val cart = CheckoutRules.start(snapshot).copy(rows = listOf(CartRow("row", "Lapte", "2", "l", "12,50", shoppingId = item.id)))
        val action = CheckoutRules.checkout(snapshot, cart)
        val repeated = CheckoutRules.checkout(snapshot, cart)
        assertEquals(action, repeated)
        val next = ActionRules.apply(snapshot, action.mutation)
        assertEquals(2.0, next.pantry.single().quantity, 0.001)
        assertEquals(1250, next.expenses.single().amount_cents)
        assertEquals(1250, next.purchases.single().total_cents)
        assertTrue(next.shopping.single().checked)
    }
    @Test fun checkoutRejectsUnknownPriceAndPreviouslyPurchasedTrip() {
        val cart = ShoppingCart("cart", date = today, rows = listOf(CartRow("row", "Lapte")))
        assertThrows(IllegalStateException::class.java) { CheckoutRules.checkout(Snapshot(), cart) }
        val valid = cart.copy(rows = cart.rows.map { it.copy(price = "5") })
        val next = ActionRules.apply(Snapshot(), CheckoutRules.checkout(Snapshot(), valid).mutation)
        assertThrows(IllegalArgumentException::class.java) { CheckoutRules.checkout(next, valid) }
    }
    @Test fun cookingCreatesPortionsAndConsumesIngredientsOnlyOnce() {
        val stock = PantryItem("stock", "Paste", 1.0, "kg")
        val snapshot = Snapshot(pantry = listOf(stock))
        val action = PreparedRules.cook(snapshot, recipe, 4, listOf(StockUse(stock, .4)), BatchDetails(today, LocalDate.now().plusDays(1).toString(), "frigider"))
        val cooked = ActionRules.apply(snapshot, action.mutation)
        assertEquals(.6, cooked.pantry.single().quantity, .0001)
        val batch = cooked.prepared.single()
        val served = ActionRules.apply(cooked, PreparedRules.serve(cooked, batch, 1, false).mutation)
        assertEquals(3, served.prepared.single().remaining)
        assertEquals(.6, served.pantry.single().quantity, .0001)
        val restored = ActionRules.apply(served, RecipeMath.undo(served, served.events.first { it.kind == "served" }.id).mutation)
        assertEquals(4, restored.prepared.single().remaining)
        assertEquals(.6, restored.pantry.single().quantity, .0001)
    }
    @Test fun reservedPortionsCannotBeEatenOutsideTheirPlannedMeal() {
        val batch = PreparedBatch("batch", recipe, today, today, total = 2, remaining = 2)
        val plan = MealPlan("plan", today, recipe = recipe, servings = 2, prepared_batch_id = batch.id)
        val snapshot = Snapshot(prepared = listOf(batch), plans = listOf(plan))
        assertThrows(IllegalArgumentException::class.java) { PreparedRules.serve(snapshot, batch, 1, false) }
        val next = ActionRules.apply(snapshot, PreparedRules.serve(snapshot, batch, 2, false, plan.id).mutation)
        assertTrue(next.plans.single().completed)
        assertEquals(0, next.prepared.single().remaining)
        assertTrue(next.pantry.isEmpty())
    }
    @Test fun preparedMealsDoNotCreateAnotherRawIngredientShoppingList() {
        val plan = MealPlan("plan", today, recipe = recipe, servings = 1, prepared_batch_id = "batch")
        assertTrue(RecipeMath.shopping(Snapshot(plans = listOf(plan)), listOf(plan)).changes.isEmpty())
    }
    @Test fun cookingCannotBeUndoneAfterEatingPortions() {
        val snapshot = Snapshot()
        val cooked = ActionRules.apply(snapshot, PreparedRules.cook(snapshot, recipe, 2, emptyList(), BatchDetails(today, today, "frigider")).mutation)
        val served = ActionRules.apply(cooked, PreparedRules.serve(cooked, cooked.prepared.single(), 1, false).mutation)
        assertThrows(IllegalArgumentException::class.java) { RecipeMath.undo(served, served.events.first { it.kind == "prepared" }.id) }
    }
    @Test fun preparedPortionsParticipateInRemindersAndRecipesRemainReadable() {
        val batch = PreparedBatch("batch", recipe, today, today, total = 2, remaining = 1)
        val snapshot = Snapshot(prepared = listOf(batch))
        assertEquals(recipe, snapshot.allRecipes().single())
        assertEquals("Gătit · Paste", snapshot.reminderItems().single().name)
        assertTrue(snapshot.copy(prepared = listOf(batch.copy(remaining = 0))).reminderItems().isEmpty())
    }
}
