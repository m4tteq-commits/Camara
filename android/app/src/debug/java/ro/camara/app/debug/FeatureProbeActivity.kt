package ro.camara.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import ro.camara.app.data.*
import ro.camara.app.ui.CamaraTheme
import kotlinx.serialization.json.jsonPrimitive

/** USB/system-only scenario runner. No exported probe exists in release builds. */
class FeatureProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CamaraTheme { Surface { Text("Cămara · verificare sincronizare") } } }
        lifecycleScope.launch(Dispatchers.IO) {
            val output = JSONObject()
            try {
                val config = checkNotNull(ConnectionStore(this@FeatureProbeActivity).read())
                val store = OfflineStore(this@FeatureProbeActivity)
                val api = CamaraApi(config, fast = true)
                val metadataFile = File(filesDir, "v03-probe.json")
                var metadata = if (metadataFile.exists()) JSONObject(metadataFile.readText()) else JSONObject()
                fun saveMetadata() { metadataFile.writeText(metadata.toString(2)) }
                fun enqueue(build: (Snapshot) -> PendingAction) {
                    val action = build(store.read(config).projected())
                    val records = metadata.getJSONArray("records")
                    action.mutation.changes.filter { it.before == null && it.after != null }.forEach { change ->
                        if ((0 until records.length()).none { records.getJSONObject(it).getString("id") == change.id }) records.put(JSONObject().put("kind", change.kind).put("id", change.id))
                    }
                    saveMetadata() // Ownership is recorded before any persistent mutation.
                    store.enqueue(config) { action }
                }
                fun verify(snapshot: Snapshot) {
                    val stock = snapshot.pantry.first { it.id == metadata.getString("stock") }
                    check(stock.quantity == 480.0)
                    check(snapshot.expenses.first { it.id == metadata.getString("expense") }.amount_cents == 1575)
                    check(snapshot.plans.first { it.id == metadata.getString("plan") }.completed.not())
                    val mine = (0 until metadata.getJSONArray("records").length()).map { metadata.getJSONArray("records").getJSONObject(it).getString("id") }.toSet()
                    check(snapshot.events.filter { it.id in mine }.count { it.kind == "cooked" && it.undone } == 1)
                    check(snapshot.events.filter { it.id in mine }.count { it.kind == "wasted" && !it.undone && it.value_cents == 100 } == 1)
                    check(snapshot.shopping.first { it.id == metadata.getString("shopping") }.quantity == "120 g")
                }
                when (intent.getStringExtra("task")) {
                    "prepare" -> {
                        check(store.read(config).pending.isEmpty())
                        SyncEngine.sync(this@FeatureProbeActivity, config)
                        output.put("prepared", true)
                    }
                    "stage" -> {
                        check(!metadataFile.exists() || metadata.optBoolean("cleaned")) { "An earlier probe needs cleanup." }
                        check(store.read(config).pending.isEmpty())
                        val stockId = UUID.randomUUID().toString()
                        val expenseId = UUID.randomUUID().toString()
                        val planId = UUID.randomUUID().toString()
                        metadata = JSONObject().put("records", JSONArray()).put("stock", stockId).put("expense", expenseId).put("plan", planId)
                        saveMetadata()
                        val today = LocalDate.now()
                        val item = PantryItem(stockId, "Test v03 Orez", 500.0, "g", expires_on = today.plusDays(20).toString())
                        val recipe = Recipe(UUID.randomUUID().toString(), "Test v03 Orez", 10, 1,
                            listOf(Ingredient(item.name, "100 g")), listOf("Fierbe orezul conform ambalajului."), model = "test", generated_at = java.time.Instant.now().toString(), generation_ms = 0)
                        val plan = MealPlan(planId, today.plusDays(1).toString(), recipe = recipe, servings = 2)
                        val second = plan.copy(id = UUID.randomUUID().toString(), on_date = today.plusDays(2).toString(), servings = 4)
                        val expense = Expense(expenseId, "Test v03 bon", 1234, today.toString())
                        val budget = Budget(UUID.randomUUID().toString(), "2099-01", 50000)
                        enqueue { ActionRules.action("Test v03 date offline", listOf(
                            Change("pantry", item.id, after = ActionRules.record(item)), Change("plans", plan.id, after = ActionRules.record(plan)),
                            Change("plans", second.id, after = ActionRules.record(second)), Change("expenses", expense.id, after = ActionRules.record(expense)),
                            Change("budgets", budget.id, after = ActionRules.record(budget)))) }
                        enqueue { RecipeMath.consume(it, recipe.title, listOf(StockUse(item, 200.0)), planId = planId) }
                        enqueue { RecipeMath.undo(it, it.events.first { row -> row.title == recipe.title && row.kind == "cooked" }.id) }
                        enqueue { snapshot -> RecipeMath.consume(snapshot, item.name, listOf(StockUse(snapshot.pantry.first { it.id == stockId }, 20.0)), wasted = true, reason = "Test v03", cents = 100) }
                        enqueue { snapshot -> val preview = RecipeMath.shopping(snapshot, listOf(plan, second)); ActionRules.action("Test v03 listă plan", preview.changes, rebasable = false, guards = preview.guards) }
                        metadata.put("shopping", store.read(config).projected().shopping.first { it.name == item.name }.id); saveMetadata()
                        enqueue { ActionRules.action("Test v03 editare bon", listOf(ActionRules.change(it, "expenses", expenseId, ActionRules.record(expense.copy(amount_cents = 1575))))) }
                        repeat(2) { index -> enqueue { snapshot ->
                            val shopping = snapshot.shopping.first { it.id == metadata.getString("shopping") }
                            ActionRules.action("Test v03 bifare", listOf(ActionRules.change(snapshot, "shopping", shopping.id, ActionRules.record(shopping.copy(checked = index == 0)))))
                        } }
                        val local = store.read(config)
                        verify(local.projected())
                        output.put("staged", true).put("pending", local.pending.size).put("stock", 480)
                    }
                    "verify-local" -> {
                        val local = store.read(config)
                        verify(local.projected())
                        check(local.pending.size == 8)
                        output.put("persisted_after_restart", true).put("pending", local.pending.size)
                    }
                    "sync" -> {
                        val local = SyncEngine.sync(this@FeatureProbeActivity, config)
                        check(local.pending.isEmpty()) { local.pending.firstOrNull()?.issue.orEmpty() }
                        verify(api.snapshot())
                        output.put("synced", true).put("pending", 0)
                    }
                    "conflict" -> {
                        SyncEngine.sync(this@FeatureProbeActivity, config)
                        val item = store.read(config).projected().pantry.first { it.id == metadata.getString("stock") }
                        enqueue { ActionRules.action("Test v03 conflict", listOf(ActionRules.change(it, "pantry", item.id, ActionRules.record(item.copy(quantity = 479.0))))) }
                        api.putPantry(item.id, item.copy(quantity = 478.0).input())
                        val local = SyncEngine.sync(this@FeatureProbeActivity, config)
                        check(local.pending.single().issue != null)
                        check(api.snapshot().pantry.first { it.id == item.id }.quantity == 478.0)
                        output.put("conflict_detected", true).put("server_quantity", 478).put("local_quantity", local.projected().pantry.first { it.id == item.id }.quantity)
                    }
                    "resolve" -> {
                        val pending = store.read(config).pending.first()
                        SyncEngine.resolve(this@FeatureProbeActivity, config, pending.id, keepMine = true)
                        val local = SyncEngine.sync(this@FeatureProbeActivity, config)
                        check(local.pending.isEmpty())
                        check(api.snapshot().pantry.first { it.id == metadata.getString("stock") }.quantity == 479.0)
                        output.put("explicit_resolution", true)
                    }
                    "observe" -> {
                        val local = SyncEngine.sync(this@FeatureProbeActivity, config)
                        val wanted = intent.getStringExtra("stock")
                        val item = local.server.pantry.first { it.id == wanted }
                        output.put("observed_shared_stock", item.quantity)
                    }
                    "cleanup" -> {
                        check(store.read(config).pending.isEmpty())
                        val remote = api.snapshot()
                        val records = metadata.getJSONArray("records")
                        val changes = (0 until records.length()).mapNotNull { index ->
                            val record = records.getJSONObject(index)
                            val kind = record.getString("kind"); val id = record.getString("id")
                            ActionRules.find(remote, kind, id)?.let { Change(kind, id, it, null) }
                        }
                        if (changes.isNotEmpty()) api.applyAction(UUID.randomUUID().toString(), Mutation("Elimină numai probele v03", changes))
                        SyncEngine.sync(this@FeatureProbeActivity, config)
                        metadata.put("cleaned", true); saveMetadata()
                        output.put("known_temporary_records_removed", changes.size)
                    }
                    else -> error("Unknown probe task")
                }
            } catch (e: Exception) { output.put("error", e.toString()).put("cause", e.cause?.toString()) }
            File(filesDir, "v03-result.json").writeText(output.toString(2))
            withContext(Dispatchers.Main) { finish() }
        }
    }
}
