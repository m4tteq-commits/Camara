package ro.camara.app.data

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class StockMinimumTest {
    private val today = LocalDate.parse("2026-11-08")

    @Test fun replenishmentAccountsForValidStockAndAddsOnlyTheMissingQuantity() {
        val snapshot = Snapshot(
            pantry = listOf(
                PantryItem("fresh", "Lapte UHT", 300.0, "ml", expires_on = today.toString()),
                PantryItem("expired", "Lapte", 2.0, "l", expires_on = today.minusDays(1).toString()),
            ),
            shopping = listOf(ShoppingItem("planned", "Lapte", "200 ml"), ShoppingItem("checked", "Lapte", "2 l", true)),
            aliases = mapOf("lapte uht" to "lapte"),
            stock_minimums = listOf(StockMinimum("limit", "Lapte", 1.0, "l")),
        )
        val preview = StockMinimumRules.preview(snapshot, today)
        assertEquals(listOf("Lapte · +0.5 l"), preview.lines)
        assertEquals(setOf("pantry", "shopping", "stock_minimums"), preview.guards.keys)
        val after = ActionRules.apply(snapshot, Mutation("Reaprovizionare", preview.changes))
        assertEquals("0.7 l", after.shopping.single { it.id == "planned" }.quantity)
        assertTrue(StockMinimumRules.preview(after, today).changes.isEmpty())
    }

    @Test fun ambiguousExistingListEntryIsLeftForReview() {
        val snapshot = Snapshot(shopping = listOf(ShoppingItem("planned", "Ouă", "o cutie")),
            stock_minimums = listOf(StockMinimum("limit", "Ouă", 6.0, "buc")))
        val preview = StockMinimumRules.preview(snapshot, today)
        assertTrue(preview.changes.isEmpty())
        assertEquals(1, preview.warnings.size)
    }

    @Test fun quickUseRejectsStaleStockAndCanBeUndone() {
        val item = PantryItem("eggs", "Ouă", 6.0, expires_on = today.toString())
        val snapshot = Snapshot(pantry = listOf(item))
        val action = RecipeMath.consume(snapshot, item.name, listOf(StockUse(item, 2.0)), today = today, quickUse = true)
        val after = ActionRules.apply(snapshot, action.mutation)
        assertEquals(4.0, after.pantry.single().quantity, 0.0)
        assertEquals("used", after.events.single().kind)
        assertThrows(IllegalArgumentException::class.java) {
            RecipeMath.consume(after, item.name, listOf(StockUse(item, 2.0)), today = today, quickUse = true)
        }
        val undone = ActionRules.apply(after, RecipeMath.undo(after, after.events.single().id).mutation)
        assertEquals(item, undone.pantry.single())
        assertTrue(undone.events.single().undone)
    }
}
