package ro.camara.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ro.camara.app.data.PreparedBatch
import ro.camara.app.data.Recipe
import ro.camara.app.data.RecipeMath
import ro.camara.app.data.Snapshot
import ro.camara.app.data.allRecipes

@Composable
internal fun rememberRecipeLibrary(snapshot: Snapshot): List<Recipe> =
    remember(snapshot.personal_recipes, snapshot.recipes, snapshot.plans, snapshot.prepared) { snapshot.allRecipes() }

private data class RecipeOption(val recipe: Recipe, val batch: PreparedBatch?) {
    val id = batch?.let { "batch:${it.id}" } ?: "recipe:${recipe.id}"
    val search = RecipeMath.normalize(recipe.title)
    val label = batch?.let { "Deja gătit · ${recipe.title} · ${it.remaining} porții" } ?: recipe.title
}

@Composable
internal fun RecipePickerDialog(recipes: List<Recipe>, onClose: () -> Unit,
    prepared: List<PreparedBatch> = emptyList(), onSelect: (Recipe, PreparedBatch?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val options = remember(recipes, prepared) {
        recipes.distinctBy { it.id }.map { RecipeOption(it, null) } +
            prepared.filter { it.remaining > 0 }.map { RecipeOption(it.recipe, it) }
    }
    val results = remember(options, query) {
        val needle = RecipeMath.normalize(query)
        options.filter { needle in it.search }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("Alege o rețetă") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it.take(100) }, label = { Text("Caută după titlu") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (results.isEmpty()) Text("Nicio rețetă găsită.")
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(results, key = { it.id }) { option ->
                    TextButton(onClick = { onSelect(option.recipe, option.batch) }, modifier = Modifier.fillMaxWidth()) {
                        Text(option.label)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Închide") } })
}
