package ro.camara.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

internal enum class HistoryKind(val path: String, val label: String) {
    Receipts("receipts", "Bonuri"), Purchases("purchases", "Cumpărături"),
    Expenses("expenses", "Cheltuieli"), Events("events", "Consum"),
}

internal data class HistoryRow(val id: String, val title: String, val date: String, val cents: Int? = null, val detail: String = "")

@Serializable
internal data class HistoryPage(val items: List<JsonObject>, val next_cursor: String? = null)

internal data class HistoryRows(val rows: List<HistoryRow>, val nextCursor: String?)

private fun Expense.history() = HistoryRow(id, label, spent_on, amount_cents, category)
private fun ReceiptRecord.history() = HistoryRow(id, merchant, spent_on, total_cents, "${lines.size} poziții")
private fun Purchase.history() = HistoryRow(id, merchant, spent_on, total_cents, "${lines.size} poziții")
private fun FoodEvent.history() = HistoryRow(id, title, occurred_on, value_cents, when {
    undone -> "Anulată"
    kind == "wasted" || kind == "portion_wasted" -> "Risipa"
    kind == "prepared" -> "Gătit"
    else -> "Consum"
})

internal fun Snapshot.history(kind: HistoryKind): List<HistoryRow> = when (kind) {
    HistoryKind.Receipts -> receipts.map(ReceiptRecord::history)
    HistoryKind.Purchases -> purchases.map(Purchase::history)
    HistoryKind.Expenses -> expenses.map(Expense::history)
    HistoryKind.Events -> events.map(FoodEvent::history)
}

internal fun HistoryPage.rows(kind: HistoryKind, json: Json) = HistoryRows(items.map { row ->
    when (kind) {
        HistoryKind.Receipts -> json.decodeFromJsonElement<ReceiptRecord>(row).history()
        HistoryKind.Purchases -> json.decodeFromJsonElement<Purchase>(row).history()
        HistoryKind.Expenses -> json.decodeFromJsonElement<Expense>(row).history()
        HistoryKind.Events -> json.decodeFromJsonElement<FoodEvent>(row).history()
    }
}, next_cursor)
