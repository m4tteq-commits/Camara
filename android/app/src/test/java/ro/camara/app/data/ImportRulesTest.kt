package ro.camara.app.data

import org.junit.Assert.*
import org.junit.Test

class ImportRulesTest {
    private val product = Product("p", "Orez", "5901234123457", 500.0, "g", "camara")
    @Test fun packagesBecomeStockInTheStoredUnit() {
        val input = product.stockInput(2, "2026-12-01")
        assertEquals(1000.0, input.quantity, 0.000001)
        assertEquals("g", input.unit)
        assertEquals("camara", input.location)
        assertThrows(IllegalArgumentException::class.java) { product.stockInput(0, null) }
        assertThrows(IllegalArgumentException::class.java) { product.stockInput(1000, null) }
    }
    @Test fun receiptImportProjectsExpenseAndFoodTogetherAndBlocksDuplicates() {
        val preview = ReceiptPreview("a".repeat(64), "Magazin", total_cents = 1234, raw_text = "bon")
        val food = PantryItem("food", "Orez", 1000.0, "g")
        val action = ReceiptRules.importAction(Snapshot(), preview, "Magazin", "2026-11-01", 1234, emptyList(), listOf(food))
        val snapshot = ActionRules.apply(Snapshot(), action.mutation)
        assertEquals(1234, snapshot.expenses.single().amount_cents)
        assertEquals(snapshot.expenses.single().id, snapshot.receipts.single().expense_id)
        assertEquals(food, snapshot.pantry.single())
        assertThrows(IllegalArgumentException::class.java) { ReceiptRules.importAction(snapshot, preview, "Magazin", "2026-11-01", 1234, emptyList(), listOf(food)) }
        val secondDevice = ReceiptRules.importAction(Snapshot(), preview, "Magazin", "2026-11-01", 1234, emptyList(), emptyList())
        assertEquals(action.mutation.changes[0].id, secondDevice.mutation.changes[0].id)
    }
    @Test fun restorationMakesOldQueuedActionsStale() {
        val afterRestore = Snapshot(epoch = "new", restored_at = "2026-11-01T15:00:00Z")
        val old = PendingAction("action", Mutation("Test", emptyList(), epoch = "old"))
        assertTrue(staleEpoch(old, afterRestore))
        assertTrue(staleEpoch(old.copy(mutation = old.mutation.copy(epoch = "")), afterRestore))
        assertFalse(staleEpoch(old.copy(mutation = old.mutation.copy(epoch = "new")), afterRestore))
        assertFalse(staleEpoch(old.copy(mutation = old.mutation.copy(epoch = "")), Snapshot(epoch = "first")))
    }
    @Test fun backupDateUsesBucharestTimeForServerOffsets() {
        assertEquals("01.11.2026 22:45", ro.camara.app.ui.backupTimestamp("2026-11-01T20:45:29.127845+00:00"))
        assertEquals("01.11.2026 22:45", ro.camara.app.ui.backupTimestamp("2026-11-01T20:45:29Z"))
    }
}
