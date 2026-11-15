package ro.camara.app.data

import org.junit.Assert.*
import org.junit.Test

class RecipeWebImportTest {
    private fun script(json: String) = """<script type="application/ld+json">$json</script>"""

    @Test fun readsRecipeInsideGraph() {
        val page = script("""
            {"@graph":[{"@type":"Organization"},{"@type":["Thing","https://schema.org/Recipe"],
                "name":"Terci &amp; mere", "totalTime":"PT25M", "recipeYield":[2],
                "recipeIngredient":[{"value":"&frac12; cană ovăz"},"un măr"],
                "recipeInstructions":[{"itemListElement":[{"text":"<b>Fierbe</b> ovăzul."},{"text":"Adaugă mărul."}]}]}]}
        """.trimIndent())
        val text = RecipeWebImport.extract(page)
        assertEquals("Terci & mere\nTimp: 25 min\nPorții: 2\nIngrediente:\n½ cană ovăz\nun măr\nPași:\nFierbe ovăzul.\nAdaugă mărul.", text)
    }

    @Test fun ignoresBrokenScriptsAndPagesWithoutCompleteRecipes() {
        val complete = script("""{"@type":"Recipe","recipeIngredient":["un ou"],"recipeInstructions":"Bate oul."}""")
        assertNotNull(RecipeWebImport.extract(script("{") + complete))
        assertNull(RecipeWebImport.extract(script("""{"@type":"Recipe","name":"Fără pași"}""")))
        assertNull(RecipeWebImport.extract("<p>Rețetă fără fișă structurată</p>"))
        assertNull(RecipeWebImport.extract(" ".repeat(2_000_001) + complete))
    }
}
