package ro.camara.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ro.camara.app.data.*

@Composable
internal fun RecipePdfImportDialog(config: ConnectionConfig, enabled: Boolean, aliases: Map<String, String>,
    saveError: String?, onDismiss: () -> Unit, onSave: (PersonalRecipe, () -> Unit) -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var document by rememberSaveable { mutableStateOf<String?>(null) }
    var firstPage by rememberSaveable { mutableStateOf("1") }
    var lastPage by rememberSaveable { mutableStateOf("1") }
    var batchId by rememberSaveable { mutableStateOf("") }
    var resultJson by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewed by rememberSaveable { mutableStateOf(arrayListOf<Int>()) }
    var editing by rememberSaveable { mutableIntStateOf(-1) }
    var uploadId by rememberSaveable(config) { mutableStateOf<String?>(null) }
    var pageCount by rememberSaveable(config) { mutableIntStateOf(0) }
    var uploading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val result = remember(resultJson) { resultJson?.let { ActionRules.json.decodeFromString(PdfRecipes.serializer(), it) } }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            document = uri.toString()
            uploadId = null
            pageCount = 0
            firstPage = "1"
            lastPage = "1"
            resultJson = null
            error = null
        }
    }
    val draft = result?.recipes?.getOrNull(editing)
    if (draft != null) {
        val index = editing
        val id = remember(batchId, index) { UUID.nameUUIDFromBytes("$batchId/$index".toByteArray()).toString() }
        PersonalRecipeDialog(null, enabled, aliases, onDismiss = { editing = -1 }, importDraft = draft.draft(),
            importId = id, saveError = saveError) { recipe ->
            onSave(recipe) {
                reviewed = ArrayList(reviewed + index)
                editing = -1
            }
        }
        return
    }

    val first = firstPage.toIntOrNull()
    val last = lastPage.toIntOrNull()
    val lastAvailable = if (pageCount > 0) minOf(pageCount, 10000) else 10000
    val valid = first != null && last != null && first >= 1 && last <= lastAvailable &&
        last >= first && last - first < RECIPE_PDF_MAX_PAGES
    AlertDialog(onDismissRequest = { if (enabled) onDismiss() }, title = { Text("Importă rețete din PDF") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (result == null) {
                OutlinedButton(onClick = { open.launch(arrayOf("application/pdf")) }, enabled = !busy) {
                    Text(if (document == null) "Alege PDF" else "Alege alt PDF")
                }
                if (document != null) {
                    Text(if (pageCount == 1) "PDF selectat · o pagină" else if (pageCount > 0) "PDF selectat · $pageCount pagini" else "PDF selectat",
                        style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(firstPage, { firstPage = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f),
                            label = { Text("Prima pagină") }, singleLine = true, enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(lastPage, { lastPage = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f),
                            label = { Text("Ultima pagină") }, singleLine = true, enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Text("Folosește numerele din vizualizatorul PDF. Alege cel mult $RECIPE_PDF_MAX_PAGES pagini consecutive.")
                    Text("ZimaOS citește paginile alese. AI-ul online primește textul extras de pe acele pagini.",
                        style = MaterialTheme.typography.bodySmall)
                    if (!valid) Note(
                        if (last != null && last > lastAvailable) "PDF-ul are cel mult $lastAvailable pagini disponibile."
                        else "Verifică intervalul de pagini.", warning = true)
                }
            } else {
                Text(if (firstPage == lastPage) "Pagina $firstPage" else "Paginile $firstPage–$lastPage")
                if (result.recipes.isEmpty()) Note("Nu am găsit rețete complete. Încearcă alte pagini.")
                if (result.more_recipes) Note("Mai pot fi rețete pe aceste pagini. Reîncearcă cu un interval mai mic.")
                result.recipes.forEachIndexed { index, recipe ->
                    val saved = index in reviewed
                    OutlinedButton(onClick = { editing = index }, enabled = enabled && !saved, modifier = Modifier.fillMaxWidth()) {
                        val title = recipe.title.ifBlank { "Rețeta ${index + 1}" }
                        Text(if (saved) "$title · salvată" else title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            error?.let { Note(it, warning = true) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        if (result != null) TextButton(onClick = { resultJson = null; error = null }) { Text("Alege alte pagini") }
        else TextButton(onClick = {
            val uri = document?.let(Uri::parse) ?: return@TextButton
            if (first == null || last == null) return@TextButton
            busy = true
            error = null
            scope.launch {
                try {
                    val api = CamaraApi(config)
                    suspend fun upload(): String {
                        uploading = true
                        return try {
                            val file = api.uploadRecipePdf(resolver, uri)
                            pageCount = file.pages
                            uploadId = file.id
                            file.id
                        } finally { uploading = false }
                    }
                    val id = uploadId ?: upload()
                    val batch = try {
                        api.previewRecipePdf(id, first, last)
                    } catch (e: ApiException) {
                        if (e.code != 410) throw e
                        uploadId = null
                        api.previewRecipePdf(upload(), first, last)
                    }
                    batchId = UUID.randomUUID().toString()
                    reviewed = arrayListOf()
                    resultJson = ActionRules.json.encodeToString(PdfRecipes.serializer(), batch)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "PDF-ul nu a putut fi importat." }
                finally { busy = false }
            }
        }, enabled = enabled && !busy && valid && document != null) {
            Text(when {
                uploading -> "Se încarcă PDF-ul…"
                busy -> "Se citesc paginile…"
                else -> "Extrage rețetele"
            })
        }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = enabled) { Text("Închide") } })
}
