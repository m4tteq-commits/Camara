package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ro.camara.app.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryScreen(snapshot: Snapshot, config: ConnectionConfig?, online: Boolean, modifier: Modifier) {
    var kind by rememberSaveable { mutableStateOf(HistoryKind.Receipts) }
    var entries by remember(kind, config, online, snapshot.epoch) { mutableStateOf<HistoryRows?>(null) }
    var loading by remember(kind, config, online) { mutableStateOf(false) }
    var error by remember(kind, config, online) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val fallback = !online || error != null && entries == null
    val offline = if (fallback) remember(kind, snapshot) { snapshot.history(kind) } else emptyList()
    val api = remember(config) { config?.let { CamaraApi(it, fast = true) } }
    // Cancel requests associated with an old filter or connection.
    var next by remember(kind, config, online, snapshot.epoch, reload) { mutableStateOf<String?>(null) }
    LaunchedEffect(api, kind, online, snapshot.epoch, reload, next) {
        if (api == null || !online) return@LaunchedEffect
        loading = true
        error = null
        try {
            val page = api.history(kind, next)
            entries = if (next == null) page else page.copy(rows = (entries?.rows.orEmpty() + page.rows).distinctBy(HistoryRow::id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Istoricul online nu este disponibil acum."
        } finally {
            loading = false
        }
    }
    val visible = entries?.rows ?: offline
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PageHeader("Istoric", if (!online || error != null && entries == null) "Date salvate pe dispozitiv" else "")
            ScrollableTabRow(selectedTabIndex = kind.ordinal, edgePadding = 0.dp) {
                HistoryKind.entries.forEach { option ->
                    Tab(option == kind, onClick = { kind = option }, text = { Text(option.label) })
                }
            }
        }
        items(visible, key = HistoryRow::id) { row ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(row.date, row.cents?.let { money(it.toLong()) }, row.detail.takeIf { it.isNotBlank() }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (visible.isEmpty() && !loading && (fallback || entries != null)) item { Text("Nu există înregistrări în acest istoric.") }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message ->
            item {
                Note(message, warning = true)
                TextButton(onClick = { reload++ }) { Text("Reîncearcă") }
            }
        }
        if (!loading && error == null) entries?.nextCursor?.let { cursor ->
            item { OutlinedButton(onClick = { next = cursor }) { Text("Încarcă mai multe") } }
        }
    }
}
