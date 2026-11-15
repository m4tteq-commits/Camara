package ro.camara.app.data

import org.junit.Assert.*
import org.junit.Test

class PurchaseHistoryTest {
    @Test fun repeatMergesCompatiblePackagesAndKeepsExistingListQuantity() {
        val milk = ShoppingItem("milk", "Lapte", "1 l")
        val purchase = Purchase("trip", "Magazin", "2026-11-07", 1800, "expense", listOf(
            PurchaseLine("Orez", 0.5, "kg", "camara", 600, true),
            PurchaseLine("Orez", 250.0, "g", "camara", 350, true),
            PurchaseLine("Lapte", 2.0, "l", "frigider", 850, true),
        ))
        val snapshot = Snapshot(shopping = listOf(milk), purchases = listOf(purchase))
        val action = PurchaseHistory.repeat(snapshot, "purchase:trip", setOf(0, 1, 2))
        val next = ActionRules.apply(snapshot, action.mutation)

        assertEquals(milk, next.shopping.first { it.name == "Lapte" })
        assertEquals("0.75 kg", next.shopping.single { it.name == "Orez" }.quantity)
        assertEquals(snapshot.pantry, next.pantry)
        assertEquals(listOf(ActionRules.record(milk)), action.mutation.guards.getValue("shopping"))
        assertThrows(IllegalArgumentException::class.java) { PurchaseHistory.repeat(next, "purchase:trip", setOf(0, 1, 2)) }
    }

    @Test fun comparisonUsesMostRecentCompatibleUnitFromCartOrReceipt() {
        val trip = Purchase("trip", "Magazin A", "2026-11-07", 350, "expense", listOf(
            PurchaseLine("Iaurt", 1.0, "buc", "frigider", 350, true),
        ))
        val receipt = ReceiptRecord("receipt", "0".repeat(64), "Magazin B", "2026-11-06", 850, "expense-b", "2026-11-06T12:00:00Z", listOf(
            ReceiptLine("Iaurt natural", 400.0, "g", line_cents = 850),
        ))
        val snapshot = Snapshot(purchases = listOf(trip), receipts = listOf(receipt), aliases = mapOf("iaurt natural" to "iaurt"))
        val prior = PurchaseHistory.latest(PurchaseHistory.entries(snapshot), snapshot.aliases, "IAURT", "kg")

        assertEquals("Magazin B", prior?.entry?.merchant)
        assertEquals("21,25 lei/kg", prior?.unitPrice?.label)
        assertEquals("14,00 lei/l", PurchaseHistory.perUnit(1050, 750.0, "ml")?.label)
        assertNull(PurchaseHistory.perUnit(100, 0.0, "kg"))
    }

    @Test fun priceHistoryGroupsReceiptAliasesAndCompatibleSizesWithoutMixingPieces() {
        val trip = Purchase("trip", "Magazin A", "2026-11-07", 1650, "expense", listOf(
            PurchaseLine("Iaurt natural", 0.5, "kg", "frigider", 1200, true),
            PurchaseLine("Iaurt natural", 1.0, "buc", "frigider", 450, true),
        ))
        val receipt = ReceiptRecord("receipt", "0".repeat(64), "Magazin B", "2026-11-06", 850, "expense-b", "", listOf(
            ReceiptLine("IAURT NAT", 400.0, "g", line_cents = 850),
            ReceiptLine("Iaurt natural", 100.0, "g", line_cents = null),
        ))
        val product = Product("yogurt", "Iaurt natural", package_quantity = 400.0, unit = "g", receipt_names = listOf("IAURT NAT"))
        val groups = PurchaseHistory.byProduct(Snapshot(products = listOf(product), purchases = listOf(trip), receipts = listOf(receipt)))

        assertEquals(setOf("kg", "buc"), groups.map { it.unit }.toSet())
        val kg = groups.single { it.unit == "kg" }
        assertEquals("Iaurt natural", kg.name)
        assertEquals(listOf("Magazin A", "Magazin B"), kg.quotes.map { it.entry.merchant })
        assertEquals(listOf("24,00 lei/kg", "21,25 lei/kg"), kg.quotes.map { it.unitPrice.label })
        assertEquals("4,50 lei/buc", groups.single { it.unit == "buc" }.quotes.single().unitPrice.label)
    }
}
