package ro.camara.app.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ro.camara.app.notifications.ReminderSettings

@Composable
fun ReminderDialog(settings: ReminderSettings, permissionGranted: Boolean, onDismiss: () -> Unit,
    onSave: (ReminderSettings) -> Unit, onPermission: () -> Unit, onTest: () -> Unit) {
    var enabled by remember { mutableStateOf(settings.enabled) }
    var hour by remember { mutableIntStateOf(settings.hour) }
    var minute by remember { mutableIntStateOf(settings.minute) }
    var days by remember { mutableIntStateOf(settings.days) }
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Termenele alimentelor") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row { Text("Rezumat zilnic", Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
            OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> hour = h; minute = m }, hour, minute, true).show() }, enabled = enabled) { Text("Ora ${"%02d:%02d".format(hour, minute)}") }
            Text("Include alimentele cu termen în următoarele:")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1, 2, 3, 7).forEach { value -> FilterChip(days == value, { days = value }, label = { Text("$value zile") }, enabled = enabled) } }
            Text("Primești un singur rezumat pe zi. Android îl poate amâna când economisește bateria. Fără server, folosim ultima copie salvată.", style = MaterialTheme.typography.bodySmall)
            if (!permissionGranted) {
                Text("Notificările nu sunt încă permise de Android.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onPermission) { Text("Permite notificările") }
            }
            TextButton(onClick = onTest) { Text("Trimite o notificare de test") }
        }
    }, confirmButton = { TextButton(onClick = { onSave(ReminderSettings(enabled, hour, minute, days)); onDismiss() }) { Text("Salvează") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Închide") } })
}
