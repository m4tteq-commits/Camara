package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ro.camara.app.data.*

internal fun filterPersonalRecipes(recipes: List<PersonalRecipe>, query: String, category: String, maxMinutes: Int?): List<PersonalRecipe> {
    val needle = RecipeMath.normalize(query)
    return recipes.filter {
        (category == "all" || it.category == category) && (maxMinutes == null || it.minutes <= maxMinutes) &&
            (RecipeMath.normalize(it.title).contains(needle) || it.ingredients.any { ingredient -> RecipeMath.normalize(ingredient.name).contains(needle) })
    }.sortedBy { RecipeMath.normalize(it.title) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PersonalRecipesScreen(recipes: List<PersonalRecipe>, enabled: Boolean, onOpen: (Recipe) -> Unit,
    onEdit: (PersonalRecipe?) -> Unit, onDelete: (PersonalRecipe) -> Unit, modifier: Modifier, onImport: () -> Unit,
    selectedRecipeId: String?, onPdfImport: (() -> Unit)?) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("all") }
    var duration by rememberSaveable { mutableStateOf("all") }
    val visible = remember(recipes, query, category, duration) {
        filterPersonalRecipes(recipes, query, category, duration.toIntOrNull())
    }
    LazyColumn(modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onImport, enabled = enabled) { Text("Lipește o rețetă") }
                    if (onPdfImport != null) OutlinedButton(onClick = onPdfImport, enabled = enabled) { Text("Importă PDF") }
                    OutlinedButton(onClick = { onEdit(null) }, enabled = enabled) { Text("Adaugă manual") }
                }
                if (recipes.isNotEmpty()) {
                    BrowseControls(query, { query = it }, "Caută după rețetă sau ingredient", category,
                        listOf("all" to "Toate", "breakfast" to "Mic dejun", "main" to "Fel principal", "snack" to "Gustare"), { category = it })
                    ValueChoice(duration, listOf("all" to "Orice durată", "15" to "Cel mult 15 min",
                        "30" to "Cel mult 30 min", "60" to "Cel mult 60 min")) { duration = it }
                }
            }
        }
        if (visible.isEmpty()) item {
            EmptyPanel(if (recipes.isEmpty()) Icons.Outlined.RestaurantMenu else Icons.Outlined.Search,
                if (recipes.isEmpty()) "Nicio rețetă personală" else "Niciun rezultat",
                if (recipes.isEmpty()) "Adaugă sau importă o rețetă ca să o găsești aici." else "Schimbă căutarea sau filtrele.")
        }
        items(visible, key = { it.id }) { recipe ->
            OutlinedCard(onClick = { onOpen(recipe.recipe()) }, colors = CardDefaults.outlinedCardColors(
                containerColor = if (recipe.id == selectedRecipeId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(recipe.title, style = MaterialTheme.typography.titleLarge)
                    val time = if (recipe.minutes_estimated) "${recipe.minutes} min (estimat de AI)" else "${recipe.minutes} min"
                    Text("$time · ${recipe.servings} porții · ${recipe.ingredients.size} ingrediente")
                    Row {
                        TextButton(onClick = { onEdit(recipe) }, enabled = enabled) { Text("Editează") }
                        TextButton(onClick = { onDelete(recipe) }, enabled = enabled) { Text("Elimină") }
                    }
                }
            }
        }
    }
}

@Serializable
private data class RecipeRow(val key: String = UUID.randomUUID().toString(), val draft: RecipeIngredientDraft = RecipeIngredientDraft())

private val rowSerializer = ListSerializer(RecipeRow.serializer())
private val recipeRowsSaver = Saver<List<RecipeRow>, String>(
    save = { ActionRules.json.encodeToString(rowSerializer, it) },
    restore = { ActionRules.json.decodeFromString(rowSerializer, it) },
)

@Composable
internal fun PersonalRecipeDialog(editing: PersonalRecipe?, enabled: Boolean, aliases: Map<String, String>, onDismiss: () -> Unit,
    importDraft: RecipeTextDraft? = null, importId: String? = null, saveError: String? = null, onSave: (PersonalRecipe) -> Unit) {
    val id = rememberSaveable(editing?.id, importId) { editing?.id ?: importId ?: UUID.randomUUID().toString() }
    var title by rememberSaveable(id) { mutableStateOf(importDraft?.title ?: editing?.title.orEmpty()) }
    var minutes by rememberSaveable(id) { mutableStateOf(importDraft?.minutes ?: editing?.minutes?.toString() ?: "20") }
    var estimated by rememberSaveable(id) { mutableStateOf(importDraft?.minutesEstimated ?: editing?.minutes_estimated ?: false) }
    var servings by rememberSaveable(id) { mutableIntStateOf(importDraft?.servings ?: editing?.servings ?: 1) }
    var category by rememberSaveable(id) { mutableStateOf(editing?.category ?: "main") }
    var diet by rememberSaveable(id) { mutableStateOf(editing?.diet ?: "any") }
    var equipment by rememberSaveable(id) { mutableStateOf(editing?.equipment ?: emptyList()) }
    var steps by rememberSaveable(id) { mutableStateOf(importDraft?.steps ?: editing?.steps?.joinToString("\n").orEmpty()) }
    var rows by rememberSaveable(id, stateSaver = recipeRowsSaver) {
        val ingredients = importDraft?.ingredients ?: editing?.ingredients?.map(::ingredientDraft)
        mutableStateOf(ingredients?.map { RecipeRow(draft = it) } ?: listOf(RecipeRow()))
    }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (enabled) onDismiss() }, title = { Text(if (importDraft == null) "Rețeta mea" else "Verifică rețeta importată") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            importDraft?.notes?.forEach { Note(it, warning = true) }
            OutlinedTextField(title, { title = it.take(100) }, label = { Text("Titlu") })
            OutlinedTextField(minutes, { minutes = it.take(3); estimated = false },
                label = { Text(if (estimated) "Timp estimat de AI · minute" else "Timp total · minute") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            PortionPicker(servings, enabled) { servings = it }
            ValueChoice(category, listOf("breakfast" to "Mic dejun", "main" to "Fel principal", "snack" to "Gustare"), enabled) { category = it }
            ValueChoice(diet, listOf("any" to "Orice", "vegetarian" to "Vegetarian", "vegan" to "Vegan"), enabled) { diet = it }
            Text("Echipamente necesare", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Plită", "Tigaie", "Oală", "Cuptor", "Microunde").forEach { value -> FilterChip(value in equipment, { equipment = if (value in equipment) equipment - value else equipment + value }, label = { Text(value) }) }
            }
            Text("Ingrediente pentru $servings porții", style = MaterialTheme.typography.titleMedium)
            rows.forEachIndexed { index, row -> key(row.key) {
                val draft = row.draft
                fun update(value: RecipeIngredientDraft) {
                    rows = rows.mapIndexed { i, current -> if (i == index) current.copy(draft = value) else current }
                }
                OutlinedCard { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    draft.original?.let { Note("Din text · $it", warning = draft.name.isBlank() || draft.quantityForSave() == null) }
                    OutlinedTextField(draft.name, { update(draft.copy(name = it.take(100))) }, label = { Text("Ingredient") })
                    OutlinedTextField(draft.amount, { update(draft.copy(amount = it.take(100))) }, label = { Text("Cantitate (opțională)") },
                        singleLine = true, placeholder = { Text(UNSPECIFIED_QUANTITY) })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (recipeAmount(draft.amount) != null) ValueChoice(draft.unit, units, enabled) { update(draft.copy(unit = it)) }
                        IconButton(onClick = { rows = rows.filter { it.key != row.key } }, enabled = rows.size > 1) { Icon(Icons.Outlined.Close, "Elimină ingredientul") }
                    }
                } }
            } }
            TextButton(onClick = { rows = rows + RecipeRow() }, enabled = rows.size < 20) { Text("Adaugă ingredient") }
            OutlinedTextField(steps, { steps = it.take(12000) }, label = { Text("Pași · câte unul pe rând") }, minLines = 4)
            (error ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        error = null
        runCatching {
            val time = minutes.toIntOrNull()
            require(title.trim().length in 1..100 && time != null && time in 1..180) { "Completează titlul și timpul între 1 și 180 de minute." }
            val ingredients = rows.map { row ->
                val draft = row.draft
                val quantity = draft.quantityForSave()
                require(draft.name.trim().length in 1..100 && quantity != null) { "Verifică numele ingredientului și cantitățile numerice." }
                Ingredient(draft.name.trim(), quantity)
            }
            require(ingredients.map { RecipeMath.canonical(it.name, aliases) }.distinct().size == ingredients.size) { "Unește ingredientele cu același nume într-o singură poziție." }
            val instructions = steps.lines().map { it.trim() }.filter { it.isNotBlank() }
            val distinct = instructions.map { it.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ") }.distinct()
            require(instructions.size in 1..12 && instructions.all { it.length <= 1000 } && distinct.size == instructions.size) { "Introdu între 1 și 12 pași diferiți, maximum 1000 de caractere fiecare." }
            onSave(PersonalRecipe(id, title.trim(), time, servings, ingredients, instructions, equipment, diet, category,
                Instant.now().toString(), minutes_estimated = estimated))
        }.onFailure { error = it.message }
    }, enabled = enabled) { Text("Salvează") } }, dismissButton = {
        TextButton(onClick = onDismiss, enabled = enabled) { Text(if (importDraft == null) "Anulează" else "Înapoi la text") }
    })
}

@Composable
internal fun RecipeImportDialog(enabled: Boolean, aliases: Map<String, String>, onDismiss: () -> Unit,
    saveError: String?, onAiImport: (suspend (String) -> AiImportedRecipe)?, onSave: (PersonalRecipe) -> Unit) {
    val id = rememberSaveable { UUID.randomUUID().toString() }
    var text by rememberSaveable { mutableStateOf("") }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var sizeRejected by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(false) }
    var aiDraftJson by rememberSaveable { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    if (reviewing) {
        val draft = remember(text, aiDraftJson) {
            aiDraftJson?.let { ActionRules.json.decodeFromString(AiImportedRecipe.serializer(), it).draft() } ?: parseRecipeText(text)
        }
        PersonalRecipeDialog(null, enabled, aliases, onDismiss = { reviewing = false }, importDraft = draft,
            importId = id, saveError = saveError, onSave = onSave)
        return
    }
    fun update(candidate: String) {
        sizeRejected = candidate.length > RECIPE_TEXT_LIMIT
        if (!sizeRejected) { text = candidate; error = null; aiDraftJson = null }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Importă o rețetă din text") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Lipește textul rețetei, apoi verifică ingredientele și pașii înainte de salvare.")
            OutlinedButton(onClick = {
                val pasted = clipboard.getText()?.text
                if (pasted.isNullOrBlank()) error = "Copiază mai întâi textul rețetei." else update(pasted)
            }, enabled = !waiting) { Text("Lipește din clipboard") }
            OutlinedTextField(text, ::update, modifier = Modifier.fillMaxWidth(), label = { Text("Textul rețetei") }, minLines = 5, maxLines = 9,
                enabled = !waiting,
                placeholder = { Text("Orez cu legume\nTimp: 30 min\nPorții: 2\nIngrediente:\n200 g orez\nPași:\nFierbe orezul.") })
            if (onAiImport != null) {
                Button(onClick = {
                    waiting = true
                    error = null
                    scope.launch {
                        try {
                            val draft = onAiImport(text)
                            aiDraftJson = ActionRules.json.encodeToString(AiImportedRecipe.serializer(), draft)
                            reviewing = true
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Importul cu AI nu a reușit." }
                        finally { waiting = false }
                    }
                }, enabled = enabled && !waiting && !sizeRejected && text.isNotBlank()) {
                    Text(if (waiting) "Se citește rețeta…" else "Extrage cu AI")
                }
                Text("La extragerea cu AI, textul este trimis furnizorului online configurat.", style = MaterialTheme.typography.bodySmall)
            }
            val message = if (sizeRejected) "Textul depășește limita de $RECIPE_TEXT_LIMIT de caractere." else error
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            runCatching { parseRecipeText(text) }.onSuccess { aiDraftJson = null; reviewing = true }.onFailure { error = it.message }
        }, enabled = enabled && !waiting && !sizeRejected && text.isNotBlank()) { Text("Verifică local") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } })
}
