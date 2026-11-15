package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ro.camara.app.data.CATALOG_RECIPE_MODEL
import ro.camara.app.data.SAVED_RECIPE_MODEL
import ro.camara.app.data.Recipe
import ro.camara.app.data.cloudAiLabels
import ro.camara.app.data.RecipeAdvice
import ro.camara.app.data.AiImportedRecipe
import ro.camara.app.data.RecipeConversationReply
import ro.camara.app.data.RecipeConversationRequest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun recommendationReason(recipe: Recipe, compact: Boolean = false): String? {
    val stock = when (val count = recipe.missing_ingredients.size) {
        0 -> "Ai toate ingredientele"
        1 -> "Lipsește un ingredient"
        else -> "Lipsesc $count ingrediente"
    }
    val expiry = recipe.expiring_ingredients.firstNotNullOfOrNull { ingredient ->
        val date = runCatching { LocalDate.parse(ingredient.expires_on) }.getOrNull() ?: return@firstNotNullOfOrNull null
        if (date.isBefore(LocalDate.now(java.time.ZoneId.of("Europe/Bucharest")))) return@firstNotNullOfOrNull null
        val display = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ro-RO")))
        "Poți folosi ${ingredient.name} (exp. $display)"
    }
    if (compact) return expiry
    return listOfNotNull(expiry, "${recipe.minutes} min", stock).joinToString(" · ")
}

@Composable
internal fun RecipeDetails(recipe: Recipe, canWrite: Boolean, onGuide: () -> Unit, onShopping: () -> Unit, modifier: Modifier,
    onCook: () -> Unit, onPlan: () -> Unit, onAdvice: (suspend (String) -> RecipeAdvice)? = null,
    onConversation: (suspend (RecipeConversationRequest) -> RecipeConversationReply)? = null,
    onVariant: (AiImportedRecipe) -> Unit = {}, onNotes: (() -> Unit)? = null) {
    var showAdvice by rememberSaveable(recipe.id) { mutableStateOf(false) }
    var conversation by rememberSaveable(recipe.id) { mutableStateOf(false) }
    if (showAdvice && onAdvice != null) RecipeAdviceDialog(onAdvice) { showAdvice = false }
    if (conversation && onConversation != null) RecipeConversationDialog(onConversation, onVariant, canWrite) { conversation = false }
    val source = when {
        recipe.model == "Rețeta mea" -> "Rețetă personală"
        recipe.model == "Din cămară" -> "Sugestie pentru alimentele aproape de termen"
        recipe.model == CATALOG_RECIPE_MODEL -> "Sugestie din catalogul Cămara"
        recipe.model == SAVED_RECIPE_MODEL -> "Rețetă salvată pe dispozitiv"
        recipe.model.startsWith("Plan automat") -> "Rețetă din plan"
        else -> null
    }
    val cloud = cloudAiLabels.values.firstOrNull { recipe.model.startsWith("$it · ") }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(recipe.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Aproximativ ${recipe.minutes} minute · ${recipe.servings} porții", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("${source ?: cloud?.let { "Sugestie AI · $it" } ?: "Sugestie AI"} · verifică ingredientele și pașii înainte de preparare.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (recipe.request_key.isNotBlank() || recipe.model in setOf(CATALOG_RECIPE_MODEL, SAVED_RECIPE_MODEL)) {
                recommendationReason(recipe)?.let { reason ->
                    Spacer(Modifier.height(8.dp))
                    Note(reason)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (recipe.steps.isNotEmpty()) Button(onClick = onGuide) { Text("Mod de gătit") }
            if (onAdvice != null) OutlinedButton(onClick = { showAdvice = true }) { Text("Sfaturi de la AI") }
            if (onConversation != null) OutlinedButton(onClick = { conversation = true }) { Text("Discută cu AI") }
            if (onNotes != null) OutlinedButton(onClick = onNotes, enabled = canWrite) { Text("Notițe și colecții") }
            OutlinedButton(onClick = onCook, enabled = canWrite) { Text("Am gătit") }
            OutlinedButton(onClick = onPlan, enabled = canWrite) { Text("Adaugă în plan") }
            OutlinedButton(onClick = onShopping, enabled = canWrite) { Text("Completează lista de cumpărături") }
        }
        item { Text("Ingrediente", style = MaterialTheme.typography.titleLarge) }
        items(recipe.ingredients) { ingredient ->
            Row(Modifier.fillMaxWidth()) {
                Text(ingredient.name, Modifier.weight(1f))
                Text(ingredient.quantity, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (recipe.missing_ingredients.isNotEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Mai ai nevoie de", style = MaterialTheme.typography.titleMedium)
                    recipe.missing_ingredients.forEach { Text("${it.name} · ${it.quantity}") }
                }
            }
        }
        item { Text("Cum pregătești", style = MaterialTheme.typography.titleLarge) }
        items(recipe.steps.withIndex().toList()) { (index, step) ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("${index + 1}.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(step, Modifier.weight(1f))
            }
        }
        if (source == null) item {
            Text("Propusă de AI în ${recipe.generation_ms / 1000.0} secunde.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecipeAdviceDialog(request: suspend (String) -> RecipeAdvice, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var goal by rememberSaveable { mutableStateOf("simpler") }
    var advice by remember { mutableStateOf<RecipeAdvice?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Sfaturi pentru rețeta ta") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ValueChoice(goal, listOf("simpler" to "Mai simplu", "faster" to "Mai rapid", "pantry" to "Cu ce am în cămară"), !loading) {
                goal = it
                advice = null
                error = null
            }
            Text("AI-ul online primește rețeta. Pentru sugestii din cămară, primește și numele alimentelor disponibile.",
                style = MaterialTheme.typography.bodySmall)
            advice?.let { result ->
                if (result.missing.isNotEmpty()) Note("Din cămară lipsesc total sau parțial · ${result.missing.joinToString(", ")}")
                if (result.tips.isEmpty()) Text("AI-ul nu a găsit o sugestie potrivită.")
                result.tips.forEach { tip ->
                    Text(if (tip.step == 0) tip.suggestion else "Pasul ${tip.step} · ${tip.suggestion}")
                }
            }
            error?.let { Note(it, warning = true) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            loading = true
            advice = null
            error = null
            scope.launch {
                try { advice = request(goal) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Sfaturile nu sunt disponibile momentan." }
                finally { loading = false }
            }
        }, enabled = !loading) { Text(if (loading) "Se pregătesc sfaturile…" else "Cere sfaturi") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Închide") } })
}
