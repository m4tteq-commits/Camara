package ro.camara.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceSheet(appearance: CamaraAppearance, onChange: (CamaraAppearance) -> Unit, onClose: () -> Unit) {
    val dark = appearance.dark(isSystemInDarkTheme())
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(148.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PageHeader("Teme și aspect", "Alege cum arată Cămara pe acest dispozitiv.") {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Închide aspectul") }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Luminozitate", style = MaterialTheme.typography.titleSmall)
                    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CamaraMode.entries.forEach { mode ->
                            FilterChip(selected = appearance.mode == mode, onClick = { onChange(appearance.copy(mode = mode)) },
                                label = { Text(mode.label) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    Text("Paletă de culori", style = MaterialTheme.typography.titleSmall)
                }
            }
            items(CamaraPalette.entries, key = { it.name }) { palette ->
                PaletteCard(palette, dark, appearance.palette == palette) { onChange(appearance.copy(palette = palette)) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(appearance.motion, role = Role.Switch) { onChange(appearance.copy(motion = it)) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconTile(Icons.Outlined.MotionPhotosOn, Modifier.size(36.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Animații", style = MaterialTheme.typography.titleSmall)
                            Text("Treceri între ecrane, panouri și pașii rețetei", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = appearance.motion, onCheckedChange = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteCard(palette: CamaraPalette, dark: Boolean, selected: Boolean, onClick: () -> Unit) {
    val preview = paletteScheme(palette, dark)
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = Modifier.fillMaxWidth().clip(shape).selectable(selected, role = Role.RadioButton, onClick = onClick),
        shape = shape,
        color = preview.background,
        contentColor = preview.onBackground,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) preview.primary else preview.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(palette.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (selected) Icon(Icons.Outlined.CheckCircle, null, Modifier.size(17.dp), tint = preview.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1.2f).height(22.dp).background(preview.primaryContainer, MaterialTheme.shapes.small))
                Box(Modifier.size(22.dp).background(preview.primary, CircleShape))
                Box(Modifier.size(22.dp).background(preview.secondary, CircleShape))
                Box(Modifier.size(22.dp).background(preview.tertiaryContainer, CircleShape))
            }
        }
    }
}
