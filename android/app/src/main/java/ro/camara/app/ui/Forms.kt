package ro.camara.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ro.camara.app.UiState
import ro.camara.app.data.ApiException
import ro.camara.app.data.CamaraApi
import ro.camara.app.data.ConnectionConfig
import ro.camara.app.data.ConnectionReport
import ro.camara.app.data.PantryInput
import ro.camara.app.data.PantryItem

@Composable
fun SetupScreen(state: UiState, modifier: Modifier, onConnect: (ConnectionConfig) -> Unit) {
    Box(modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 500.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Outlined.Kitchen, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Bun venit în\nCămara ta.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Text("Alimentele, ideile de mese și lista de cumpărături, împreună. Conectează-te la serverul tău pentru a începe.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ConnectionForm(null, state.busy, null, onConnect)
        }
    }
}

@Composable
fun ConnectionForm(config: ConnectionConfig?, busy: Boolean, lastSync: Long?, onConnect: (ConnectionConfig) -> Unit,
    allowSave: Boolean = true) {
    var url by rememberSaveable(config?.baseUrl) { mutableStateOf(config?.baseUrl ?: "") }
    var editedLan by rememberSaveable(config?.baseUrl, config?.lanAddress) {
        mutableStateOf(config?.lanAddress ?: if (config != null) "" else null)
    }
    // The key is deliberately not stored in the saved-instance-state bundle.
    var token by remember(config) { mutableStateOf(config?.token ?: "") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ConnectionReport?>(null) }
    val scope = rememberCoroutineScope()
    val lan = editedLan ?: ConnectionConfig.defaultLanAddress(url).orEmpty()
    val enabled = !busy && !testing
    val canConnect = enabled && url.isNotBlank() && token.isNotBlank()
    fun clearResult() { report = null; error = null }
    fun candidate() = runCatching { ConnectionConfig(url, token, lanAddress = lan.ifBlank { null }).validated() }
        .onFailure { error = it.message }.getOrNull()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(url, { url = it; clearResult() }, label = { Text("Adresa serverului") }, placeholder = { Text("https://camara-m4tteq.duckdns.org") },
            singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(lan, { editedLan = it; clearResult() }, label = { Text("IP LAN · opțional") },
            supportingText = { Text("Conectare locală cu același certificat HTTPS.") }, singleLine = true,
            enabled = enabled, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(token, { token = it; clearResult() }, label = { Text("Cheia de acces") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "Ascunde cheia" else "Arată cheia") } })
        if (config != null) {
            val synced = lastSync?.let { connectionTime(Instant.ofEpochMilli(it)) } ?: "În așteptare"
            Text("Ultima sincronizare · $synced", style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        report?.let {
            val route = when { it.viaLan -> "LAN prin HTTPS"; it.https -> "Adresa HTTPS configurată"; else -> "Adresă locală HTTP" }
            Text("$route · API ${it.status.version}")
            if (it.status.database == "ready") Text("SQLite disponibilă · schema ${it.status.schema_version}", style = MaterialTheme.typography.bodySmall)
            it.status.last_backup_at?.let { raw ->
                val time = runCatching { connectionTime(Instant.parse(raw)) }.getOrDefault(raw)
                Text("Ultimul backup · $time", style = MaterialTheme.typography.bodySmall)
            }
            it.status.backup_error?.let { message -> Text(message, color = MaterialTheme.colorScheme.error) }
        }
        OutlinedButton(onClick = {
            clearResult()
            candidate()?.let { connection ->
                testing = true
                scope.launch {
                    try { report = CamaraApi(connection, fast = true).testConnection() }
                    catch (e: CancellationException) { throw e }
                    catch (_: SSLException) { error = "Certificatul HTTPS nu a putut fi verificat pentru adresa serverului." }
                    catch (_: IOException) { error = "Serverul nu este accesibil. Verifică rețeaua și adresele." }
                    catch (e: ApiException) { error = e.message }
                    catch (_: IllegalArgumentException) { error = "Răspunsul nu corespunde unui server Cămara." }
                    finally { testing = false }
                }
            }
        }, enabled = canConnect, modifier = Modifier.fillMaxWidth()) { Text(if (testing) "Se testează..." else "Testează conexiunea") }
        Button(onClick = {
            clearResult()
            candidate()?.let(onConnect)
        }, enabled = canConnect && allowSave, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            if (busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
            Text(if (busy) "Se verifică..." else if (config == null) "Conectează" else "Salvează conexiunea")
        }
    }
}

private fun connectionTime(time: Instant): String =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(time)

@Composable
fun PantryDialog(item: PantryItem?, enabled: Boolean, onDismiss: () -> Unit, onSave: (PantryInput) -> Unit, initialName: String = "") {
    var name by rememberSaveable(item?.id, initialName) { mutableStateOf(item?.name ?: initialName) }
    var quantity by rememberSaveable(item?.id) { mutableStateOf(item?.quantity?.toString() ?: "1") }
    var unit by rememberSaveable(item?.id) { mutableStateOf(item?.unit ?: "buc") }
    var location by rememberSaveable(item?.id) { mutableStateOf(item?.location ?: "frigider") }
    var expires by rememberSaveable(item?.id) { mutableStateOf(item?.expires_on ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (item == null) "Un aliment în plus" else "Editează alimentul") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text("Denumire") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(quantity, { quantity = it.take(12) }, label = { Text("Cantitate") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("buc", "g", "kg", "ml", "l").forEach { value -> FilterChip(unit == value, { unit = value }, label = { Text(value) }) }
                }
                Text("Unde îl păstrezi", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("frigider" to "Frigider", "congelator" to "Congelator", "camara" to "Cămară").forEach { (value, label) ->
                        FilterChip(location == value, { location = value }, label = { Text(label) })
                    }
                }
                OutlinedTextField(expires, { expires = it.take(10) }, label = { Text("Termen · opțional") }, placeholder = { Text("AAAA-LL-ZZ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            val amount = quantity.replace(',', '.').toDoubleOrNull()
            val validDate = expires.isBlank() || runCatching { LocalDate.parse(expires) }.isSuccess
            when {
                name.isBlank() -> error = "Introdu denumirea alimentului."
                amount == null || !amount.isFinite() || amount <= 0 || amount > 100000 -> error = "Introdu o cantitate între 0 și 100000."
                !validDate -> error = "Data trebuie să fie în formatul AAAA-LL-ZZ."
                else -> onSave(PantryInput(name.trim(), amount, unit, location, expires.ifBlank { null }))
            }
        }, enabled = enabled) { Text("Salvează") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } })
}

@Composable
fun ShoppingDialog(enabled: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Adaugă pe listă") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Produs") }, singleLine = true)
            OutlinedTextField(quantity, { quantity = it.take(80) }, label = { Text("Cantitate · opțional") }, placeholder = { Text("De exemplu: 500 g") }, singleLine = true)
        }
    }, confirmButton = { TextButton(onClick = { onSave(name.trim(), quantity.trim()) }, enabled = enabled && name.isNotBlank()) { Text("Adaugă") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } })
}
