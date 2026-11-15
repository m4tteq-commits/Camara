package ro.camara.app.data

import java.time.LocalDate
import java.time.Instant
import java.util.UUID

fun Product.stockInput(count: Int, expiry: String?): PantryInput {
    val total = count * package_quantity
    require(count > 0 && total.isFinite() && total > 0 && total <= 100000) { "Verifică numărul de ambalaje." }
    if (!expiry.isNullOrBlank()) LocalDate.parse(expiry)
    return PantryInput(name, total, unit, location, expiry?.ifBlank { null })
}

object ReceiptRules {
    fun importAction(snapshot: Snapshot, preview: ReceiptPreview, merchant: String, date: String, total: Int,
                     lines: List<ReceiptLine>, pantry: List<PantryItem>): PendingAction {
        require(snapshot.receipts.none { it.image_sha256 == preview.image_sha256 }) { "Această fotografie de bon a fost deja importată." }
        require(merchant.isNotBlank() && total > 0 && total <= 100_000_000)
        LocalDate.parse(date)
        val id = UUID.nameUUIDFromBytes("camara-receipt:${preview.image_sha256}".toByteArray()).toString()
        val expenseId = UUID.nameUUIDFromBytes("camara-receipt-expense:$id".toByteArray()).toString()
        val receipt = ReceiptRecord(id, preview.image_sha256, merchant, date, total, expenseId, Instant.now().toString(), lines)
        val expense = Expense(expenseId, merchant, total, date)
        return ActionRules.action("Bon: $merchant", listOf(Change("receipts", id, after = ActionRules.record(receipt)), Change("expenses", expenseId, after = ActionRules.record(expense))) +
            pantry.map { Change("pantry", it.id, after = ActionRules.record(it)) }, rebasable = false)
    }
}
