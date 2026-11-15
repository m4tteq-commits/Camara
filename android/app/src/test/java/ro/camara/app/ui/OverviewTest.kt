package ro.camara.app.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ro.camara.app.data.Ingredient
import ro.camara.app.data.MealPlan
import ro.camara.app.data.PersonalRecipe
import ro.camara.app.data.Recipe

class OverviewTest {
    private val recipe = Recipe("recipe", "Supă de legume", 30, 2, listOf(Ingredient("Țelină", "100 g")),
        listOf("Fierbe legumele."), model = "test", generated_at = "", generation_ms = 0)

    @Test fun nextMealUsesDateAndSlotIgnoringCompletedAndPastMeals() {
        val today = LocalDate.of(2026, 11, 11)
        val tomorrow = today.plusDays(1).toString()
        val breakfast = MealPlan("breakfast", tomorrow, "breakfast", recipe, 2)
        val dinner = breakfast.copy(id = "dinner", slot = "dinner")
        val completed = breakfast.copy(id = "completed", on_date = today.toString(), completed = true)
        val past = breakfast.copy(id = "past", on_date = today.minusDays(1).toString())
        assertEquals(breakfast, nextPlannedMeal(listOf(past, completed, dinner, breakfast), today))
        assertNull(nextPlannedMeal(listOf(past, completed), today))
    }

    @Test fun savedRecipesSearchTitlesAndIngredientsWithoutDiacriticsAndCombineWithTime() {
        val faster = recipe.copy(id = "fast", title = "Salată", minutes = 15)
        assertEquals(listOf(recipe), filterSavedRecipes(listOf(recipe, faster), "SUPA", null))
        assertEquals(listOf(faster), filterSavedRecipes(listOf(recipe, faster), "telina", 15))
        assertEquals(emptyList<Recipe>(), filterSavedRecipes(listOf(recipe, faster), "orez", null))
    }

    @Test fun personalLibraryCombinesIngredientCategoryAndDuration() {
        val main = PersonalRecipe("main", "Supă", 30, 2, recipe.ingredients, recipe.steps, updated_at = "")
        val snack = main.copy(id = "snack", title = "Gustare", minutes = 10, category = "snack")
        assertEquals(listOf(snack), filterPersonalRecipes(listOf(main, snack), "telina", "snack", 15))
        assertEquals(listOf(main), filterPersonalRecipes(listOf(main, snack), "supa", "main", 30))
        assertEquals(emptyList<PersonalRecipe>(), filterPersonalRecipes(listOf(main, snack), "telina", "main", 15))
    }
}
