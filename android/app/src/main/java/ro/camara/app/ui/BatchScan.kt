package ro.camara.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.serialization.builtins.ListSerializer
import ro.camara.app.data.*

private val scanRowsSerializer = ListSerializer(ScannedProduct.serializer())
private val scanRowsSaver = Saver<List<ScannedProduct>, String>(
    save = { ActionRules.json.encodeToString(scanRowsSerializer, it) },
    restore = { ActionRules.json.decodeFromString(scanRowsSerializer, it) },
)

@Composable
internal fun BatchScanDialog(
    snapshot: Snapshot,
    enabled: Boolean,
    serverMessage: String?,
    onDismiss: () -> Unit,
    onSave: (String, List<ScannedProduct>) -> Unit,
    sampleCodes: List<String>? = null,
) {
    val epoch = rememberSaveable { snapshot.epoch }
    var rows by rememberSaveable(stateSaver = scanRowsSaver) { mutableStateOf(emptyList<ScannedProduct>()) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val lotCount = rows.sumOf { it.lots.size }
    val canScanMore = lotCount < MAX_SCAN_LOTS
    val close = { if (rows.isEmpty()) onDismiss() else discard = true }
    fun scan(code: String) {
        rows = addScannedProduct(rows, code, snapshot.products)
    }
    Dialog(onDismissRequest = { if (enabled) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 620.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.heightIn(max = 700.dp).fillMaxHeight().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (reviewing) "Verifică loturile" else "Scanare în serie", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = close, enabled = enabled) { Icon(Icons.Outlined.Close, "Închide scanarea") }
                    }
                    if (reviewing) {
                        Text("Completează produsele noi și verifică termenele. La salvare, produsele noi intră și în catalog.", style = MaterialTheme.typography.bodySmall)
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(rows, key = { it.barcode }) { row ->
                                ScanProductCard(row, enabled, canScanMore,
                                    onChange = { changed -> rows = rows.map { if (it.barcode == row.barcode) changed else it } },
                                    onRemove = { rows = rows.filterNot { it.barcode == row.barcode } })
                            }
                        }
                        serverMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { reviewing = false }, enabled = enabled && canScanMore, modifier = Modifier.weight(1f)) {
                                Text("Continuă scanarea")
                            }
                            Button(onClick = { onSave(epoch, rows) }, enabled = enabled && rows.isNotEmpty(), modifier = Modifier.weight(1f)) {
                                Text("Salvează loturile")
                            }
                        }
                    } else {
                        Text("Treci pe rând codurile prin cadru. Pentru același cod, ajustezi numărul de ambalaje la verificare.",
                            style = MaterialTheme.typography.bodySmall)
                        if (sampleCodes == null) {
                            ContinuousBarcodeView(Modifier.weight(1f).fillMaxWidth(), ::scan)
                        } else {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                sampleCodes.forEach { code -> OutlinedButton(onClick = { scan(code) }) { Text("Cod de probă $code") } }
                            }
                        }
                        val last = rows.lastOrNull()
                        Text(when {
                            !canScanMore -> "Ai atins limita de $MAX_SCAN_LOTS loturi."
                            last == null -> "Încadrează primul cod de bare."
                            else -> "Ultimul cod · ${last.name.ifBlank { last.barcode }}"
                        }, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { reviewing = true }, enabled = rows.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text("Verifică · ${countLabel(lotCount, "lot", "loturi")}")
                        }
                    }
                }
            }
        }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text("Renunți la scanare?") },
        text = { Text("Loturile din această sesiune vor fi eliminate.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Renunță") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Continuă") } },
    )
}

@Composable
private fun ContinuousBarcodeView(modifier: Modifier, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val currentOnCode by rememberUpdatedState(onCode)
    val view = remember(context) {
        DecoratedBarcodeView(context).apply {
            barcodeView.decoderFactory = DefaultDecoderFactory(ScanOptions.PRODUCT_CODE_TYPES.map(com.google.zxing.BarcodeFormat::valueOf))
            setStatusText("")
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
    LifecycleResumeEffect(view) {
        view.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) { currentOnCode(result.text) }
            override fun possibleResultPoints(resultPoints: List<ResultPoint>) = Unit
        })
        view.resume()
        onPauseOrDispose { view.pause() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanProductCard(row: ScannedProduct, enabled: Boolean, canAddLot: Boolean,
    onChange: (ScannedProduct) -> Unit, onRemove: () -> Unit) {
    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.known?.name ?: "Produs nou", style = MaterialTheme.typography.titleMedium)
                    Text(row.known?.let { "${row.packageAmount} ${it.unit} / ambalaj" } ?: row.barcode, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onRemove, enabled = enabled) { Icon(Icons.Outlined.Close, "Elimină produsul scanat") }
            }
            if (row.known == null) {
                OutlinedTextField(row.name, { onChange(row.copy(name = it.take(100))) }, label = { Text("Denumire produs") },
                    singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(row.packageAmount, { onChange(row.copy(packageAmount = it.take(12))) }, label = { Text("Cantitate / ambalaj") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, enabled = enabled, modifier = Modifier.weight(1f))
                    ValueChoice(row.unit, units, enabled) { onChange(row.copy(unit = it)) }
                }
            }
            row.lots.forEachIndexed { index, lot ->
                key(lot.id) {
                    fun change(next: ScannedLot) { onChange(row.copy(lots = row.lots.map { if (it.id == lot.id) next else it })) }
                    HorizontalDivider()
                    if (row.lots.size > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Lotul ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { onChange(row.copy(lots = row.lots.filterNot { it.id == lot.id })) }, enabled = enabled) {
                                Icon(Icons.Outlined.Close, "Elimină lotul ${index + 1}")
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(lot.count, { change(lot.copy(count = it.filter(Char::isDigit).take(6))) }, label = { Text("Ambalaje") },
                            singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(112.dp))
                        ValueChoice(lot.location, locations, enabled) { change(lot.copy(location = it)) }
                    }
                    OutlinedTextField(lot.expiry, { change(lot.copy(expiry = it.take(10))) }, label = { Text("Termen opțional") },
                        placeholder = { Text("AAAA-LL-ZZ") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                }
            }
            TextButton(onClick = { onChange(row.copy(lots = row.lots + ScannedLot(location = row.lots.last().location))) }, enabled = enabled && canAddLot) {
                Text("Adaugă lot separat")
            }
        }
    }
}
