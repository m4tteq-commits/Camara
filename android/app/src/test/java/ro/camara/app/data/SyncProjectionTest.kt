package ro.camara.app.data

import java.time.LocalDate
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SyncProjectionTest {
    private val milk = PantryItem("milk", "Lapte", 1.0, "l")
    private val server = Snapshot(pantry = listOf(milk), epoch = "current")

    private fun edit(epoch: String, quantity: Double, issue: String? = null) = PendingAction(
        "edit-$quantity", Mutation("Lapte", listOf(Change("pantry", milk.id, ActionRules.record(milk),
            ActionRules.record(milk.copy(quantity = quantity)))), epoch = epoch), issue = issue,
    )

    @Test fun oldEpochAndRejectedActionsStayInJournalWithoutOverwritingVisibleServerState() {
        val legacy = edit("", 2.0)
        assertEquals(2.0, LocalState(server, pending = listOf(legacy)).projected().pantry.single().quantity, 0.0)
        val restored = server.copy(restored_at = "2026-11-09T10:00:00Z")
        val pending = listOf(legacy, edit("old", 3.0), edit("current", 4.0, "Conflict"), edit("current", 5.0))
        val local = LocalState(restored, pending = pending)
        assertEquals(restored, local.projected())
        assertEquals(pending, local.pending)
        val reopened = ActionRules.json.decodeFromString<LocalState>(ActionRules.json.encodeToString(local))
        assertEquals(pending, reopened.pending)
        assertEquals(restored, reopened.projected())
    }

    @Test fun cartRejectsAnEditedShoppingRowAndAnOldRestoreEpoch() {
        val listItem = ShoppingItem("need", "Lapte", "1 l")
        val initial = server.copy(shopping = listOf(listItem))
        val draft = CheckoutRules.start(initial, LocalDate.now())
        val ready = draft.copy(rows = draft.rows.map { it.copy(price = "7,00") })
        assertEquals("current", CheckoutRules.checkout(initial, ready).mutation.epoch)
        assertThrows(IllegalArgumentException::class.java) {
            CheckoutRules.checkout(initial.copy(shopping = listOf(listItem.copy(quantity = "2 l"))), ready)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CheckoutRules.checkout(initial.copy(epoch = "restored"), ready)
        }
        val legacy = ready.copy(epoch = "", rows = ready.rows.map { it.copy(shoppingBefore = null) })
        assertTrue(CheckoutRules.checkout(initial, legacy).mutation.changes.isNotEmpty())
    }
}
