package ro.camara.app.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

internal data class PurchasedItem(val name: String, val quantity: Double, val unit: String, val cents: Int?)

internal data class PurchaseEntry(
    val key: String,
    val merchant: String,
    val date: String,
    val totalCents: Int,
    val items: List<PurchasedItem>,
)

internal data class UnitPrice(val lei: BigDecimal, val unit: String) {
    val label: String get() = "${lei.toPlainString().replace('.', ',')} lei/$unit"
}

internal data class HistoricalPrice(val entry: PurchaseEntry, val item: PurchasedItem, val unitPrice: UnitPrice)

internal data class ProductPrices(val key: String, val name: String, val unit: String, val quotes: List<HistoricalPrice>)

internal object PurchaseHistory {
    fun entries(snapshot: Snapshot): List<PurchaseEntry> {
        val purchases = snapshot.purchases.map { purchase ->
            PurchaseEntry("purchase:${purchase.id}", purchase.merchant, purchase.spent_on, purchase.total_cents,
                purchase.lines.map { PurchasedItem(it.name, it.quantity, it.unit, it.line_cents) })
        }
        val receipts = snapshot.receipts.map { receipt ->
            PurchaseEntry("receipt:${receipt.id}", receipt.merchant, receipt.spent_on, receipt.total_cents,
                receipt.lines.filter { it.include_pantry }.map { PurchasedItem(it.name, it.quantity, it.unit, it.line_cents) })
        }
        return (purchases + receipts).filter { it.items.isNotEmpty() }.sortedByDescending { it.date }
    }

    fun perUnit(cents: Int?, quantity: Double?, unit: String): UnitPrice? {
        if (cents == null || cents < 0 || quantity == null || !quantity.isFinite() || quantity <= 0) return null
        val divisor = BigDecimal.valueOf(quantity)
        val total = BigDecimal.valueOf(cents.toLong(), 2)
        return when (unit) {
            "g" -> UnitPrice(total.movePointRight(3).divide(divisor, 2, RoundingMode.HALF_UP), "kg")
            "ml" -> UnitPrice(total.movePointRight(3).divide(divisor, 2, RoundingMode.HALF_UP), "l")
            "kg", "l", "buc" -> UnitPrice(total.divide(divisor, 2, RoundingMode.HALF_UP), unit)
            else -> null
        }
    }

    fun latest(entries: List<PurchaseEntry>, aliases: Map<String, String>, name: String, unit: String): HistoricalPrice? {
        val product = RecipeMath.canonical(name, aliases)
        val base = RecipeMath.baseUnit(unit)
        return entries.firstNotNullOfOrNull { entry ->
            entry.items.firstNotNullOfOrNull { item ->
                val matches = RecipeMath.canonical(item.name, aliases) == product && RecipeMath.baseUnit(item.unit) == base
                if (matches) perUnit(item.cents, item.quantity, item.unit)?.let { HistoricalPrice(entry, item, it) } else null
            }
        }
    }

    fun byProduct(snapshot: Snapshot): List<ProductPrices> {
        val catalogNames = snapshot.products.groupBy { RecipeMath.canonical(it.name, snapshot.aliases) }
        val receiptNames = snapshot.products.flatMap { product ->
            product.receipt_names.map { RecipeMath.normalize(it) to RecipeMath.canonical(product.name, snapshot.aliases) }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, names) -> names.distinct().singleOrNull() }
        val quotes = entries(snapshot).flatMap { entry ->
            entry.items.mapNotNull { item -> perUnit(item.cents, item.quantity, item.unit)?.let { HistoricalPrice(entry, item, it) } }
        }
        return quotes.groupBy { quote ->
            val canonical = RecipeMath.canonical(quote.item.name, snapshot.aliases)
            val name = if (canonical in catalogNames) canonical else receiptNames[RecipeMath.normalize(quote.item.name)] ?: canonical
            name to quote.unitPrice.unit
        }.map { (key, prices) ->
            ProductPrices("${key.first}:${key.second}", catalogNames[key.first]?.firstOrNull()?.name ?: prices.first().item.name, key.second, prices)
        }.sortedWith(compareBy<ProductPrices> { RecipeMath.normalize(it.name) }.thenBy { it.unit })
    }

    fun repeat(snapshot: Snapshot, entryKey: String, selected: Set<Int>): PendingAction {
        val entry = entries(snapshot).firstOrNull { it.key == entryKey }
            ?: error("Cumpărătura nu mai este în istoric. Actualizează lista.")
        require(selected.isNotEmpty() && selected.all { it in entry.items.indices }) { "Alege produsele pe care vrei să le cumperi din nou." }
        val pending = snapshot.shopping.filterNot { it.checked }.groupBy { RecipeMath.canonical(it.name, snapshot.aliases) }
        val chosen = entry.items.filterIndexed { index, _ -> index in selected }
        val grouped = chosen.groupBy { RecipeMath.canonical(it.name, snapshot.aliases) to RecipeMath.baseUnit(it.unit) }
        val changes = grouped.mapNotNull { (key, lines) ->
            val (name, unit) = key
            if (pending[name].orEmpty().any { (RecipeMath.amount(it.quantity)?.second ?: unit) == unit }) return@mapNotNull null
            val first = lines.first()
            val quantity = lines.fold(BigDecimal.ZERO) { total, line ->
                total + BigDecimal.valueOf(line.quantity).multiply(BigDecimal.valueOf(RecipeMath.factor(line.unit)))
            }.divide(BigDecimal.valueOf(RecipeMath.factor(first.unit))).stripTrailingZeros().toPlainString()
            val label = "$quantity ${first.unit}"
            require(label.length <= 80) { "Cantitatea pentru ${first.name} trebuie introdusă manual." }
            val item = ShoppingItem(UUID.randomUUID().toString(), first.name, label)
            ActionRules.change(snapshot, "shopping", item.id, ActionRules.record(item))
        }
        require(changes.isNotEmpty()) { "Produsele alese sunt deja pe listă." }
        return ActionRules.action("Cumpăr din nou · ${entry.merchant}", changes, rebasable = false,
            guards = mapOf("shopping" to ActionRules.records(snapshot, "shopping")))
    }
}
