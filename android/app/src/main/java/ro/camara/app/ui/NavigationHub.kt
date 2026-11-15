package ro.camara.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun MoreScreen(
    onNavigate: (Int) -> Unit,
    modifier: Modifier,
    appearance: CamaraAppearance = CamaraAppearance(),
    onAppearance: () -> Unit = {},
    onReminders: () -> Unit = {},
    onServer: () -> Unit = {},
    serverEnabled: Boolean = true,
    storeEnabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    LazyVerticalGrid(
        columns = GridCells.Adaptive(336.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(screenGutter, 12.dp, screenGutter, 28.dp),
        horizontalArrangement = Arrangement.spacedBy(cardGap),
        verticalArrangement = Arrangement.spacedBy(sectionGap),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PageHeader("Mai mult", "Alege ce vrei să faci în Cămara ta.")
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(cardGap)) {
                HubShortcut("Planul meselor", "Vezi săptămâna", Icons.Outlined.CalendarMonth,
                    colors.primaryContainer, colors.onPrimaryContainer, colors.primary, Modifier.weight(1f), onClick = { onNavigate(3) })
                HubShortcut("În magazin", "Deschide coșul", Icons.Outlined.ShoppingCart,
                    colors.secondaryContainer, colors.onSecondaryContainer, colors.secondary, Modifier.weight(1f), storeEnabled) { onNavigate(10) }
            }
        }
        item {
            HubGroup("Mese", listOf(
                HubEntry("Plan automat", "Mese și cumpărături în limita bugetului", Icons.Outlined.AutoAwesome) { onNavigate(12) },
                HubEntry("Porții gătite", "Mâncare pregătită pentru alte zile", Icons.Outlined.TakeoutDining) { onNavigate(11) },
            ))
        }
        item {
            HubGroup("Cumpărături și buget", listOf(
                HubEntry("Catalog", "Produse și coduri de bare", Icons.Outlined.QrCodeScanner) { onNavigate(7) },
                HubEntry("Bonuri", "Adaugă o fotografie sau vezi istoricul", Icons.Outlined.ReceiptLong) { onNavigate(8) },
                HubEntry("Buget și risipă", "Cheltuieli, consum și istoric", Icons.Outlined.AccountBalanceWallet) { onNavigate(4) },
            ))
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            HubGroup("Setări și date", listOf(
                HubEntry("Teme și aspect", "${appearance.palette.label} · ${appearance.mode.label}", Icons.Outlined.Palette, onClick = onAppearance),
                HubEntry("Notificări", "Rezumatul alimentelor și termenele", Icons.Outlined.NotificationsNone, onClick = onReminders),
                HubEntry("Serverul meu", "Adresă și conexiune", Icons.Outlined.Dns, serverEnabled, onServer),
                HubEntry("Backup și restaurare", "Copii ale datelor de pe server", Icons.Outlined.Backup) { onNavigate(9) },
            ))
        }
    }
}

private data class HubEntry(val title: String, val description: String, val icon: ImageVector, val enabled: Boolean = true, val onClick: () -> Unit)

@Composable
private fun HubGroup(title: String, entries: List<HubEntry>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 3.dp), style = MaterialTheme.typography.titleMedium)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest, border = cardOutline()) {
            Column {
                entries.forEachIndexed { index, entry ->
                    Surface(onClick = entry.onClick, enabled = entry.enabled, color = Color.Transparent) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(entry.icon, Modifier.size(40.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(entry.title, style = MaterialTheme.typography.titleSmall)
                                Text(entry.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (index < entries.lastIndex) HorizontalDivider(Modifier.padding(start = 66.dp, end = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun HubShortcut(title: String, description: String, icon: ImageVector, background: Color, text: Color, accent: Color,
    modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Card(onClick = onClick, modifier = modifier.fillMaxHeight().pressScale(interaction), enabled = enabled,
        shape = MaterialTheme.shapes.medium, border = cardOutline(), interactionSource = interaction,
        colors = CardDefaults.cardColors(containerColor = background, contentColor = text)) {
        val fill = if (enabled) {
            Modifier.background(Brush.linearGradient(listOf(background, lerp(background, accent, .16f))))
        } else Modifier
        Box(Modifier.fillMaxSize().then(fill)) {
            Icon(icon, null, Modifier.align(Alignment.BottomEnd).padding(8.dp).size(72.dp), tint = LocalContentColor.current.copy(alpha = .10f))
            Column(Modifier.fillMaxWidth().heightIn(min = 115.dp).padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, Modifier.size(25.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
