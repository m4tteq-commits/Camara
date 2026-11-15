package ro.camara.app.data

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RecipeSuggestionsTest {
    private val today = LocalDate.parse("2026-11-11")
    private val porridge = PersonalRecipe("oats", "Terci cu ovăz", 10, 1, listOf(Ingredient("Ovăz", "50 g")),
        listOf("Fierbe la foc mic."), listOf("Plită"), "vegan", "breakfast", today.toString())

    @Test fun savedIdeasUseCurrentUnexpiredStockAndScalePersonalRecipes() {
        val stock = Snapshot(
            personal_recipes = listOf(porridge),
            pantry = listOf(PantryItem("fresh", "Ovăz", 0.08, "kg"), PantryItem("expired", "Ovăz", 1.0, "kg",
                expires_on = today.minusDays(1).toString())),
        )
        val request = RecipeRequest(servings = 2, category = "breakfast", diet = "vegetarian")
        val recipe = savedRecipeSuggestions(stock, request, today).single()
        assertEquals(SAVED_RECIPE_MODEL, recipe.model)
        assertEquals(2, recipe.servings)
        assertEquals("100 g", recipe.ingredients.single().quantity)
        assertEquals("20 g", recipe.missing_ingredients.single().quantity)
        assertTrue(savedRecipeSuggestions(stock, request.copy(pantry_only = true), today).isEmpty())
        assertTrue(savedRecipeSuggestions(stock, request.copy(equipment = listOf("Cuptor")), today).isEmpty())
    }

    @Test fun savedHistoryNeedsMatchingPortionsAndDoesNotAssumeDietOrCategory() {
        val saved = porridge.recipe().copy(id = "first", model = "test")
        val stock = Snapshot(recipes = listOf(saved, saved.copy(id = "second")))
        assertEquals(1, savedRecipeSuggestions(stock, RecipeRequest(), today).size)
        for (request in listOf(RecipeRequest(servings = 2), RecipeRequest(diet = "vegan"),
            RecipeRequest(category = "breakfast"), RecipeRequest(max_minutes = 5))) {
            assertTrue(savedRecipeSuggestions(stock, request, today).isEmpty())
        }
    }
}
