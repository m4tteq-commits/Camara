package ro.camara.app.debug

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.lifecycle.lifecycleScope
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray
import ro.camara.app.data.*
import ro.camara.app.ai.LocalAi
import ro.camara.app.ui.CamaraTheme

/** Test-only entry point restricted to the system DUMP permission. */
class V04ProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CamaraTheme { Surface { Text("Cămara · verificare 0.4") } } }
        lifecycleScope.launch(Dispatchers.IO) {
            val result = JSONObject()
            val metadataFile = File(filesDir, "v04-probe.json")
            try {
                val config = checkNotNull(ConnectionStore(this@V04ProbeActivity).read())
                val store = OfflineStore(this@V04ProbeActivity)
                val local = SyncEngine.sync(this@V04ProbeActivity, config)
                check(local.pending.isEmpty()) { "Resolve pending actions before probing." }
                val api = CamaraApi(config, epoch = local.server.epoch)
                when (intent.getStringExtra("task")) {
                    "seed" -> {
                        check(!metadataFile.exists() || JSONObject(metadataFile.readText()).optBoolean("cleaned")) { "Earlier v04 probe needs cleanup." }
                        val product = Product(UUID.randomUUID().toString(), "Test v04 Orez", "5901234123457", 500.0, "g", "camara", listOf("OREZ TEST"))
                        val own = PersonalRecipe(UUID.randomUUID().toString(), "Test v04 Orez simplu", 12, 1,
                            listOf(Ingredient(product.name, "100 g"), Ingredient("Apă", "250 ml")),
                            listOf("Clătește orezul.", "Fierbe orezul conform ambalajului."), listOf("Plită", "Oală"), "vegan", "main", Instant.now().toString())
                        val input = product.stockInput(2, LocalDate.now().plusDays(2).toString())
                        val stock = PantryItem(UUID.randomUUID().toString(), input.name, input.quantity, input.unit, input.location, input.expires_on)
                        val owned = JSONArray().put(JSONObject().put("kind", "products").put("id", product.id))
                            .put(JSONObject().put("kind", "personal_recipes").put("id", own.id)).put(JSONObject().put("kind", "pantry").put("id", stock.id))
                        val metadata = JSONObject().put("records", owned).put("product_id", product.id).put("personal_id", own.id).put("stock_id", stock.id)
                        metadataFile.writeText(metadata.toString(2))
                        store.enqueue(config) { ActionRules.action("Test v04 catalog și rețetă", listOf(Change("products", product.id, after = ActionRules.record(product)),
                            Change("personal_recipes", own.id, after = ActionRules.record(own)), Change("pantry", stock.id, after = ActionRules.record(stock)))) }
                        check(SyncEngine.sync(this@V04ProbeActivity, config).pending.isEmpty())
                        val matrix = MultiFormatWriter().encode(product.barcode, BarcodeFormat.EAN_13, 900, 300)
                        val pixels = IntArray(matrix.width * matrix.height) { index -> if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE }
                        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels))))
                        check(decoded.text == product.barcode)
                        result.put("barcode_decoder", decoded.text).put("package_stock", stock.quantity)
                        val bitmap = Bitmap.createBitmap(1200, 570, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 38f; typeface = Typeface.MONOSPACE }
                        val text = listOf("CAMARA TEST V04", LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), "2 x OREZ TEST        9,00 A", "TOTAL               9,00", "CARD                9,00")
                        text.forEachIndexed { index, line -> canvas.drawText(line, 70f, 90f + index * 85, paint) }
                        val uri = ImageFiles.captureUri(this@V04ProbeActivity)
                        contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                        val jpeg = ImageFiles.jpeg(this@V04ProbeActivity, uri)
                        val started = android.os.SystemClock.elapsedRealtime()
                        val preview = api.scanReceipt(jpeg)
                        val elapsed = android.os.SystemClock.elapsedRealtime() - started
                        metadata.put("image_sha256", preview.image_sha256)
                        val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "Camara-test-v04.jpg"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camara-tests"); put(MediaStore.Images.Media.IS_PENDING, 1) }
                        val publicUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                        metadata.put("media_uri", publicUri.toString()); metadataFile.writeText(metadata.toString(2))
                        contentResolver.openOutputStream(publicUri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                        contentResolver.update(publicUri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                        bitmap.recycle()
                        check(preview.total_cents == 900) { "OCR total: ${preview.total_cents}; ${preview.raw_text}" }
                        check(preview.lines.any { it.name == product.name && it.quantity == 1000.0 && it.unit == "g" }) { "OCR catalog: ${preview.raw_text}" }
                        result.put("ocr_milliseconds", elapsed).put("ocr_total_cents", preview.total_cents).put("ocr_package_grams", 1000).put("media_uri", publicUri.toString())
                        val options = api.recipeOptions(RecipeRequest(max_minutes = 15, equipment = listOf("Plită", "Oală"), diet = "vegan", pantry_only = true))
                        check(options.options.any { it.id == "personal-${own.id}" })
                        val selected = LocalAi.get(this@V04ProbeActivity).select(options, useCache = false)
                        check(options.options.any { it.id == selected.recipeId })
                        result.put("personal_recipe_in_options", true).put("native_ai_selection", selected.recipeId)
                    }
                    "personal" -> {
                        val metadata = JSONObject(metadataFile.readText())
                        val options = api.recipeOptions(RecipeRequest(max_minutes = 15, equipment = listOf("Plită", "Oală"), diet = "vegan", pantry_only = true))
                        val selected = LocalAi.get(this@V04ProbeActivity).select(options, useCache = false)
                        check(selected.recipeId == "personal-${metadata.getString("personal_id")}")
                        result.put("native_personal_selection", selected.recipeId).put("milliseconds", selected.milliseconds)
                    }
                    "observe" -> {
                        val wanted = intent.getStringExtra("product")
                        val product = local.server.products.first { it.id == wanted }
                        result.put("shared_catalog_product", product.name).put("package_quantity", product.package_quantity)
                    }
                    "cleanup" -> {
                        val metadata = JSONObject(metadataFile.readText())
                        val records = metadata.getJSONArray("records")
                        val changes = (0 until records.length()).mapNotNull { index ->
                            val record = records.getJSONObject(index); val kind = record.getString("kind"); val id = record.getString("id")
                            ActionRules.find(local.server, kind, id)?.let { Change(kind, id, it, null) }
                        }
                        if (changes.isNotEmpty()) api.applyAction(UUID.randomUUID().toString(), Mutation("Elimină numai probele v04", changes, epoch = local.server.epoch))
                        metadata.optString("media_uri").takeIf { it.isNotEmpty() }?.let { contentResolver.delete(android.net.Uri.parse(it), null, null) }
                        metadata.put("cleaned", true); metadataFile.writeText(metadata.toString(2))
                        SyncEngine.sync(this@V04ProbeActivity, config)
                        result.put("temporary_records_removed", changes.size)
                    }
                    else -> error("Unknown task")
                }
            } catch (e: Exception) { result.put("error", e.toString()).put("cause", e.cause?.toString()) }
            File(filesDir, "v04-result.json").writeText(result.toString(2))
            withContext(Dispatchers.Main) { finish() }
        }
    }
}
