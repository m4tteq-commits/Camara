package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import ro.camara.app.data.ActionRules
import ro.camara.app.data.AiImportedRecipe
import ro.camara.app.data.RecipeConversationReply
import ro.camara.app.data.RecipeConversationRequest
import ro.camara.app.data.RecipeExchange

private val exchangeList = ListSerializer(RecipeExchange.serializer())
private val exchangeSaver = Saver<List<RecipeExchange>, String>(
    save = { ActionRules.json.encodeToString(exchangeList, it) },
    restore = { ActionRules.json.decodeFromString(exchangeList, it) },
)

@Composable
internal fun RecipeConversationDialog(request: suspend (RecipeConversationRequest) -> RecipeConversationReply,
    onVariant: (AiImportedRecipe) -> Unit, canWrite: Boolean, onDismiss: () -> Unit) {
    var history by rememberSaveable(stateSaver = exchangeSaver) { mutableStateOf(emptyList<RecipeExchange>()) }
    var question by rememberSaveable { mutableStateOf("") }
    var variantJson by rememberSaveable { mutableStateOf<String?>(null) }
    val variant = remember(variantJson) {
        variantJson?.let { ActionRules.json.decodeFromString(AiImportedRecipe.serializer(), it) }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    LaunchedEffect(history) { if (history.isNotEmpty()) scroll.scrollTo(scroll.maxValue) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Discută cu AI") }, text = {
        Column(Modifier.heightIn(max = 540.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (history.isEmpty()) {
                Note("AI-ul online primește rețeta, notițele culinare și numele alimentelor disponibile.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { question = "Adaptează rețeta pentru două porții." }, enabled = !loading) {
                        Text("Două porții")
                    }
                    TextButton(onClick = { question = "Ce pot înlocui cu ce am în cămară?" }, enabled = !loading) {
                        Text("Cu ce am")
                    }
                }
            }
            history.forEach { exchange ->
                Text(exchange.question, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(exchange.answer, style = MaterialTheme.typography.bodyMedium)
            }
            variant?.let { draft ->
                OutlinedButton(onClick = { onVariant(draft) }, enabled = canWrite && !loading) {
                    Text("Verifică și salvează varianta")
                }
            }
            OutlinedTextField(question, { question = it.take(500) }, label = { Text("Întrebarea ta") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, enabled = !loading)
            error?.let { Note(it, warning = true) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val sent = question.trim()
            loading = true
            error = null
            scope.launch {
                try {
                    val reply = request(RecipeConversationRequest(sent, history.takeLast(6), variant))
                    history = (history + RecipeExchange(sent, reply.answer)).takeLast(12)
                    reply.variant?.let { variantJson = ActionRules.json.encodeToString(AiImportedRecipe.serializer(), it) }
                    question = ""
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: "Răspunsul AI nu este disponibil momentan."
                } finally {
                    loading = false
                }
            }
        }, enabled = !loading && question.isNotBlank()) { Text(if (loading) "Se pregătește…" else "Trimite") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Închide") } })
}
