package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ro.camara.app.data.RecipeWebImport

@Composable
internal fun RecipeSourceDialog(initialLink: String, photoAvailable: Boolean, photoReading: Boolean, photoError: String?,
    onCamera: () -> Unit, onGallery: () -> Unit, onText: (String) -> Unit, onDismiss: () -> Unit) {
    var link by rememberSaveable(initialLink) { mutableStateOf(initialLink) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = loading || photoReading
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Importă din link sau fotografie") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(link, { link = it.take(2048); error = null }, label = { Text("Link către rețetă") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCamera, enabled = photoAvailable && !busy) { Text("Fotografiază") }
                TextButton(onClick = onGallery, enabled = photoAvailable && !busy) { Text("Alege o imagine") }
            }
            if (!photoAvailable) Note("Pentru a citi o fotografie, conectează aplicația la ZimaOS.")
            if (photoReading) Text("Se citește fotografia pe ZimaOS…")
            (error ?: photoError)?.let { Note(it, warning = true) }
        }
    }, confirmButton = {
        TextButton(enabled = link.isNotBlank() && !busy, onClick = {
            loading = true
            error = null
            scope.launch {
                try {
                    onText(RecipeWebImport.load(link))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: "Linkul nu a putut fi citit."
                } finally {
                    loading = false
                }
            }
        }) { Text(if (loading) "Se citește…" else "Citește linkul") }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Închide") } })
}
