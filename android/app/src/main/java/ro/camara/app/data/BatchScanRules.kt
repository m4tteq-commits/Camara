package ro.camara.app.data

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Serializable

internal val productBarcode = Regex("^(?:[0-9]{8}|[0-9]{12,14})$")
internal const val MAX_SCAN_LOTS = 40

@Serializable
internal data class ScannedLot(
    val id: String = UUID.randomUUID().toString(),
    val count: String = "1",
    val location: String = "frigider",
    val expiry: String = "",
)

@Serializable
internal data class ScannedProduct(
    val barcode: String,
    val known: Product?,
    val catalogId: String = known?.id ?: UUID.randomUUID().toString(),
    val name: String = known?.name.orEmpty(),
    val packageAmount: String = known?.let { BigDecimal.valueOf(it.package_quantity).stripTrailingZeros().toPlainString() } ?: "1",
    val unit: String = known?.unit ?: "buc",
    val lots: List<ScannedLot> = listOf(ScannedLot(location = known?.location ?: "frigider")),
)

internal fun addScannedProduct(draft: List<ScannedProduct>, code: String, catalog: List<Product>): List<ScannedProduct> {
    if (!productBarcode.matches(code) || draft.any { it.barcode == code } || draft.sumOf { it.lots.size } >= MAX_SCAN_LOTS) return draft
    return draft + ScannedProduct(code, catalog.firstOrNull { it.barcode == code })
}

internal fun scannedStockAction(snapshot: Snapshot, epoch: String, draft: List<ScannedProduct>): PendingAction {
    val lotCount = draft.sumOf { it.lots.size }
    require(lotCount in 1..MAX_SCAN_LOTS) { "Scanează între 1 și $MAX_SCAN_LOTS loturi." }
    require(draft.map { it.barcode }.toSet().size == draft.size) { "Același cod apare de mai multe ori." }
    val catalog = snapshot.products.associateBy { it.barcode }
    val pantryIds = snapshot.pantry.mapTo(mutableSetOf()) { it.id }
    val changes = buildList {
        for (row in draft) {
            val name = row.name.trim()
            val amount = row.packageAmount.trim().replace(',', '.').toDoubleOrNull()
            require(productBarcode.matches(row.barcode) && name.length in 1..100 && amount != null && amount.isFinite()
                && amount > 0 && amount <= 100000 && row.unit in setOf("buc", "g", "kg", "ml", "l") && row.lots.isNotEmpty()) {
                "Verifică numele și cantitatea ambalajului pentru codul ${row.barcode}."
            }
            val previous = catalog[row.barcode]
            require(previous == row.known) { "Produsul $name s-a schimbat în catalog. Elimină-l din lot și scanează-l din nou." }
            val product = previous?.copy(location = row.lots.last().location)
                ?: Product(row.catalogId, name, row.barcode, amount, row.unit, row.lots.last().location)
            for (lot in row.lots) {
                require(pantryIds.add(lot.id)) { "Unul dintre loturi a fost deja salvat. Închide și începe o scanare nouă." }
                val count = lot.count.toIntOrNull()
                val expiry = lot.expiry.trim().ifBlank { null }
                require(count != null && count > 0 && count * amount <= 100000 &&
                    lot.location in setOf("frigider", "camara", "congelator")) { "Verifică numărul de ambalaje pentru $name." }
                require(expiry == null || (expiry.length == 10 && !expiry.startsWith("0000") &&
                    runCatching { LocalDate.parse(expiry) }.isSuccess)) { "Verifică termenul pentru $name. Folosește formatul AAAA-LL-ZZ." }
                val stock = product.copy(location = lot.location).stockInput(count, expiry)
                val item = PantryItem(lot.id, stock.name, stock.quantity, stock.unit, stock.location, stock.expires_on)
                add(Change("pantry", item.id, after = ActionRules.record(item)))
            }
            if (product != previous) add(Change("products", product.id, previous?.let { ActionRules.record(it) }, ActionRules.record(product)))
        }
    }
    val action = ActionRules.action("Scanare în serie · $lotCount loturi", changes, rebasable = false)
    return action.copy(mutation = action.mutation.copy(epoch = epoch))
}
