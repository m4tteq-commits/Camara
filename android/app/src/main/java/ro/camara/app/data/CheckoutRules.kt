package ro.camara.app.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

object CheckoutRules {
    fun cents(raw: String): Int? = runCatching { BigDecimal(raw.trim().replace(',', '.')).setScale(2, RoundingMode.UNNECESSARY)
        .movePointRight(2).intValueExact().also { require(it in 1..100_000_000) } }.getOrNull()
    fun start(snapshot: Snapshot, today: LocalDate = LocalDate.now()): ShoppingCart {
        val rows = snapshot.shopping.filterNot { it.checked }.take(60).map { item ->
            val parsed = RecipeMath.amount(item.quantity)
            val product = snapshot.products.firstOrNull { RecipeMath.canonical(it.name, snapshot.aliases) == RecipeMath.canonical(item.name, snapshot.aliases) }
            CartRow(item.id, item.name, parsed?.first?.let(RecipeMath::number) ?: product?.package_quantity?.let(RecipeMath::number) ?: "1",
                parsed?.second ?: product?.unit ?: "buc", location = product?.location ?: "camara", shoppingId = item.id, shoppingBefore = item)
        }
        return ShoppingCart(UUID.randomUUID().toString(), date = today.toString(), rows = rows, epoch = snapshot.epoch)
    }
    fun checkout(snapshot: Snapshot, cart: ShoppingCart): PendingAction {
        require(cart.epoch.isEmpty() || cart.epoch == snapshot.epoch) { "Serverul a fost restaurat. Reia coșul pe lista actualizată." }
        require(snapshot.purchases.none { it.id == cart.id }) { "Aceste cumpărături au fost deja confirmate." }
        require(cart.merchant.isNotBlank() && cart.merchant.length <= 100) { "Completează magazinul." }
        val date = LocalDate.parse(cart.date)
        require(date <= LocalDate.now()) { "Data cumpărăturilor nu poate fi în viitor." }
        val selected = cart.rows.filter { it.selected }
        require(selected.isNotEmpty() && selected.size <= 60) { "Alege între 1 și 60 de produse." }
        require(selected.map { it.id }.distinct().size == selected.size)
        val changes = mutableListOf<Change>()
        fun stable(part: String) = UUID.nameUUIDFromBytes("${cart.id}:$part".toByteArray()).toString()
        val lines = selected.map { row ->
            val quantity = row.quantity.replace(',', '.').toDoubleOrNull()
            require(quantity != null && quantity.isFinite() && quantity > 0 && quantity <= 100000 && row.unit in listOf("buc", "g", "kg", "ml", "l")) { "Verifică cantitatea pentru ${row.name}." }
            require(row.name.isNotBlank() && row.name.length <= 100)
            val price = cents(row.price) ?: error("Completează prețul total al poziției ${row.name}.")
            val expiry = row.expires.trim().takeIf { it.isNotEmpty() }?.also { LocalDate.parse(it) }
            val pantryId = if (row.includePantry) stable("pantry:${row.id}") else null
            if (pantryId != null) {
                require(row.location in listOf("camara", "frigider", "congelator"))
                val stock = PantryItem(pantryId, row.name.trim(), quantity, row.unit, row.location, expiry)
                changes += ActionRules.change(snapshot, "pantry", pantryId, ActionRules.record(stock))
            }
            row.shoppingId?.let { id ->
                val current = snapshot.shopping.firstOrNull { it.id == id }
                require(current != null && !current.checked && (row.shoppingBefore == null || current == row.shoppingBefore)) {
                    "${row.name} s-a schimbat în listă. Reia coșul pe lista actualizată."
                }
                changes += ActionRules.change(snapshot, "shopping", id, ActionRules.record(current.copy(checked = true)))
            }
            PurchaseLine(row.name.trim(), quantity, row.unit, row.location, price, row.includePantry,
                shopping_id = row.shoppingId, pantry_id = pantryId, expires_on = expiry)
        }
        val total = lines.sumOf { it.line_cents.toLong() }
        require(total in 1..100_000_000) { "Verifică totalul cumpărăturilor." }
        val expense = Expense(stable("expense"), "Cumpărături · ${cart.merchant}".take(100), total.toInt(), cart.date)
        val purchase = Purchase(cart.id, cart.merchant.trim(), cart.date, total.toInt(), expense.id, lines)
        changes += ActionRules.change(snapshot, "expenses", expense.id, ActionRules.record(expense))
        changes += ActionRules.change(snapshot, "purchases", purchase.id, ActionRules.record(purchase))
        return PendingAction(cart.id, Mutation("Cumpărături confirmate", changes, epoch = cart.epoch), rebasable = false)
    }
}
