package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ro.camara.app.data.AiImportedRecipe
import ro.camara.app.data.RecipeConversationReply
import ro.camara.app.data.RecipeConversationRequest
import ro.camara.app.data.RecipeExchange

internal class RecipeConversationModel : ViewModel() {
    var question by mutableStateOf("")
    var history by mutableStateOf(emptyList<RecipeExchange>())
        private set
    var variant by mutableStateOf<AiImportedRecipe?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    private var activeRecipe: String? = null
    private var job: Job? = null

    fun open(recipe: String) {
        if (activeRecipe == recipe) return
        clear()
        activeRecipe = recipe
    }

    fun clear() {
        job?.cancel()
        job = null
        activeRecipe = null
        history = emptyList()
        question = ""
        variant = null
        error = null
        loading = false
    }

    fun send(request: suspend (RecipeConversationRequest) -> RecipeConversationReply) {
        val sent = question.trim()
        if (loading || sent.isEmpty()) return
        loading = true
        error = null
        job = viewModelScope.launch {
            try {
                val reply = request(RecipeConversationRequest(sent, history.takeLast(6), variant))
                history = (history + RecipeExchange(sent, reply.answer)).takeLast(12)
                reply.variant?.let { variant = it }
                question = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Răspunsul AI nu este disponibil momentan."
            } finally {
                if (job === coroutineContext[Job]) loading = false
            }
        }
    }
}

@Composable
internal fun RecipeConversationDialog(recipeId: String, request: suspend (RecipeConversationRequest) -> RecipeConversationReply,
    onVariant: (AiImportedRecipe) -> Unit, canWrite: Boolean, onDismiss: () -> Unit) {
    val model: RecipeConversationModel = viewModel()
    LaunchedEffect(recipeId) { model.open(recipeId) }
    val history = model.history
    val loading = model.loading
    val scroll = rememberScrollState()
    LaunchedEffect(history, scroll.maxValue) {
        if (history.isNotEmpty() && scroll.maxValue != Int.MAX_VALUE) scroll.scrollTo(scroll.maxValue)
    }
    val dismiss = { model.clear(); onDismiss() }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Discută cu AI") }, text = {
        Column(Modifier.heightIn(max = 540.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (history.isEmpty()) {
                Note("AI-ul online primește rețeta, notițele culinare și numele alimentelor disponibile.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { model.question = "Adaptează rețeta pentru două porții." }, enabled = !loading) {
                        Text("Două porții")
                    }
                    TextButton(onClick = { model.question = "Ce pot înlocui cu ce am în cămară?" }, enabled = !loading) {
                        Text("Cu ce am")
                    }
                }
            }
            history.forEach { exchange ->
                Text(exchange.question, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(exchange.answer, style = MaterialTheme.typography.bodyMedium)
            }
            model.variant?.let { draft ->
                OutlinedButton(onClick = { onVariant(draft) }, enabled = canWrite && !loading) {
                    Text("Verifică și salvează varianta")
                }
            }
            OutlinedTextField(model.question, { model.question = it.take(500) }, label = { Text("Întrebarea ta") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, enabled = !loading)
            model.error?.let { Note(it, warning = true) }
        }
    }, confirmButton = {
        TextButton(onClick = { model.send(request) }, enabled = !loading && model.question.isNotBlank()) {
            Text(if (loading) "Se pregătește…" else "Trimite")
        }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("Închide") } })
}
