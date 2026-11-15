package ro.camara.app.data

import java.time.LocalDate
import java.time.ZoneId

private val freeTextRestrictions = Regex("\\b(fara|neaparat|obligatoriu|nu vreau|nu mananc|nu suport|nu pot manca|nu pune|alerg\\w*|intoleran\\w*|exclude|evit\\w*|elimin\\w*|scoti|tin post|de post|postesc|trebuie sa fie)\\b")

internal fun needsServerIngredientCheck(request: RecipeRequest) =
    freeTextRestrictions.containsMatchIn(RecipeMath.normalize(request.preferences))

private val ingredientGroups = mapOf(
    "soia" to listOf("soia", "tofu", "tempeh", "edamame", "miso"),
    "ciuperci" to listOf("ciuperci", "ciuperca", "champignon", "pleurotus", "hribi", "shiitake", "galbiori"),
    "lactate" to listOf("lapte", "branza", "cascaval", "mozzarella", "parmezan", "iaurt", "unt", "smantana",
        "kefir", "urda", "telemea", "feta", "ricotta"),
    "oua" to listOf("ou", "oua", "ouale", "maioneza"),
    "nuci" to listOf("nuci", "nuca"),
    "arahide" to listOf("arahide", "alune de pamant"),
)

internal const val CATALOG_RECIPE_MODEL = "Catalog Cămara"
internal const val SAVED_RECIPE_MODEL = "Salvată pe dispozitiv"

internal fun savedRecipeSuggestions(
    snapshot: Snapshot,
    request: RecipeRequest,
    today: LocalDate = LocalDate.now(ZoneId.of("Europe/Bucharest")),
): List<Recipe> {
    if (needsServerIngredientCheck(request)) return emptyList()
    fun ingredientPattern(filter: String): Regex {
        val name = RecipeMath.canonical(filter, snapshot.aliases)
        val alternatives = (ingredientGroups[name] ?: listOf(name)).joinToString("|", transform = Regex::escape)
        return Regex("(?<![\\p{L}\\p{N}_])(?:$alternatives)(?![\\p{L}\\p{N}_])")
    }
    val required = request.required_ingredients.map(::ingredientPattern)
    val excluded = request.excluded_ingredients.map(::ingredientPattern)
    val expiryLimit = today.plusDays(3).toString()
    val equipment = request.equipment.map(RecipeMath::normalize).toSet()
    val personal = snapshot.personal_recipes.filter { recipe ->
        (request.category == "any" || request.category == recipe.category) &&
            (request.diet == "any" || request.diet == recipe.diet || (request.diet == "vegetarian" && recipe.diet == "vegan")) &&
            recipe.equipment.all { RecipeMath.normalize(it) in equipment }
    }.map { it.recipe() }
    // Older AI history has no category or diet metadata and only records a time for the original portions.
    val history = if (request.diet == "any" && request.category == "any") {
        snapshot.recipes.filter { it.servings == request.servings }
    } else emptyList()

    return (personal + history).asSequence()
        .filter { it.minutes <= request.max_minutes }
        .filter { recipe ->
            if (required.isEmpty() && excluded.isEmpty()) return@filter true
            val names = recipe.ingredients.map { RecipeMath.canonical(it.name, snapshot.aliases) }
            required.all { pattern -> names.any(pattern::containsMatchIn) } &&
                excluded.none { pattern -> names.any(pattern::containsMatchIn) }
        }
        .mapNotNull { recipe ->
            val preview = RecipeMath.cooking(snapshot, recipe, request.servings, today)
            val missing = preview.missing
            if (request.pantry_only && missing.isNotEmpty()) return@mapNotNull null
            val ingredients = if (recipe.servings == request.servings) recipe.ingredients else recipe.ingredients.map { ingredient ->
                val (quantity, unit) = RecipeMath.amount(ingredient.quantity) ?: return@map ingredient
                ingredient.copy(quantity = "${RecipeMath.number(quantity * request.servings / recipe.servings)} $unit")
            }
            val expiring = preview.uses.mapNotNull { use ->
                val date = use.item.expires_on ?: return@mapNotNull null
                if (date <= expiryLimit) ExpiringIngredient(use.item.name, date) else null
            }.sortedBy { it.expires_on }.distinctBy { RecipeMath.canonical(it.name, snapshot.aliases) }.take(20)
            recipe.copy(servings = request.servings, ingredients = ingredients, missing_ingredients = missing,
                model = SAVED_RECIPE_MODEL, expiring_ingredients = expiring)
        }
        .sortedWith(compareBy<Recipe> { it.missing_ingredients.size }.thenBy { it.minutes })
        .distinctBy { Triple(RecipeMath.normalize(it.title), it.ingredients, it.steps) }
        .take(5).toList()
}
