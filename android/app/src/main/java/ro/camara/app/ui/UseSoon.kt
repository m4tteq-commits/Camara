package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import ro.camara.app.UiState
import ro.camara.app.data.ConnectionConfig
import ro.camara.app.data.Recipe
import ro.camara.app.data.RecipeMath
import ro.camara.app.data.UseSoonIdea
import java.time.LocalDate

@Composable
internal fun UseSoonScreen(state: UiState, fetch: suspend (ConnectionConfig) -> List<UseSoonIdea>, onRecipe: (Recipe) -> Unit, modifier: Modifier) {
    val snapshot = state.snapshot
    val today = LocalDate.now()
    val due = RecipeMath.expiring(snapshot.pantry, today)
    val fallback = remember(snapshot, today) { RecipeMath.expiringSavedIdeas(snapshot, today) }
    val config = state.config?.takeIf { snapshot.stock_tools && state.online && state.pending.isEmpty() && due.isNotEmpty() }
    var remote by remember(config, snapshot.pantry, snapshot.personal_recipes, today) { mutableStateOf<List<UseSoonIdea>?>(null) }
    var loading by remember(config, snapshot.pantry, snapshot.personal_recipes, today) { mutableStateOf(config != null) }
    var failed by remember(config, snapshot.pantry, snapshot.personal_recipes, today) { mutableStateOf(false) }
    LaunchedEffect(config, snapshot.pantry, snapshot.personal_recipes, today) {
        if (config != null) {
            try {
                remote = fetch(config)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
            } finally {
                loading = false
            }
        }
    }
    val ideas = (remote.orEmpty() + fallback).distinctBy { it.recipe.id }.distinctBy { RecipeMath.normalize(it.recipe.title) }.take(8)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Gătește cu ce expiră", "Rețete care folosesc alimente cu termen în următoarele 3 zile.") }
        if (due.isNotEmpty()) item {
            Note("La termen apropiat · ${due.map { it.name }.distinct().take(5).joinToString(", ")}")
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (due.isNotEmpty() && !loading && (config == null || failed)) item {
            Note("Sugestiile disponibile acum sunt din rețetele deja salvate.")
        }
        if (!loading && ideas.isEmpty()) item {
            EmptyPanel(Icons.Outlined.RestaurantMenu, "Fără sugestii acum",
                if (due.isEmpty()) "Nu ai alimente cu termen apropiat în cămară." else "Nu am găsit o rețetă care să folosească alimentele disponibile în unitățile introduse.")
        }
        items(ideas, key = { it.recipe.id }) { idea ->
            Card(onClick = { onRecipe(idea.recipe) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(Icons.Outlined.RamenDining)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(idea.recipe.title, style = MaterialTheme.typography.titleMedium)
                        Text("Folosește ${idea.foods.joinToString(", ")}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                        val missing = idea.recipe.missing_ingredients.size
                        val detail = if (missing > 0) " · ${countLabel(missing, "ingredient lipsă", "ingrediente lipsă")}" else ""
                        Text("${idea.recipe.minutes} min$detail", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }
        }
    }
}
