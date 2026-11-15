package ro.camara.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BatchScanRulesTest {
    private val milk = Product("milk", "Lapte", "5941234123458", 500.0, "ml", "frigider")
    private val unknownCode = "4006381333931"
    private val snapshot = Snapshot(products = listOf(milk), epoch = "generation")

    @Test fun scanIgnoresRepeatedFramesAndPrefillsTheKnownPackage() {
        val rows = addScannedProduct(emptyList(), milk.barcode, snapshot.products)
        assertSame(rows, addScannedProduct(rows, milk.barcode, snapshot.products))
        assertSame(rows, addScannedProduct(rows, "invalid", snapshot.products))
        assertEquals("500", rows.single().packageAmount)
        assertEquals("frigider", rows.single().lots.single().location)
        assertEquals("", addScannedProduct(rows, unknownCode, snapshot.products).last().name)
    }

    @Test fun savesSeparateLotsAndCatalogChangesTogetherAndRejectsAnAlreadySavedDraft() {
        val expiry = LocalDate.of(2027, 1, 6).toString()
        val rows = listOf(
            ScannedProduct(milk.barcode, milk).let { row ->
                row.copy(lots = listOf(row.lots.single().copy(count = "2", expiry = expiry), ScannedLot(location = "congelator")))
            },
            ScannedProduct(unknownCode, null).copy(name = "Orez", packageAmount = "0,5", unit = "kg"),
        )
        val action = scannedStockAction(snapshot, snapshot.epoch, rows)
        val next = ActionRules.apply(snapshot, action.mutation)

        assertEquals("generation", action.mutation.epoch)
        assertFalse(action.rebasable)
        assertEquals(listOf(1000.0, 500.0), next.pantry.filter { it.name == "Lapte" }.map { it.quantity })
        assertEquals(expiry, next.pantry.first().expires_on)
        assertEquals("congelator", next.products.single { it.barcode == milk.barcode }.location)
        assertEquals(0.5, next.products.single { it.barcode == unknownCode }.package_quantity, 0.0)
        assertThrows(IllegalArgumentException::class.java) { scannedStockAction(next, snapshot.epoch, rows) }
    }

    @Test fun rejectsInvalidLotAndAnExistingCodeThatChangedAfterScanning() {
        val row = ScannedProduct(milk.barcode, milk)
        val invalid = row.copy(lots = listOf(row.lots.single().copy(expiry = "2027-02-29")))
        assertThrows(IllegalArgumentException::class.java) { scannedStockAction(snapshot, snapshot.epoch, listOf(invalid)) }
        val changed = snapshot.copy(products = listOf(milk.copy(package_quantity = 250.0)))
        assertThrows(IllegalArgumentException::class.java) { scannedStockAction(changed, snapshot.epoch, listOf(row)) }
    }
}
