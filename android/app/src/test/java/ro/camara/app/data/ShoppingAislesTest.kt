package ro.camara.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ShoppingAislesTest {
    @Test
    fun explicitAisleTakesPriorityAndFrozenProductsStayTogether() {
        assertEquals(ShoppingAisle.Frozen, ShoppingAisle.forProduct("Mazăre congelată"))
        assertEquals(ShoppingAisle.Dairy, ShoppingAisle.forProduct("Ouă"))
        assertEquals(ShoppingAisle.Pantry, ShoppingAisle.forProduct("Conservă de roșii"))
        assertEquals(ShoppingAisle.Produce, ShoppingAisle.forProduct("Ouă", "produce"))
        assertEquals(ShoppingAisle.Other, ShoppingAisle.forProduct("Produs necunoscut"))
    }

    @Test
    fun savedOrderKeepsEveryAisleExactlyOnce() {
        val order = ShoppingAisle.ordered("dairy,dairy,produce,obsolete")
        assertEquals(listOf(ShoppingAisle.Dairy, ShoppingAisle.Produce), order.take(2))
        assertEquals(ShoppingAisle.entries.toSet(), order.toSet())
        assertEquals(ShoppingAisle.entries.size, order.size)
    }
}
