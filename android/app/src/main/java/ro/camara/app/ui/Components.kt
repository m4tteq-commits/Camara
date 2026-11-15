package ro.camara.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

internal fun countLabel(value: Int, one: String, many: String) = "$value ${if (value == 1) one else many}"
internal fun portionsLabel(value: Int) = countLabel(value, "porție", "porții")
internal val screenGutter = 16.dp
internal val cardGap = 12.dp
internal val sectionGap = 20.dp
internal fun TextStyle.tabularNumbers() = copy(fontFeatureSettings = "tnum")

@Composable
internal fun cardOutline() = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .09f))

@Composable
internal fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    if (!LocalCamaraMotion.current) return this
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) .98f else 1f, tween(160, easing = LinearOutSlowInEasing), label = "Apăsare")
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

@Composable
internal fun PageHeader(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

@Composable
internal fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
internal fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(modifier.size(44.dp), shape = RoundedCornerShape(14.dp), color = color.copy(alpha = .12f)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(23.dp), tint = color) }
    }
}

@Composable
internal fun SmallPill(text: String, icon: ImageVector? = null, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(shape = RoundedCornerShape(9.dp), color = color.copy(alpha = .10f), contentColor = color) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun Note(text: String, warning: Boolean = false) {
    val color = if (warning) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(if (warning) Icons.Outlined.Info else Icons.Outlined.Lightbulb, null, Modifier.padding(top = 2.dp).size(16.dp), tint = color)
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
internal fun EmptyPanel(icon: ImageVector, title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(20.dp).size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BrowseControls(query: String, onQueryChange: (String) -> Unit, searchLabel: String,
    filter: String, filters: List<Pair<String, String>>, onFilterChange: (String) -> Unit,
    counts: Map<String, Int> = emptyMap(), horizontalFilters: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, { onQueryChange(it.take(100)) }, modifier = Modifier.fillMaxWidth(),
            label = { Text(searchLabel) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (query.isEmpty()) null else {
                { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Outlined.Close, "Golește căutarea") } }
            }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), shape = MaterialTheme.shapes.medium)
        if (horizontalFilters) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEach { (key, label) -> BrowseFilterChip(key == filter, label, counts[key]) { onFilterChange(key) } }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                filters.forEach { (key, label) ->
                    FilterChip(selected = key == filter, onClick = { onFilterChange(key) }, label = { Text(label) })
                }
            }
        }
    }
}

@Composable
private fun BrowseFilterChip(selected: Boolean, label: String, count: Int?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val target = if (selected) colors.secondaryContainer else Color.Transparent
    val motion = LocalCamaraMotion.current
    val fill = if (motion) {
        val animated by animateColorAsState(target, tween(160, easing = LinearOutSlowInEasing), label = "Filtru")
        animated
    } else target
    val interaction = remember { MutableInteractionSource() }
    FilterChip(selected, onClick, modifier = Modifier.pressScale(interaction), interactionSource = interaction,
        colors = FilterChipDefaults.filterChipColors(containerColor = fill, selectedContainerColor = fill),
        label = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, maxLines = 1)
                if (count != null) {
                    Surface(shape = CircleShape, color = if (selected) colors.surfaceContainerLowest else colors.surfaceContainerHigh) {
                        Text("$count", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall.tabularNumbers())
                    }
                }
            }
        })
}

@Composable
internal fun PrimaryBrowseAction(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean,
    modifier: Modifier = Modifier, loading: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    Button(onClick = onClick, enabled = enabled, interactionSource = interaction,
        modifier = modifier.fillMaxWidth().heightIn(min = 50.dp).pressScale(interaction),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(icon, null, Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
internal fun SecondaryBrowseAction(label: String, onClick: () -> Unit, modifier: Modifier,
    enabled: Boolean = true, icon: ImageVector? = null, minHeight: androidx.compose.ui.unit.Dp = 44.dp) {
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(onClick = onClick, enabled = enabled, interactionSource = interaction,
        modifier = modifier.heightIn(min = minHeight).pressScale(interaction),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun ListDetailLayout(modifier: Modifier, wide: Boolean, list: @Composable (Modifier) -> Unit,
    detail: @Composable (Modifier) -> Unit) {
    Row(modifier.fillMaxSize()) {
        list(Modifier.weight(if (wide) .46f else 1f).fillMaxHeight())
        if (wide) {
            VerticalDivider(Modifier.fillMaxHeight())
            detail(Modifier.weight(.54f).fillMaxHeight())
        }
    }
}

@Composable
internal fun DetailPlaceholder(modifier: Modifier, icon: ImageVector, title: String, message: String) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { EmptyPanel(icon, title, message) }
}

@Composable
internal fun ExpandablePanel(title: String, subtitle: String, expanded: Boolean, onToggle: () -> Unit,
    modifier: Modifier = Modifier, icon: ImageVector? = null, compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val motion = LocalCamaraMotion.current
    val rotation = if (expanded) 180f else 0f
    val arrow = if (motion) {
        val animated by animateFloatAsState(rotation, tween(180), label = "Extindere")
        animated
    } else rotation
    val container = modifier.fillMaxWidth()
    Surface(if (motion) container.animateContentSize(tween(220)) else container,
        shape = if (compact) MaterialTheme.shapes.medium else MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow, border = if (compact) cardOutline() else null) {
        Column {
            val header = Modifier.fillMaxWidth().clickable(onClick = onToggle)
            Row(if (compact) header.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 4.dp) else header.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (icon != null) IconTile(icon, Modifier.size(if (compact) 32.dp else 38.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 3.dp)) {
                    Text(title, style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ExpandMore, if (expanded) "Restrânge $title" else "Extinde $title",
                    Modifier.rotate(arrow), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}
