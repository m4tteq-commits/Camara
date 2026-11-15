package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ro.camara.app.data.ShoppingAisle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AisleField(name: String, aisle: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val resolved = ShoppingAisle.forProduct(name, aisle)
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(if (aisle.isEmpty()) "Automat · ${resolved.label}" else resolved.label, {},
            Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable), readOnly = true,
            label = { Text("Raion") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Automat") }, onClick = { onChange(""); expanded = false })
            ShoppingAisle.entries.forEach { entry ->
                DropdownMenuItem(text = { Text(entry.label) }, onClick = { onChange(entry.id); expanded = false })
            }
        }
    }
}

@Composable
internal fun AisleOrderDialog(aisles: List<ShoppingAisle>, onDismiss: () -> Unit, onSave: (List<ShoppingAisle>) -> Unit) {
    var order by remember(aisles) { mutableStateOf(aisles) }
    fun move(index: Int, offset: Int) {
        order = order.toMutableList().apply { add(index + offset, removeAt(index)) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Ordinea raioanelor") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            order.forEachIndexed { index, aisle ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(aisle.label, Modifier.weight(1f))
                    IconButton(onClick = { move(index, -1) }, enabled = index > 0) {
                        Icon(Icons.Outlined.KeyboardArrowUp, "Mută mai sus · ${aisle.label}")
                    }
                    IconButton(onClick = { move(index, 1) }, enabled = index < order.lastIndex) {
                        Icon(Icons.Outlined.KeyboardArrowDown, "Mută mai jos · ${aisle.label}")
                    }
                }
            }
            TextButton(onClick = { order = ShoppingAisle.entries }) { Text("Ordinea implicită") }
        }
    }, confirmButton = { TextButton(onClick = { onSave(order) }) { Text("Salvează") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } })
}
