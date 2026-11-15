package ro.camara.app.data

import java.time.LocalDate
import java.util.UUID

object PreparedRules {
    fun reserved(snapshot: Snapshot, id: String, exceptPlan: String? = null) = snapshot.plans.filter { !it.completed && it.prepared_batch_id == id && it.id != exceptPlan }.sumOf { it.servings }
    fun cook(snapshot: Snapshot, recipe: Recipe, portions: Int, uses: List<StockUse>, details: BatchDetails, planId: String? = null): PendingAction {
        require(portions in 1..8)
        val cooked = LocalDate.parse(details.cookedOn)
        require(cooked <= LocalDate.now() && LocalDate.parse(details.expiresOn) >= cooked) { "Verifică data gătirii și termenul ales de tine." }
        require(details.location in listOf("frigider", "congelator"))
        val id = UUID.randomUUID().toString()
        val batch = PreparedBatch(id, recipe, details.cookedOn, details.expiresOn, details.location, portions, portions)
        val base = if (uses.isNotEmpty()) RecipeMath.consume(snapshot, recipe.title, uses) else null
        val event = FoodEvent(UUID.randomUUID().toString(), "prepared", recipe.title, details.cookedOn, uses, batch_id = id, portions = portions)
        val changes = base?.mutation?.changes?.filterNot { it.kind == "events" }?.toMutableList() ?: mutableListOf()
        changes += Change("prepared", id, after = ActionRules.record(batch))
        changes += Change("events", event.id, after = ActionRules.record(event))
        if (planId != null) {
            val plan = snapshot.plans.first { it.id == planId }
            require(!plan.completed && plan.prepared_batch_id == null && plan.servings <= portions && plan.on_date >= details.cookedOn && plan.on_date <= details.expiresOn) { "Data sau porțiile mesei nu corespund lotului gătit." }
            changes += ActionRules.change(snapshot, "plans", planId, ActionRules.record(plan.copy(prepared_batch_id = id)))
        }
        return ActionRules.action("Porții gătite: ${recipe.title}", changes, false, listOf("plans", "prepared").associateWith { ActionRules.records(snapshot, it) })
    }
    fun serve(snapshot: Snapshot, batch: PreparedBatch, portions: Int, wasted: Boolean, planId: String? = null, today: LocalDate = LocalDate.now()): PendingAction {
        val current = snapshot.prepared.firstOrNull { it.id == batch.id }
        require(current == batch && portions in 1..batch.remaining) { "Porțiile au fost schimbate. Actualizează lista." }
        require(wasted || batch.expires_on >= today.toString()) { "Termenul introdus a trecut." }
        require(batch.remaining - portions >= reserved(snapshot, batch.id, planId)) { "Porțiile sunt rezervate în plan. Folosește masa din plan sau modifică rezervarea." }
        val event = FoodEvent(UUID.randomUUID().toString(), if (wasted) "portion_wasted" else "served", batch.recipe.title, today.toString(), emptyList(), plan_id = planId, batch_id = batch.id, portions = portions)
        val changes = mutableListOf(ActionRules.change(snapshot, "prepared", batch.id, ActionRules.record(batch.copy(remaining = batch.remaining - portions))), Change("events", event.id, after = ActionRules.record(event)))
        if (planId != null) {
            val plan = snapshot.plans.first { it.id == planId }
            require(!plan.completed && plan.prepared_batch_id == batch.id && plan.servings == portions)
            changes += ActionRules.change(snapshot, "plans", plan.id, ActionRules.record(plan.copy(completed = true)))
        }
        return ActionRules.action(if (wasted) "Porții aruncate" else "Porții mâncate", changes, false, mapOf("plans" to ActionRules.records(snapshot, "plans")))
    }
    fun plan(snapshot: Snapshot, plan: MealPlan): PendingAction {
        val batch = plan.prepared_batch_id?.let { id -> snapshot.prepared.first { it.id == id } }
        if (batch != null) require(plan.servings <= batch.remaining - reserved(snapshot, batch.id, plan.id) && plan.on_date >= batch.cooked_on && plan.on_date <= batch.expires_on) { "Verifică porțiile libere și termenul lotului." }
        return ActionRules.action("Plan: ${plan.recipe.title}", listOf(ActionRules.change(snapshot, "plans", plan.id, ActionRules.record(plan))), false,
            listOf("plans", "prepared").associateWith { ActionRules.records(snapshot, it) })
    }
}
