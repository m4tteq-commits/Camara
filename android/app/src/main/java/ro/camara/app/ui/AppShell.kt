package ro.camara.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ro.camara.app.UiState
import ro.camara.app.data.staleEpoch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Immutable
internal data class ConnectionDisplay(val online: Boolean, val syncing: Boolean, val blocked: Boolean, val pending: Int, val lastSync: Long?)

internal fun UiState.connectionDisplay() = ConnectionDisplay(online, syncing,
    pending.any { it.issue != null || staleEpoch(it, serverSnapshot) }, pending.size, lastSync)

private val syncTime = DateTimeFormatter.ofPattern("dd.MM HH:mm")

internal fun syncLabel(status: ConnectionDisplay, compact: Boolean = false): String {
    val time = if (compact) null else status.lastSync?.let { syncTime.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) }
    return when {
        status.blocked -> if (compact) "De verificat" else "Necesită verificare"
        status.syncing -> "Se sincronizează"
        status.pending > 0 -> if (compact) "În așteptare" else "Salvat pe dispozitiv"
        status.online -> if (time == null) "La zi" else "La zi · $time"
        status.lastSync != null -> if (time == null) "Offline" else "Offline · date din $time"
        compact -> "Indisponibil"
        else -> "Server indisponibil"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppShell(selected: Destination, recipeId: String?, splitRecipe: Boolean, cooking: Boolean,
    busy: Boolean, canWrite: Boolean, connection: State<ConnectionDisplay>, snackbar: SnackbarHostState,
    navigate: (Destination) -> Unit, goBack: () -> Unit, onSync: () -> Unit, onRefresh: () -> Unit,
    onReminders: () -> Unit, onAppearance: () -> Unit, onSettings: () -> Unit, onAdd: () -> Unit,
    content: @Composable (AppPage, Boolean, Boolean) -> Unit) {
    val holder = rememberSaveableStateHolder()
    var menu by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val short = maxHeight < 520.dp
        val recipeInPane = wide && splitRecipe && !cooking
        val page = AppPage(selected, recipeId.takeUnless { recipeInPane })
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selected in primaryDestinations && recipeId == null) {
                        Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(Icons.Outlined.Eco, null, Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    val title = when {
                        cooking -> "Mod de gătit"
                        recipeId != null && !recipeInPane -> "Rețetă"
                        selected in primaryDestinations -> "Cămara"
                        else -> selected.label
                    }
                    Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ConnectionPill(connection.value, onSync)
                }
            }, navigationIcon = {
                if (selected !in primaryDestinations || recipeId != null) IconButton(onClick = goBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Înapoi")
                }
            }, actions = {
                IconButton(onClick = onReminders) { Icon(Icons.Outlined.NotificationsNone, "Notificări pentru termene") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Opțiunile aplicației") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Actualizează datele") }, { menu = false; onRefresh() },
                            leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, enabled = canWrite)
                        DropdownMenuItem({ Text("Teme și aspect") }, { menu = false; onAppearance() },
                            leadingIcon = { Icon(Icons.Outlined.Palette, null) })
                        DropdownMenuItem({ Text("Serverul meu") }, { menu = false; onSettings() },
                            leadingIcon = { Icon(Icons.Outlined.Dns, null) }, enabled = canWrite && !connection.value.syncing)
                    }
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        }, bottomBar = {
            if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp) {
                primaryDestinations.forEach { item ->
                    NavigationBarItem(selected.navGroup == item, { navigate(item) }, { Icon(item.icon, null, Modifier.size(24.dp)) },
                        label = { Text(item.navLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1) })
                }
            }
        }, floatingActionButton = {
            if (canWrite && (selected == Destination.Pantry || selected == Destination.Shopping)) {
                ExtendedFloatingActionButton(text = { Text(if (selected == Destination.Pantry) "Adaugă aliment" else "Adaugă produs") },
                    icon = { Icon(Icons.Outlined.Add, null) }, onClick = onAdd)
            }
        }) { padding ->
            Row(Modifier.padding(padding).fillMaxSize()) {
                if (wide) NavigationRail(Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    windowInsets = WindowInsets(0, 0, 0, 0)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(if (short) 4.dp else 16.dp)) {
                        if (!short) Spacer(Modifier.height(16.dp))
                        primaryDestinations.forEach { item ->
                            NavigationRailItem(selected.navGroup == item, { navigate(item) }, { Icon(item.icon, null) }, label = { Text(item.navLabel) })
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val motion = LocalCamaraMotion.current
                    val alpha = remember(page) { Animatable(if (motion) 0.68f else 1f) }
                    LaunchedEffect(page, motion) { if (motion) alpha.animateTo(1f, tween(160)) else alpha.snapTo(1f) }
                    Box(Modifier.weight(1f).fillMaxWidth().graphicsLayer { this.alpha = alpha.value }) {
                        holder.SaveableStateProvider(if (page.recipeId != null) "recipe-details" else page.saveKey) {
                            key(page) { content(page, wide, recipeInPane) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionPill(status: ConnectionDisplay, onSync: () -> Unit) {
    val theme = MaterialTheme.colorScheme
    val (dot, container, text) = when {
        status.blocked -> Triple(theme.error, theme.errorContainer, theme.onErrorContainer)
        status.pending > 0 || !status.online -> Triple(theme.secondary, theme.secondaryContainer, theme.onSecondaryContainer)
        else -> Triple(theme.primary, theme.primaryContainer, theme.onPrimaryContainer)
    }
    val description = buildList {
        add("Detalii sincronizare")
        add(syncLabel(status))
        if (status.pending > 0) add(countLabel(status.pending, "acțiune în așteptare", "acțiuni în așteptare"))
    }.joinToString(" · ")
    Box(Modifier.widthIn(max = 118.dp).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onSync)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Surface(color = container, contentColor = text, shape = CircleShape) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (status.syncing && !status.blocked) CircularProgressIndicator(Modifier.size(11.dp), color = text, strokeWidth = 1.5.dp)
                else Box(Modifier.size(6.dp).background(dot, CircleShape))
                Text(syncLabel(status, compact = true), Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (status.pending > 0) Text(status.pending.toString(), style = MaterialTheme.typography.labelSmall.tabularNumbers())
            }
        }
    }
}
