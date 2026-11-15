package ro.camara.app.data

import java.math.BigDecimal
import kotlinx.serialization.Serializable

internal const val RECIPE_TEXT_LIMIT = 16_000
internal const val UNSPECIFIED_QUANTITY = "Nespecificată"

@Serializable
internal data class RecipeIngredientDraft(
    val name: String = "",
    val amount: String = "",
    val unit: String = "buc",
    val original: String? = null,
)

internal data class RecipeTextDraft(
    val title: String,
    val minutes: String,
    val servings: Int,
    val ingredients: List<RecipeIngredientDraft>,
    val steps: String,
    val notes: List<String>,
    val minutesEstimated: Boolean = false,
)

@Serializable
internal data class AiImportedIngredient(val name: String, val amount: String, val unit: String, val original: String,
    val quantity_text: String = "")

@Serializable
internal data class AiImportedRecipe(
    val title: String, val minutes: Int?, val servings: Int?, val ingredients: List<AiImportedIngredient>,
    val steps: List<String>, val notes: List<String>, val minutes_estimated: Boolean = false,
) {
    fun draft() = RecipeTextDraft(title, minutes?.toString().orEmpty(), servings ?: 1,
        ingredients.map { RecipeIngredientDraft(it.name, it.quantity_text.ifBlank { it.amount }, it.unit, it.original.ifBlank { null }) },
        steps.joinToString("\n"), notes + if (servings == null) listOf("Textul nu precizează porțiile. Verifică valoarea afișată.") else emptyList(),
        minutes_estimated)
}

@Serializable
internal data class RecipeImportText(val text: String)

internal const val RECIPE_PDF_MAX_BYTES = 20L * 1024 * 1024
internal const val RECIPE_PDF_MAX_PAGES = 4

@Serializable
internal data class PdfRecipes(val recipes: List<AiImportedRecipe>, val more_recipes: Boolean)

@Serializable
internal data class PdfUpload(val id: String, val pages: Int)

@Serializable
internal data class RecipeAdviceRequest(val goal: String)

@Serializable
internal data class RecipeTip(val step: Int, val suggestion: String)

@Serializable
internal data class RecipeAdvice(val missing: List<String>, val tips: List<RecipeTip>)

private val unitsByName = mapOf(
    "buc" to "buc", "bucata" to "buc", "bucati" to "buc",
    "g" to "g", "gr" to "g", "gram" to "g", "grame" to "g",
    "kg" to "kg", "kilogram" to "kg", "kilograme" to "kg",
    "ml" to "ml", "mililitru" to "ml", "mililitri" to "ml",
    "l" to "l", "litru" to "l", "litri" to "l",
)
private val bullet = Regex("""^(?:[-*•]\s+|\d{1,3}[.)]\s+)""")
private const val amountPattern = """[0-9]+(?:[.,][0-9]+)?"""
private val quantityFirst = Regex("""^($amountPattern)\s*([\p{L}.]+)\s+(?:de\s+)?(.+)$""", RegexOption.IGNORE_CASE)
private val countFirst = Regex("""^($amountPattern)\s+(.+)$""")
private val quantityLast = Regex("""^(.+?)\s*[-–—:]\s*($amountPattern)\s*([\p{L}.]+)$""")
private val storedQuantity = Regex("""^($amountPattern)\s*(buc|g|kg|ml|l)$""", RegexOption.IGNORE_CASE)
private val time = Regex("""^([0-9]{1,3})(?:\s*(?:min\.?|minute))?$""", RegexOption.IGNORE_CASE)
private val portions = Regex("""^([0-9]{1,2})(?:\s*porti[ei])?$""")
private val amountOnly = Regex(amountPattern)
private val household = """(?:linguri[țţt][ăa]|linguri[țţt]e|lingur[ăa]|linguri|can[ăa]|c[ăa]ni|ce[șşs]ti?|cea[șşs]c[ăa]|v[âa]rf(?:uri)? de cu[țţt]it|praf|pumni?|pic[ăa]turi?|felii|felie|leg[ăa]tur[ăa])"""
private val householdMeasure = """(?:(?:$amountPattern|\d+/\d+|[¼½¾]|o|un|dou[ăa]|trei|jum[ăa]tate(?: de)?)\s+)?$household"""
private val householdFirst = Regex("""^($householdMeasure)\s+(?:de\s+)?(.+)$""", RegexOption.IGNORE_CASE)
private val householdLast = Regex("""^(.+?)(?:\s*[-–—:]\s*|\s+)($householdMeasure|dup[ăa] gust)$""", RegexOption.IGNORE_CASE)

internal fun recipeAmount(raw: String): String? {
    val text = raw.trim()
    if (text.length > 90 || !amountOnly.matches(text)) return null
    val decimal = text.replace(',', '.').toBigDecimalOrNull() ?: return null
    if (decimal <= BigDecimal.ZERO || decimal > BigDecimal.valueOf(100000)) return null
    return decimal.stripTrailingZeros().toPlainString()
}

internal fun ingredientDraft(ingredient: Ingredient): RecipeIngredientDraft {
    if (ingredient.quantity == UNSPECIFIED_QUANTITY) return RecipeIngredientDraft(name = ingredient.name)
    val match = storedQuantity.matchEntire(ingredient.quantity.trim())
        ?: return RecipeIngredientDraft(name = ingredient.name, amount = ingredient.quantity, original = ingredient.quantity)
    return RecipeIngredientDraft(ingredient.name, match.groupValues[1], match.groupValues[2].lowercase())
}

internal fun RecipeIngredientDraft.quantityForSave(): String? {
    val value = amount.trim()
    if (value.isEmpty()) return UNSPECIFIED_QUANTITY
    if (amountOnly.matches(value)) return recipeAmount(value)?.let { "$it $unit" }
    val measured = storedQuantity.matchEntire(value)
    if (measured != null && recipeAmount(measured.groupValues[1]) == null) return null
    return value.takeIf { it.length <= 100 }
}

private fun parseIngredient(line: String): RecipeIngredientDraft {
    val text = line.replaceFirst(bullet, "").trim()
    val first = quantityFirst.matchEntire(text)
    val last = quantityLast.matchEntire(text)
    val count = countFirst.matchEntire(text)
    val (amount, unitText, name) = when {
        last != null && RecipeMath.normalize(last.groupValues[3].trimEnd('.')) in unitsByName ->
            last.groupValues.let { Triple(it[2], it[3], it[1]) }
        first != null && RecipeMath.normalize(first.groupValues[2].trimEnd('.')) in unitsByName ->
            first.groupValues.let { Triple(it[1], it[2], it[3]) }
        count != null && RecipeMath.normalize(count.groupValues[2]) in setOf("oua", "ou") ->
            count.groupValues.let { Triple(it[1], "buc", it[2]) }
        else -> {
            val prefix = householdFirst.matchEntire(text)
            val suffix = householdLast.matchEntire(text)
            if (prefix != null) return RecipeIngredientDraft(prefix.groupValues[2], prefix.groupValues[1], original = line)
            if (suffix != null) return RecipeIngredientDraft(suffix.groupValues[1], suffix.groupValues[2], original = line)
            return RecipeIngredientDraft(name = text, original = line)
        }
    }
    val unit = unitsByName[RecipeMath.normalize(unitText.trimEnd('.'))]
    val validAmount = recipeAmount(amount)
    return if (unit != null && validAmount != null && name.isNotBlank()) RecipeIngredientDraft(name.trim(), validAmount, unit)
    else RecipeIngredientDraft(name = if (unit != null) name.trim() else "", unit = unit ?: "buc", original = line)
}

private enum class Section { Header, Ingredients, Steps }

internal fun parseRecipeText(text: String): RecipeTextDraft {
    require(text.isNotBlank() && text.length <= RECIPE_TEXT_LIMIT) { "Lipește o rețetă de maximum $RECIPE_TEXT_LIMIT de caractere." }
    var title = ""
    var minutes = ""
    var servings = 1
    var section = Section.Header
    val ingredients = mutableListOf<RecipeIngredientDraft>()
    val steps = mutableListOf<String>()
    val notes = mutableListOf<String>()
    for (raw in text.lineSequence()) {
        val line = raw.trim().trimStart('\uFEFF').replace('\u00A0', ' ').trim()
        if (line.isEmpty()) continue
        val label = RecipeMath.normalize(line.substringBefore(':'))
        val value = line.substringAfter(':', "").trim()
        when (label) {
            "ingrediente", "ingredientele" -> {
                section = Section.Ingredients
                if (value.isNotEmpty()) ingredients += parseIngredient(value)
            }
            "pasi", "mod de preparare", "preparare", "instructiuni" -> {
                section = Section.Steps
                if (value.isNotEmpty()) steps += value.replaceFirst(bullet, "")
            }
            "titlu" -> title = value
            "timp", "durata", "timp de preparare", "timp total" -> {
                minutes = time.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..180 }?.toString().orEmpty()
                if (minutes.isEmpty()) notes += "Verifică timpul din text · $line"
            }
            "portii", "numar de portii" -> {
                val count = portions.matchEntire(RecipeMath.normalize(value))?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..8 }
                if (count == null) notes += "Verifică porțiile din text · $line" else servings = count
            }
            else -> when (section) {
                Section.Header -> { if (title.isEmpty()) title = line else notes += line }
                Section.Ingredients -> ingredients += parseIngredient(line)
                Section.Steps -> steps += line.replaceFirst(bullet, "")
            }
        }
    }
    require(ingredients.isNotEmpty() && steps.isNotEmpty()) { "Pune ingredientele și pașii sub titlurile Ingrediente și Pași, pe rânduri separate." }
    require(ingredients.size <= 20) { "Redu lista din text la cel mult 20 de ingrediente." }
    require(steps.size <= 12) { "Redu textul la cel mult 12 pași." }
    return RecipeTextDraft(title, minutes, servings, ingredients, steps.joinToString("\n"), notes)
}
