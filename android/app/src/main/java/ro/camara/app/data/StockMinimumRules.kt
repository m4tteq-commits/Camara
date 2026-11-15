package ro.camara.app.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

internal data class Replenishment(
    val minimum: StockMinimum,
    val available: Double,
    val planned: Double,
    val missing: Double,
    val needsQuantityCheck: Boolean,
    val firstCompatible: ShoppingItem?,
)

internal object StockMinimumRules {
    private const val kind = "stock_minimums"
    val units = listOf("buc", "g", "kg", "ml", "l")

    fun save(snapshot: Snapshot, previous: StockMinimum?, minimum: StockMinimum): PendingAction {
        require(snapshot.stock_minimums.firstOrNull { it.id == minimum.id } == previous) { "Pragul a fost schimbat. Redeschide setările." }
        require(minimum.name.isNotBlank() && minimum.name.length <= 100 && minimum.quantity in 0.0001..100000.0 && minimum.unit in units)
        require(previous != null || snapshot.stock_minimums.size < 200) { "Poți păstra cel mult 200 de praguri de stoc." }
        val name = RecipeMath.canonical(minimum.name, snapshot.aliases)
        require(snapshot.stock_minimums.none { it.id != minimum.id && RecipeMath.canonical(it.name, snapshot.aliases) == name }) {
            "Acest aliment are deja un prag de stoc minim."
        }
        return ActionRules.action("Stoc minim · ${minimum.name}",
            listOf(ActionRules.change(snapshot, kind, minimum.id, ActionRules.record(minimum))), rebasable = false)
    }

    fun remove(snapshot: Snapshot, minimum: StockMinimum): PendingAction {
        require(snapshot.stock_minimums.firstOrNull { it.id == minimum.id } == minimum) { "Pragul a fost schimbat. Redeschide setările." }
        return ActionRules.action("Elimină pragul · ${minimum.name}", listOf(ActionRules.change(snapshot, kind, minimum.id, null)), rebasable = false)
    }

    fun report(snapshot: Snapshot, today: LocalDate = LocalDate.now()): List<Replenishment> {
        val date = today.toString()
        fun key(name: String) = RecipeMath.canonical(name, snapshot.aliases)
        val pantry = snapshot.pantry.filter { it.expires_on == null || it.expires_on >= date }.groupBy { key(it.name) }
        val shopping = snapshot.shopping.filterNot { it.checked }.groupBy { key(it.name) }
        return snapshot.stock_minimums.sortedBy { key(it.name) }.map { minimum ->
            val name = key(minimum.name)
            val baseUnit = RecipeMath.baseUnit(minimum.unit)
            val factor = RecipeMath.factor(minimum.unit)
            val available = pantry[name].orEmpty().filter { RecipeMath.baseUnit(it.unit) == baseUnit }
                .sumOf { it.quantity * RecipeMath.factor(it.unit) } / factor
            val pending = shopping[name].orEmpty()
            val compatible = pending.mapNotNull { item ->
                val amount = RecipeMath.amount(item.quantity)
                if (amount != null && amount.second == baseUnit && amount.first.isFinite() && amount.first > 0) item to amount.first else null
            }
            val planned = compatible.sumOf { it.second } / factor
            val missing = rounded((minimum.quantity - available - planned).coerceAtLeast(0.0)).toDouble()
            Replenishment(minimum, available, planned, missing, missing > 0 && compatible.size < pending.size, compatible.firstOrNull()?.first)
        }
    }

    fun preview(snapshot: Snapshot, today: LocalDate = LocalDate.now()): ShoppingPreview {
        val changes = mutableListOf<Change>()
        val lines = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        for (row in report(snapshot, today)) {
            if (row.missing <= 0) continue
            val minimum = row.minimum
            if (row.needsQuantityCheck) {
                warnings += "${minimum.name} este deja pe listă cu o cantitate sau unitate de verificat."
                continue
            }
            val existing = row.firstCompatible
            val original = existing?.let { RecipeMath.amount(it.quantity)?.first } ?: 0.0
            val newQuantity = original / RecipeMath.factor(minimum.unit) + row.missing
            val quantity = "${amountLabel(newQuantity)} ${minimum.unit}"
            val next = existing?.copy(quantity = quantity) ?: ShoppingItem(UUID.randomUUID().toString(), minimum.name, quantity)
            changes += ActionRules.change(snapshot, "shopping", next.id, ActionRules.record(next))
            lines += "${minimum.name} · +${amountLabel(row.missing)} ${minimum.unit}"
        }
        val guards = if (changes.isEmpty()) emptyMap() else listOf("pantry", "shopping", kind).associateWith { ActionRules.records(snapshot, it) }
        return ShoppingPreview(changes, lines, warnings, guards)
    }

    fun amountLabel(value: Double): String = rounded(value).stripTrailingZeros().toPlainString()

    private fun rounded(value: Double) = BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP)
}
