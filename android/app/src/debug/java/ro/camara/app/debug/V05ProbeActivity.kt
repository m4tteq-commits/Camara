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
import java.time.Instant
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray
import ro.camara.app.data.*
import ro.camara.app.ui.CamaraTheme

/** Debug-only local integration probe; system DUMP permission is required to launch it. */
class V05ProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CamaraTheme { Surface { Text("Cămara · verificare 0.5") } } }
        lifecycleScope.launch(Dispatchers.IO) {
            val result = JSONObject()
            val file = File(filesDir, "v05-probe.json")
            try {
                val config = checkNotNull(ConnectionStore(this@V05ProbeActivity).read())
                val store = OfflineStore(this@V05ProbeActivity)
                var local = SyncEngine.sync(this@V05ProbeActivity, config)
                check(local.pending.isEmpty()) { "Resolve pending actions before probing." }
                val api = CamaraApi(config, epoch = local.server.epoch)
                if (intent.getStringExtra("task") == "cleanup") {
                    val metadata = JSONObject(file.readText())
                    val records = metadata.getJSONArray("records")
                    val changes = (0 until records.length()).mapNotNull { index ->
                        val record = records.getJSONObject(index)
                        val kind = record.getString("kind"); val id = record.getString("id")
                        ActionRules.find(local.server, kind, id)?.let { Change(kind, id, it, null) }
                    }
                    if (changes.isNotEmpty()) api.applyAction(UUID.randomUUID().toString(), Mutation("Elimină numai probele v05", changes, epoch = local.server.epoch))
                    metadata.put("cleaned", true); file.writeText(metadata.toString(2))
                    SyncEngine.sync(this@V05ProbeActivity, config)
                    result.put("temporary_records_removed", changes.size)
                } else {
                    check(!file.exists() || JSONObject(file.readText()).optBoolean("cleaned")) { "Earlier v05 probe needs cleanup." }
                    val metadata = JSONObject().put("records", JSONArray())
                    file.writeText(metadata.toString(2))
                    suspend fun apply(build: (Snapshot) -> PendingAction) {
                        val action = build(local.projected())
                        val records = metadata.getJSONArray("records")
                        action.mutation.changes.filter { it.before == null && it.after != null }.forEach { change ->
                            val known = (0 until records.length()).any { records.getJSONObject(it).let { row -> row.getString("kind") == change.kind && row.getString("id") == change.id } }
                            if (!known) records.put(JSONObject().put("kind", change.kind).put("id", change.id))
                        }
                        file.writeText(metadata.toString(2))
                        store.enqueue(config) { action }
                        check(store.read(config).pending.isNotEmpty())
                        local = SyncEngine.sync(this@V05ProbeActivity, config)
                        check(local.pending.isEmpty()) { "Probe action needs conflict review." }
                    }
                    val today = LocalDate.now()
                    val own = PersonalRecipe(UUID.randomUUID().toString(), "Test v05 Paste pentru alte zile", 15, 1,
                        listOf(Ingredient("Test v05 Paste", "100 g")), listOf("Respectă instrucțiunile de preparare de pe ambalaj."),
                        listOf("Plită", "Oală"), "vegan", "main", Instant.now().toString())
                    val shopping = ShoppingItem(UUID.randomUUID().toString(), "Test v05 Paste", "500 g")
                    apply { ActionRules.action("Probe v05 · listă și rețetă", listOf(Change("personal_recipes", own.id, after = ActionRules.record(own)), Change("shopping", shopping.id, after = ActionRules.record(shopping)))) }
                    val cart = ShoppingCart(UUID.randomUUID().toString(), "Test v05 Magazin", today.toString(), listOf(CartRow(UUID.randomUUID().toString(), shopping.name, "500", "g", "12,50", expires = today.plusDays(2).toString(), shoppingId = shopping.id)))
                    val carts = CartStore(this@V05ProbeActivity)
                    if (carts.read(config) == null) {
                        carts.save(config, cart)
                        check(carts.read(config) == cart)
                        val newer = cart.copy(revision = 2, merchant = "Test v05 salvat")
                        carts.save(config, newer); carts.save(config, cart)
                        check(carts.read(config) == newer)
                        carts.clear(config, cart.id); carts.save(config, newer)
                        check(carts.read(config) == null)
                        val nextCart = cart.copy(id = UUID.randomUUID().toString())
                        carts.save(config, nextCart); carts.clear(config, cart.id); carts.save(config, newer)
                        check(carts.read(config) == nextCart)
                        carts.clear(config, nextCart.id)
                        result.put("persistent_cart_and_late_write_verified", true)
                    }
                    apply { CheckoutRules.checkout(it, cart) }
                    check(local.server.shopping.first { it.id == shopping.id }.checked)
                    check(local.server.purchases.first { it.id == cart.id }.total_cents == 1250)
                    val stock = local.server.pantry.first { it.name == shopping.name }
                    check(stock.quantity == 500.0)
                    apply { PreparedRules.cook(it, own.recipe(), 4, listOf(StockUse(stock, 400.0)), BatchDetails(today.toString(), today.plusDays(1).toString(), "frigider")) }
                    val batch = local.server.prepared.first { it.recipe.id == own.id }
                    val plan = MealPlan(UUID.randomUUID().toString(), today.toString(), recipe = batch.recipe, servings = 1, prepared_batch_id = batch.id)
                    apply { PreparedRules.plan(it, plan) }
                    apply { PreparedRules.serve(it, it.prepared.first { row -> row.id == batch.id }, 1, false, plan.id) }
                    check(local.server.prepared.first { it.id == batch.id }.remaining == 3)
                    check(local.server.pantry.first { it.id == stock.id }.quantity == 100.0)
                    check(local.server.plans.first { it.id == plan.id }.completed)
                    result.put("checkout_total_cents", 1250).put("shopping_checked", true).put("raw_stock_remaining_grams", 100)
                        .put("prepared_remaining", 3).put("plan_completed", true).put("durable_outbox_synced", true)
                }
            } catch (e: Exception) { result.put("error", e.toString()).put("cause", e.cause?.toString()) }
            File(filesDir, "v05-result.json").writeText(result.toString(2))
            withContext(Dispatchers.Main) { finish() }
        }
    }
}
