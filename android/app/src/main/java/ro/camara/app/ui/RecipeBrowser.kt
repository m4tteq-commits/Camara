package ro.camara.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.camara.app.UiState
import ro.camara.app.data.*

internal fun filterSavedRecipes(recipes: List<Recipe>, query: String, maxMinutes: Int?): List<Recipe> {
    val needle = RecipeMath.normalize(query)
    return recipes.filter { recipe ->
        (maxMinutes == null || recipe.minutes <= maxMinutes) &&
            (RecipeMath.normalize(recipe.title).contains(needle) ||
                recipe.ingredients.any { RecipeMath.normalize(it.name).contains(needle) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesScreen(state: UiState, canWrite: Boolean, onGenerate: (RecipeRequest) -> Unit, onRecipe: (Recipe) -> Unit, modifier: Modifier,
    onAiMode: (String) -> Unit, onDownloadModel: () -> Unit, onPersonal: (PersonalRecipe?) -> Unit, onDeletePersonal: (PersonalRecipe) -> Unit,
    onSuggest: (RecipeRequest) -> Unit, onPreview: (Recipe) -> Unit,
    onImport: () -> Unit = {}, selectedRecipeId: String? = null, onTabChange: () -> Unit = {},
    onPdfImport: (() -> Unit)? = null, onSources: (() -> Unit)? = null,
    onFavorite: ((PersonalRecipe) -> Unit)? = null) {
    var library by rememberSaveable { mutableStateOf(false) }
    val libraryState = rememberSaveableStateHolder()
    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = if (library) 1 else 0) {
            Tab(!library, onClick = { if (library) { library = false; onTabChange() } }, text = { Text("Sugestii") })
            Tab(library, onClick = { if (!library) { library = true; onTabChange() } }, text = { Text("Rețetele mele") })
        }
        val page = Modifier.weight(1f).fillMaxWidth()
        libraryState.SaveableStateProvider(if (library) "personal" else "suggestions") {
            if (library) PersonalRecipesScreen(state.snapshot.personal_recipes, canWrite, onRecipe, onPersonal, onDeletePersonal, page,
                onImport, selectedRecipeId, onPdfImport, onSources, onFavorite)
            else RecipeSuggestions(state, canWrite, onGenerate, onRecipe, page, onAiMode, onDownloadModel, selectedRecipeId, onSuggest, onPreview)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeSuggestions(state: UiState, canWrite: Boolean, onGenerate: (RecipeRequest) -> Unit, onRecipe: (Recipe) -> Unit,
    modifier: Modifier, onAiMode: (String) -> Unit, onDownloadModel: () -> Unit, selectedRecipeId: String?,
    onSuggest: (RecipeRequest) -> Unit, onPreview: (Recipe) -> Unit) {
    val list = rememberLazyListState()
    var minutes by rememberSaveable { mutableFloatStateOf(30f) }
    var servings by rememberSaveable { mutableIntStateOf(1) }
    var preferences by rememberSaveable { mutableStateOf("") }
    var required by rememberSaveable { mutableStateOf("") }
    var excluded by rememberSaveable { mutableStateOf("") }
    var equipment by rememberSaveable { mutableStateOf(listOf("Plită", "Tigaie")) }
    var pantryOnly by rememberSaveable { mutableStateOf(false) }
    var diet by rememberSaveable { mutableStateOf("any") }
    var category by rememberSaveable { mutableStateOf("any") }
    var filters by rememberSaveable { mutableStateOf(false) }
    var engine by rememberSaveable { mutableStateOf(false) }
    var showAiData by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var savedFilter by rememberSaveable { mutableStateOf("all") }
    val saved = state.snapshot.recipes
    val visible = remember(saved, query, savedFilter) { filterSavedRecipes(saved, query, savedFilter.toIntOrNull()) }
    fun ingredients(text: String) = text.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
    val requiredIngredients = ingredients(required)
    val excludedIngredients = ingredients(excluded)
    val validIngredients = listOf(requiredIngredients, excludedIngredients).all { names ->
        names.size <= 8 && names.all { it.length <= 100 }
    }
    val request = RecipeRequest(minutes.toInt(), servings, equipment, preferences, pantryOnly, diet, category,
        requiredIngredients, excludedIngredients)
    val groqModel = state.cloudProviders["groq"]
    val openRouterModel = state.cloudProviders["openrouter"]
    val onlineAiAvailable = groqModel != null || openRouterModel != null
    val cloudSelected = state.aiMode == "groq"
    val aiAvailable = when (state.aiMode) {
        "device" -> state.localModelReady
        "server" -> state.aiStatus?.available != false
        else -> onlineAiAvailable
    }
    val engineSummary = when {
        cloudSelected -> when {
            groqModel != null -> "GroqCloud · $groqModel"
            openRouterModel != null -> "OpenRouter · rezervă"
            else -> "GroqCloud · configurare necesară"
        }
        state.aiMode == "device" -> if (state.localModelReady) "Pe dispozitiv · pregătit" else "Pe dispozitiv · model necesar"
        else -> "Ollama pe ZimaOS"
    }
    val useSaved = !state.online || state.pending.isNotEmpty()
    val quick = state.quickSuggestions?.takeIf { it.request == request && (!useSaved || !it.fromCatalog) }
    val snapshot = state.snapshot
    var offlineIdeas by remember(useSaved, snapshot, request) { mutableStateOf<List<Recipe>?>(null) }
    LaunchedEffect(useSaved, snapshot, request) {
        if (useSaved && request.equipment.isNotEmpty()) {
            offlineIdeas = withContext(Dispatchers.Default) { savedRecipeSuggestions(snapshot, request) }
        }
    }
    val ideas = quick?.recipes ?: offlineIdeas
    val autoSuggest = state.online && !useSaved && !aiAvailable && equipment.isNotEmpty() &&
        validIngredients && !needsServerIngredientCheck(request)
    LaunchedEffect(state.config, autoSuggest) {
        if (autoSuggest && quick == null) onSuggest(request)
    }
    LazyColumn(modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(screenGutter, 12.dp, screenGutter, 28.dp),
        verticalArrangement = Arrangement.spacedBy(cardGap)) {
        item { PageHeader("Ce gătim azi?", "Idei pornind de la alimentele tale.") }
        item {
            ExpandablePanel("Motorul AI", engineSummary, engine, { engine = !engine },
                modifier = Modifier.padding(top = sectionGap - cardGap), icon = Icons.Outlined.AutoAwesome, compact = true) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(cloudSelected, { onAiMode("groq") }, label = { Text("GroqCloud") },
                        enabled = !state.generating && onlineAiAvailable)
                    FilterChip(state.aiMode == "server", { onAiMode("server") }, label = { Text("Pe server") }, enabled = !state.generating)
                    FilterChip(state.aiMode == "device", { onAiMode("device") }, label = { Text("Pe dispozitiv") }, enabled = !state.generating && state.localModelReady)
                }
                if (openRouterModel != null) {
                    Note("OpenRouter este folosit automat când GroqCloud nu poate răspunde.")
                }
                if (!onlineAiAvailable) {
                    Note("Furnizorii online se activează pe ZimaOS, cu o cheie API și un model pentru fiecare.")
                }
                if (!state.localModelReady) {
                    OutlinedButton(onClick = onDownloadModel, enabled = state.online && state.modelDownloadProgress == null && !state.generating) { Text("Descarcă pe dispozitiv · 344 MB") }
                    state.modelDownloadProgress?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Descărcare și verificare: $it%", style = MaterialTheme.typography.bodySmall) }
                }
                if (state.aiMode == "server") state.aiStatus?.let { Note(it.detail, !it.available) }
            }
        }
        item {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest, border = cardOutline()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(cardGap)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Cât timp ai?", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); SmallPill("${minutes.toInt()} min", Icons.Outlined.Schedule) }
                    val durations = listOf(15, 30, 45, 60)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        durations.forEachIndexed { index, value ->
                            SegmentedButton(selected = minutes.toInt() == value, onClick = { minutes = value.toFloat() },
                                enabled = !state.generating, shape = SegmentedButtonDefaults.itemShape(index, durations.size),
                                icon = {}) {
                                Text("$value min", style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                                    textAlign = TextAlign.Center)
                            }
                        }
                    }
                    PortionPicker(servings, !state.generating, outlined = true) { servings = it }
                    OutlinedTextField(preferences, { preferences = it.take(500) }, label = { Text("Ce ți-ar plăcea?") },
                        placeholder = { Text(if (cloudSelected) "De exemplu, o supă rapidă" else "De exemplu, ceva ușor, fără ciuperci") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, enabled = !state.generating, shape = MaterialTheme.shapes.medium)
                    if (cloudSelected && onlineAiAvailable) {
                        Column {
                            TextButton(onClick = { showAiData = !showAiData },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                                Icon(Icons.Outlined.Info, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Ce date primește AI-ul?", Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(4.dp))
                                Icon(if (showAiData) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                            }
                            val details = "Furnizorul online primește titlurile, timpii, ingredientele rețetelor, ce lipsește, ce expiră curând și preferința scrisă."
                            if (LocalCamaraMotion.current) {
                                AnimatedVisibility(showAiData, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) { Note(details) }
                            } else if (showAiData) Note(details)
                        }
                    }
                    PrimaryBrowseAction(if (state.generating) "Se pregătește ideea…" else "Propune o rețetă", Icons.Outlined.AutoAwesome,
                        onClick = { onGenerate(request) },
                        enabled = canWrite && state.online && !state.syncing && !state.suggesting && state.pending.isEmpty() &&
                            state.snapshot.pantry.isNotEmpty() && equipment.isNotEmpty() && validIngredients && aiAvailable,
                        loading = state.generating)
                    if (state.snapshot.pantry.isEmpty()) {
                        Note("Adaugă câteva alimente în Cămară înainte de prima recomandare.")
                    }
                    if (state.generating) Text(when {
                        cloudSelected -> "Se alege o rețetă prin serviciul AI online."
                        state.aiMode == "device" -> "AI-ul de pe dispozitiv alege o rețetă."
                        else -> "Ollama alege o rețetă pe ZimaOS."
                    }, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onSuggest(request) }, modifier = Modifier.fillMaxWidth(),
                        enabled = state.config != null && !state.busy && !state.suggesting && equipment.isNotEmpty() && validIngredients) {
                        Text(if (state.suggesting) "Se caută sugestii…" else "Sugestii rapide")
                    }
                }
            }
        }
        if (ideas != null) {
            item(key = "quick-controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(if (quick?.fromCatalog == true) "Din catalogul Cămara" else "Salvate pe dispozitiv")
                    quick?.note?.let { Note(it) }
                    if (request.preferences.isNotBlank()) Note("Catalogul aplică filtrele de ingrediente. AI-ul poate interpreta și preferințele flexibile.")
                    if (quick?.fromCatalog != true) {
                        if (state.pending.isNotEmpty()) Note("Cantitățile includ modificările în așteptare.")
                        if (request.diet == "any" && request.category == "any" && ideas.isNotEmpty()) {
                            Note("Pentru rețetele din istoricul AI, verifică echipamentul în pașii de gătit.")
                        }
                    }
                }
            }
            if (ideas.isEmpty()) item(key = "quick-empty") {
                EmptyPanel(Icons.Outlined.RestaurantMenu, "Nicio sugestie pentru aceste filtre",
                    if (quick?.fromCatalog == true) "Schimbă filtrele sau adaugă alimente în Cămară."
                    else "Schimbă filtrele sau adaugă o rețetă în biblioteca personală.")
            }
            items(ideas, key = { "quick:${it.id}" }) { recipe ->
                RecipeResultCard(recipe, recipe.id == selectedRecipeId, showMissing = true) { onPreview(recipe) }
            }
        }
        item {
            val ingredientFilterCount = requiredIngredients.size + excludedIngredients.size
            val filterSummary = "${if (pantryOnly) "Doar din cămară" else "Cu o listă de completat"} · ${equipment.size} echipamente" +
                if (ingredientFilterCount > 0) " · $ingredientFilterCount filtre de ingrediente" else ""
            ExpandablePanel("Filtre și echipamente", filterSummary, filters, { filters = !filters }, icon = Icons.Outlined.Tune) {
                OutlinedTextField(required, { required = it.take(808) }, label = { Text("Ingrediente obligatorii") },
                    placeholder = { Text("linte, roșii") }, modifier = Modifier.fillMaxWidth(), enabled = !state.generating)
                OutlinedTextField(excluded, { excluded = it.take(808) }, label = { Text("Ingrediente de evitat") },
                    placeholder = { Text("ciuperci, soia") }, modifier = Modifier.fillMaxWidth(), enabled = !state.generating)
                if (!validIngredients) Note("Folosește cel mult 8 ingrediente pe câmp, separate prin virgule. Fiecare poate avea cel mult 100 de caractere.", true)
                Text("Ajustează timpul", style = MaterialTheme.typography.titleSmall)
                Slider(minutes, { minutes = it }, valueRange = 5f..90f, steps = 16, enabled = !state.generating)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(pantryOnly, { pantryOnly = it }, enabled = !state.generating); Text("Doar ce am în inventar", style = MaterialTheme.typography.bodyMedium) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf("any" to "Orice", "vegetarian" to "Vegetarian", "vegan" to "Vegan").forEach { (key,label) -> FilterChip(diet == key, { diet = key }, label = { Text(label) }, enabled = !state.generating) } }
                Text("Tipul mesei", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf("any" to "Orice masă", "breakfast" to "Mic dejun", "main" to "Fel principal", "snack" to "Gustare").forEach { (key,label) -> FilterChip(category == key, { category = key }, label = { Text(label) }, enabled = !state.generating) } }
                Text("Cu ce poți găti", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf("Plită", "Tigaie", "Oală", "Cuptor", "Microunde").forEach { label -> FilterChip(label in equipment, { equipment = if (label in equipment) equipment - label else equipment + label }, label = { Text(label) }, enabled = !state.generating) } }
            }
        }
        item(key = "saved-controls") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Idei salvate")
                if (saved.isNotEmpty()) BrowseControls(query, { query = it }, "Caută după rețetă sau ingredient", savedFilter,
                    listOf("all" to "Toate", "15" to "≤ 15 min", "30" to "≤ 30 min", "60" to "≤ 60 min"), { savedFilter = it })
            }
        }
        if (visible.isEmpty()) item(key = "saved-empty") {
            EmptyPanel(if (saved.isEmpty()) Icons.Outlined.RestaurantMenu else Icons.Outlined.Search,
                if (saved.isEmpty()) "Nicio idee salvată" else "Niciun rezultat",
                if (saved.isEmpty()) "Recomandările generate apar aici." else "Schimbă căutarea sau filtrul.")
        }
        items(visible, key = { "recipe:${it.id}" }) { recipe ->
            RecipeResultCard(recipe, recipe.id == selectedRecipeId) { onRecipe(recipe) }
        }
    }
}

@Composable
private fun RecipeResultCard(recipe: Recipe, selected: Boolean, showMissing: Boolean = false, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Outlined.RamenDining)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(recipe.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val missing = recipe.missing_ingredients.size
                val stock = if (showMissing) when (missing) {
                    0 -> " · ai ingredientele"
                    1 -> " · lipsește un ingredient"
                    else -> " · lipsesc $missing ingrediente"
                } else ""
                Text("${recipe.minutes} min · ${portionsLabel(recipe.servings)}$stock",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                recommendationReason(recipe, compact = true)?.let { reason ->
                    Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
