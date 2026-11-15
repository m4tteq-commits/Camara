package ro.camara.app.data

import org.junit.Assert.*
import org.junit.Test

class RecipeTextImportTest {
    @Test fun parsesRomanianHeadingsQuantitiesAndSteps() {
        val recipe = parseRecipeText("""
            Supă de legume
            Durată: 30 minute
            Porții: 2
            Ingrediente:
            - 0,5 l apă
            - Morcov – 250 grame
            Mod de preparare:
            1. Taie legumele.
            2. Fierbe-le.
        """.trimIndent())
        assertEquals("Supă de legume", recipe.title)
        assertEquals("30", recipe.minutes)
        assertEquals(2, recipe.servings)
        assertEquals(listOf(RecipeIngredientDraft("apă", "0.5", "l"), RecipeIngredientDraft("Morcov", "250", "g")), recipe.ingredients)
        assertEquals("Taie legumele.\nFierbe-le.", recipe.steps)
    }

    @Test fun preservesHouseholdMeasuresAndAllowsAnUnspecifiedQuantity() {
        val draft = parseRecipeText("Prăjitură\nIngrediente:\n- 2 căni făină\n- Sare după gust\n- Un vârf de cuțit scorțișoară\n- Lapte - 2 linguri\n- Vanilie\nPași:\nAmestecă.")
        assertEquals(listOf("făină", "Sare", "scorțișoară", "Lapte", "Vanilie"), draft.ingredients.map { it.name })
        assertEquals(listOf("2 căni", "după gust", "Un vârf de cuțit", "2 linguri", "Nespecificată"), draft.ingredients.map { it.quantityForSave() })
        assertEquals("", draft.minutes)
        assertThrows(IllegalArgumentException::class.java) { parseRecipeText("Prăjitură\n- 2 căni făină\nAmestecă.") }
    }

    @Test fun onlineDraftKeepsSourceMeasureAndEstimatedTime() {
        val draft = AiImportedRecipe("Supă", 25, 2, listOf(
            AiImportedIngredient("Sare", "", "buc", "o linguriță de sare", "o linguriță"),
            AiImportedIngredient("Piper", "", "buc", "Piper"),
        ), listOf("Fierbe supa."), emptyList(), minutes_estimated = true).draft()
        assertTrue(draft.minutesEstimated)
        assertEquals("25", draft.minutes)
        assertEquals(listOf("o linguriță", "Nespecificată"), draft.ingredients.map { it.quantityForSave() })
    }

    @Test fun parsesEggCountsAndLinesWithTheAmountAfterTheIngredient() {
        val draft = parseRecipeText("Omletă\nIngrediente:\n2 ouă\n2 roșii cherry - 100 g\nPași:\nAmestecă.")
        assertEquals(listOf(RecipeIngredientDraft("ouă", "2", "buc"),
            RecipeIngredientDraft("2 roșii cherry", "100", "g")), draft.ingredients)
    }

    @Test fun preservesUnitsAndRejectsUnsupportedAmounts() {
        assertEquals(RecipeIngredientDraft("Orez", "200", "kg"), ingredientDraft(Ingredient("Orez", "200 kg")))
        assertEquals("0.00001", recipeAmount("0,0000100"))
        assertNull(recipeAmount("100000.00001"))
        assertNull(recipeAmount("1e2"))
    }
}
