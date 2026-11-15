package ro.camara.app.debug

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import ro.camara.app.ai.LocalAi
import ro.camara.app.data.CamaraApi
import ro.camara.app.data.ConnectionStore
import ro.camara.app.data.RecipeRequest
import ro.camara.app.notifications.ReminderScheduler
import ro.camara.app.ui.CamaraTheme

/** Debug-only, restricted to adb/system callers by the DUMP permission. */
class AiBenchmarkActivity : ComponentActivity() {
    private val status = mutableStateOf("Se pregătește testul local…")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CamaraTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(28.dp)) {
            Text("Cămara · verificare", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(20.dp)); Text(status.value)
        } } } }
        lifecycleScope.launch(Dispatchers.IO) {
            val output = JSONObject().put("device", Build.MODEL)
            try {
                if (intent.getStringExtra("task") == "notification") {
                    val ok = ReminderScheduler.post(this@AiBenchmarkActivity, "Cămara · test notificare", "Notificările sunt pregătite pentru rezumatul de la 19:00.", true)
                    output.put("notification_posted", ok)
                } else {
                    val config = checkNotNull(ConnectionStore(this@AiBenchmarkActivity).read())
                    val api = CamaraApi(config)
                    val local = LocalAi.get(this@AiBenchmarkActivity)
                    local.download(api) { percent -> lifecycleScope.launch { status.value = "Descărcare model verificat: $percent%" } }
                    val options = api.recipeOptions(RecipeRequest(max_minutes = 20))
                    val runs = JSONArray()
                    repeat(3) { index ->
                        withContext(Dispatchers.Main) { status.value = "Test AI ${index + 1}/3…" }
                        val selection = local.select(options, useCache = false)
                        runs.put(JSONObject().put("milliseconds", selection.milliseconds).put("recipe_id", selection.recipeId).put("cached", selection.cached))
                    }
                    output.put("runs", runs).put("model_ready", local.isReady())
                }
            } catch (e: Throwable) { output.put("error", e.toString()).put("cause", e.cause?.toString()) }
            File(filesDir, "benchmark.json").writeText(output.toString(2))
            withContext(Dispatchers.Main) { status.value = output.toString(2); if (intent.getBooleanExtra("finish", false)) finish() }
        }
    }
}
