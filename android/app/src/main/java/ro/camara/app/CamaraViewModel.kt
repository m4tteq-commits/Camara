package ro.camara.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.util.UUID
import javax.net.ssl.SSLException
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.camara.app.data.*
import ro.camara.app.ai.AiPreferences
import ro.camara.app.ai.LocalAi
import ro.camara.app.ai.LocalAiUnavailable
import ro.camara.app.notifications.ReminderSettings
import ro.camara.app.notifications.ReminderStore
import ro.camara.app.notifications.ReminderScheduler

data class QuickSuggestions(val request: RecipeRequest, val recipes: List<Recipe>, val fromCatalog: Boolean, val note: String?)

data class UiState(
    val initializing: Boolean = true,
    val config: ConnectionConfig? = null,
    val snapshot: Snapshot = Snapshot(),
    val online: Boolean = false,
    val busy: Boolean = false,
    val generating: Boolean = false,
    val suggesting: Boolean = false,
    val quickSuggestions: QuickSuggestions? = null,
    val lastSync: Long? = null,
    val aiStatus: AiStatus? = null,
    val cloudProviders: Map<String, String> = emptyMap(),
    val message: String? = null,
    val aiMode: String = "groq",
    val localModelReady: Boolean = false,
    val modelDownloadProgress: Int? = null,
    val reminderSettings: ReminderSettings = ReminderSettings(),
    val pending: List<PendingAction> = emptyList(),
    val syncing: Boolean = false,
    val serverSnapshot: Snapshot = Snapshot(),
    val localRevision: Long = 0,
    val backupOverview: BackupOverview? = null,
    val receiptReading: Boolean = false,
    val cart: ShoppingCart? = null,
)

class CamaraViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ConnectionStore(application)
    private val offline = OfflineStore(application)
    private val aiPreferences = AiPreferences(application)
    private val localAi = LocalAi.get(application)
    private val reminders = ReminderStore(application)
    private val carts = CartStore(application)
    var state by mutableStateOf(UiState(busy = true, aiMode = aiPreferences.mode,
        localModelReady = localAi.isReady(), reminderSettings = reminders.read()))
        private set
    private var providersCheckedAt: Long? = null
    private var localAiCheckedAt: Long? = null

    init {
        viewModelScope.launch {
            try {
                val config = withContext(Dispatchers.IO) { store.read() }
                state = state.copy(config = config)
                if (config != null) showLocal(withContext(Dispatchers.IO) { offline.read(config) })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state = state.copy(message = "Nu pot citi jurnalul local. Datele nu au fost suprascrise.")
            } finally {
                state = state.copy(initializing = false, busy = false)
            }
        }
    }

    private fun showLocal(local: LocalState) {
        if (local.revision < state.localRevision) return
        val projected = local.projected()
        val sameRecipeInputs = projected.pantry == state.snapshot.pantry && projected.personal_recipes == state.snapshot.personal_recipes &&
            local.server.epoch == state.serverSnapshot.epoch
        state = state.copy(snapshot = projected, serverSnapshot = local.server, lastSync = local.savedAt,
            pending = local.pending, localRevision = local.revision, quickSuggestions = state.quickSuggestions.takeIf { sameRecipeInputs })
    }

    private fun failure(e: Exception) {
        if (e is CancellationException) throw e
        state = state.copy(
            online = if (e is IOException || (e is ApiException && e.code == 401)) false else state.online,
            message = if (e is IOException) "Nu pot confirma operația pe server. Verifică rețeaua și actualizează lista." else e.message ?: "Operația nu a reușit.",
        )
    }

    fun connect(candidate: ConnectionConfig) {
        if (state.busy || state.generating || state.syncing) return
        state = state.copy(busy = true)
        viewModelScope.launch {
            try {
                val (config, local) = withContext(Dispatchers.IO) { SyncEngine.changeConnection(getApplication(), candidate) }
                providersCheckedAt = null
                localAiCheckedAt = null
                getApplication<CamaraApplication>().serverAiPresence.settingsChanged()
                val cart = state.cart.takeIf { state.config?.storageIdentity == config.storageIdentity }
                state = state.copy(config = config, snapshot = local.projected(), serverSnapshot = local.server, pending = local.pending, localRevision = local.revision, online = true, busy = true, lastSync = local.savedAt, message = "Conectat la serverul tău.", cart = cart, quickSuggestions = null, aiStatus = null, cloudProviders = emptyMap())
                if (local.pending.isNotEmpty()) SyncEngine.schedule(getApplication(), now = true)
                refreshAi(config, force = true)
            } catch (e: Exception) { failure(e) }
            finally { state = state.copy(busy = false) }
        }
    }

    fun refresh(silent: Boolean = false) {
        val config = state.config ?: return
        if (state.busy || state.generating || state.syncing) return
        state = state.copy(syncing = true)
        viewModelScope.launch {
            try {
                showLocal(withContext(Dispatchers.IO) { SyncEngine.sync(getApplication(), config, reuseRecent = silent) })
                state = state.copy(online = true)
                refreshAi(config, force = !silent)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state = state.copy(online = false)
                runCatching { withContext(Dispatchers.IO) { offline.read(config) } }
                    .onFailure { if (it is CancellationException) throw it }.onSuccess(::showLocal)
                if (!silent) failure(e)
            } finally { state = state.copy(syncing = false) }
        }
    }

    private suspend fun refreshAi(config: ConnectionConfig, force: Boolean = false) = coroutineScope {
        val now = SystemClock.elapsedRealtime()
        val checkProviders = force || providersCheckedAt?.let { now - it >= 300_000 } != false
        val checkLocal = state.aiMode == "server" && (force || localAiCheckedAt?.let { now - it >= 60_000 } != false)
        if (!checkProviders && !checkLocal) return@coroutineScope
        val api = CamaraApi(config, fast = true)
        val providers = if (checkProviders) async {
            runCatching { api.cloudProviders() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        } else null
        val localStatus = if (checkLocal) {
            runCatching { api.aiStatus() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        } else null
        val cloud = providers?.await()
        if (state.config == config) {
            if (cloud != null) providersCheckedAt = now
            if (localStatus != null) localAiCheckedAt = now
            state = state.copy(aiStatus = if (state.aiMode == "server") localStatus ?: state.aiStatus else null,
                cloudProviders = cloud ?: state.cloudProviders)
        }
    }

    fun networkUnavailable() { state = state.copy(online = false) }

    private fun enqueue(onSuccess: () -> Unit = {}, build: (Snapshot) -> PendingAction) {
        val config = state.config ?: return
        if (state.busy || state.generating) {
            state = state.copy(message = "Se finalizează operația curentă. Încearcă din nou într-un moment.")
            return
        }
        state = state.copy(busy = true)
        viewModelScope.launch {
            try {
                val local = withContext(Dispatchers.IO) { offline.enqueue(config, build) }
                showLocal(local)
                state = state.copy(message = "Salvat pe dispozitiv · se sincronizează când serverul este disponibil.")
                SyncEngine.schedule(getApplication(), now = true)
                onSuccess()
            } catch (e: Exception) { failure(e) }
            finally { state = state.copy(busy = false); refresh(silent = true) }
        }
    }

    fun savePantry(id: String, item: PantryInput, onSuccess: () -> Unit) = enqueue(onSuccess) { snapshot ->
        val value = PantryItem(id, item.name, item.quantity, item.unit, item.location, item.expires_on)
        ActionRules.action("Aliment: ${item.name}", listOf(ActionRules.change(snapshot, "pantry", id, ActionRules.record(value))))
    }
    fun deletePantry(id: String) = enqueue { ActionRules.action("Elimină aliment", listOf(ActionRules.change(it, "pantry", id, null))) }
    internal fun importScans(epoch: String, rows: List<ScannedProduct>, onSuccess: () -> Unit) = enqueue(onSuccess) {
        scannedStockAction(it, epoch, rows)
    }
    fun saveShopping(item: ShoppingItem) = enqueue { ActionRules.action("Cumpărături: ${item.name}", listOf(ActionRules.change(it, "shopping", item.id, ActionRules.record(item)))) }
    fun addShopping(id: String, name: String, quantity: String, onSuccess: () -> Unit) = saveShoppingNew(ShoppingItem(id, name, quantity), onSuccess)
    private fun saveShoppingNew(item: ShoppingItem, onSuccess: () -> Unit) = enqueue(onSuccess) { ActionRules.action("Cumpărături: ${item.name}", listOf(ActionRules.change(it, "shopping", item.id, ActionRules.record(item)))) }
    fun deleteShopping(id: String) = enqueue { ActionRules.action("Elimină produs", listOf(ActionRules.change(it, "shopping", id, null))) }
    fun repeatPurchase(entryKey: String, selected: Set<Int>, onSuccess: () -> Unit) = enqueue(onSuccess) { snapshot ->
        PurchaseHistory.repeat(snapshot, entryKey, selected)
    }
    fun addMissing(recipe: Recipe) = enqueue { snapshot ->
        val preview = RecipeMath.shopping(snapshot, listOf(MealPlan(UUID.randomUUID().toString(), java.time.LocalDate.now().toString(), recipe = recipe, servings = recipe.servings)))
        require(preview.changes.isNotEmpty()) { "Lista și inventarul acoperă ingredientele necesare." }
        ActionRules.action("Ingrediente pentru ${recipe.title}", preview.changes, rebasable = false, guards = preview.guards)
    }
    fun savePlan(plan: MealPlan, onSuccess: () -> Unit) = enqueue(onSuccess) { PreparedRules.plan(it, plan) }
    fun saveStockMinimum(previous: StockMinimum?, minimum: StockMinimum, onSuccess: () -> Unit) = enqueue(onSuccess) {
        StockMinimumRules.save(it, previous, minimum)
    }
    fun deleteStockMinimum(minimum: StockMinimum, onSuccess: () -> Unit) = enqueue(onSuccess) {
        StockMinimumRules.remove(it, minimum)
    }
    fun replenish(preview: ShoppingPreview) = enqueue {
        ActionRules.action("Reaprovizionare", preview.changes, rebasable = false, guards = preview.guards)
    }
    fun quickUse(item: PantryItem, amount: Double, onSuccess: () -> Unit) = enqueue(onSuccess) {
        RecipeMath.consume(it, item.name, listOf(StockUse(item, amount)), quickUse = true)
    }
    suspend fun useSoonIdeas(config: ConnectionConfig): List<UseSoonIdea> = CamaraApi(config, fast = true).useSoonIdeas()
    fun deletePlan(id: String) = enqueue { ActionRules.action("Elimină masă din plan", listOf(ActionRules.change(it, "plans", id, null))) }
    fun saveExpense(expense: Expense, onSuccess: () -> Unit) = enqueue(onSuccess) { ActionRules.action("Cheltuială: ${expense.label}", listOf(ActionRules.change(it, "expenses", expense.id, ActionRules.record(expense)))) }
    fun deleteExpense(id: String) = enqueue { ActionRules.action("Elimină cheltuială", listOf(ActionRules.change(it, "expenses", id, null))) }
    fun saveBudget(month: String, cents: Int, onSuccess: () -> Unit) = enqueue(onSuccess) {
        val value = Budget(UUID.nameUUIDFromBytes("camara-budget:$month".toByteArray()).toString(), month, cents)
        ActionRules.action("Buget: $month", listOf(ActionRules.change(it, "budgets", value.id, ActionRules.record(value))))
    }
    fun consume(title: String, uses: List<StockUse>, wasted: Boolean, reason: String, cents: Int?, planId: String?, onSuccess: () -> Unit) = enqueue(onSuccess) { RecipeMath.consume(it, title, uses, wasted, reason, cents, planId = planId) }
    fun undoEvent(id: String) = enqueue { RecipeMath.undo(it, id) }
    fun addPlannedShopping(preview: ShoppingPreview, onSuccess: () -> Unit) = enqueue(onSuccess) { ActionRules.action("Cumpărături pentru planul săptămânii", preview.changes, rebasable = false, guards = preview.guards) }
    fun resolveConflict(id: String, keepMine: Boolean) {
        val config = state.config ?: return
        if (state.busy) return
        state = state.copy(busy = true)
        viewModelScope.launch {
            try { showLocal(withContext(Dispatchers.IO) { SyncEngine.resolve(getApplication(), config, id, keepMine) }); SyncEngine.schedule(getApplication(), now = true) }
            catch (e: Exception) { failure(e) }
            finally { state = state.copy(busy = false); refresh(silent = true) }
        }
    }
    fun clearMessage() { state = state.copy(message = null) }
    fun message(text: String) { state = state.copy(message = text) }

    fun startCart() {
        val config = state.config ?: return
        if (state.cart != null) return
        val snapshot = state.snapshot
        viewModelScope.launch { try {
            val draft = withContext(Dispatchers.IO) { carts.open(config, snapshot) }
            if (state.config == config && state.cart == null) state = state.copy(cart = draft)
        } catch (e: Exception) { failure(e) } }
    }
    fun updateCart(value: ShoppingCart) {
        val config = state.config ?: return
        val current = state.cart ?: return
        if (state.busy || value.id != current.id) return
        val next = value.copy(revision = current.revision + 1)
        state = state.copy(cart = next)
        viewModelScope.launch { try { withContext(Dispatchers.IO) { carts.save(config, next) } } catch (e: Exception) { failure(e) } }
    }
    fun discardCart(onSuccess: () -> Unit) {
        val config = state.config ?: return
        val id = state.cart?.id ?: return
        if (state.busy) return
        state = state.copy(busy = true)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { carts.clear(config, id) }; state = state.copy(cart = null); onSuccess() }
            catch (e: Exception) { failure(e) }
            finally { state = state.copy(busy = false) }
        }
    }
    fun checkout(onSuccess: () -> Unit) {
        val cart = state.cart ?: return
        enqueue(onSuccess = {
            state.config?.let { config -> viewModelScope.launch { try { withContext(Dispatchers.IO) { carts.clear(config, cart.id) } } catch (e: Exception) { failure(e) } } }
            state = state.copy(cart = null); onSuccess()
        }) { CheckoutRules.checkout(it, cart) }
    }
    fun cookBatch(recipe: Recipe, portions: Int, uses: List<StockUse>, details: BatchDetails, planId: String?, onSuccess: () -> Unit) = enqueue(onSuccess) { PreparedRules.cook(it, recipe, portions, uses, details, planId) }
    fun serveBatch(batch: PreparedBatch, portions: Int, wasted: Boolean, planId: String?, onSuccess: () -> Unit) = enqueue(onSuccess) { PreparedRules.serve(it, batch, portions, wasted, planId) }
    fun previewWeek(request: WeekRequest, onReady: (WeekPreview) -> Unit) {
        val config = state.config ?: return
        if (state.busy || state.generating || state.syncing) return
        if (state.pending.isNotEmpty()) { message("Sincronizează întâi modificările înainte de planificarea automată."); return }
        state = state.copy(busy = true)
        viewModelScope.launch { try { onReady(CamaraApi(config).weekPreview(request)) } catch (e: Exception) { failure(e) } finally { state = state.copy(busy = false) } }
    }
    fun acceptWeek(preview: WeekPreview, onSuccess: () -> Unit) = enqueue(onSuccess) {
        PendingAction(UUID.randomUUID().toString(), preview.action ?: error("Completează prețurile și recalculează planul."), rebasable = false)
    }

    fun saveProduct(product: Product, onSuccess: () -> Unit) = enqueue(onSuccess) { snapshot ->
        require(product.barcode.isBlank() || snapshot.products.none { it.id != product.id && it.barcode == product.barcode }) { "Codul este deja asociat altui produs." }
        ActionRules.action("Catalog: ${product.name}", listOf(ActionRules.change(snapshot, "products", product.id, ActionRules.record(product))))
    }
    fun deleteProduct(id: String) = enqueue { ActionRules.action("Elimină produs din catalog", listOf(ActionRules.change(it, "products", id, null))) }
    fun savePersonal(recipe: PersonalRecipe, onSuccess: () -> Unit) = enqueue(onSuccess) { ActionRules.action("Rețeta mea: ${recipe.title}", listOf(ActionRules.change(it, "personal_recipes", recipe.id, ActionRules.record(recipe)))) }
    fun saveImportedPersonal(recipe: PersonalRecipe, onSuccess: () -> Unit) {
        if (state.snapshot.personal_recipes.any { it.id == recipe.id }) {
            onSuccess()
            return
        }
        enqueue(onSuccess) {
            ActionRules.action("Rețetă importată: ${recipe.title}",
                listOf(Change("personal_recipes", recipe.id, after = ActionRules.record(recipe))), rebasable = false)
                .copy(id = recipe.id)
        }
    }
    fun deletePersonal(id: String) = enqueue { ActionRules.action("Elimină rețeta mea", listOf(ActionRules.change(it, "personal_recipes", id, null))) }
    fun importReceipt(preview: ReceiptPreview, merchant: String, date: String, total: Int, lines: List<ReceiptLine>, pantry: List<PantryItem>, onSuccess: () -> Unit) = enqueue(onSuccess) { snapshot ->
        ReceiptRules.importAction(snapshot, preview, merchant, date, total, lines, pantry)
    }
    fun scanReceipt(uri: android.net.Uri, onReady: (ReceiptPreview) -> Unit) {
        val config = state.config ?: return
        if (state.busy || state.generating) return
        if (state.pending.isNotEmpty()) { message("Sincronizează întâi modificările catalogului și ale inventarului."); return }
        state = state.copy(busy = true, receiptReading = true)
        viewModelScope.launch {
            try { val bytes = withContext(Dispatchers.IO) { ImageFiles.jpeg(getApplication(), uri) }; onReady(CamaraApi(config).scanReceipt(bytes)) }
            catch (e: Exception) { failure(e) }
            finally { state = state.copy(busy = false, receiptReading = false) }
        }
    }
    private fun backupAction(action: suspend (CamaraApi) -> Unit) {
        val config = state.config ?: return
        if (state.busy || state.generating) return
        state = state.copy(busy = true)
        viewModelScope.launch {
            try { val api = CamaraApi(config); action(api); state = state.copy(backupOverview = api.backups()) }
            catch (e: Exception) { failure(e) }
            finally { state = state.copy(busy = false) }
        }
    }
    fun loadBackups() = backupAction { }
    fun createBackup() = backupAction { it.createBackup(); message("Copia de siguranță a fost creată și verificată.") }
    fun configureBackups(value: BackupSettings) = backupAction { it.configureBackups(value) }
    fun previewRestore(id: String, onReady: (RestorePreview) -> Unit) = backupAction {
        require(state.pending.isEmpty()) { "Sincronizează sau rezolvă toate acțiunile înainte de restaurare." }
        onReady(it.previewRestore(id))
    }
    fun restore(preview: RestorePreview, requestId: String, onSuccess: () -> Unit) {
        val config = state.config ?: return
        if (state.busy || state.generating || state.syncing) return
        state = state.copy(busy = true)
        viewModelScope.launch {
            try {
                val request = RestoreRequest(requestId, preview.fingerprint, preview.backup.sha256)
                showLocal(withContext(Dispatchers.IO) { SyncEngine.restore(getApplication(), config, preview.backup.id, request) })
                state = state.copy(backupOverview = CamaraApi(config).backups())
                message("Datele au fost restaurate. Copia dinaintea restaurării este păstrată separat.")
                onSuccess()
            } catch (e: Exception) { failure(e) }
            finally { state = state.copy(busy = false) }
        }
    }
    fun discardRestoredChanges() {
        val config = state.config ?: return
        if (state.busy) return
        state = state.copy(busy = true)
        viewModelScope.launch { try { showLocal(withContext(Dispatchers.IO) { SyncEngine.discardStale(getApplication(), config) }) }
            catch (e: Exception) { failure(e) } finally { state = state.copy(busy = false); refresh(silent = true) } }
    }

    fun previewBulk(text: String, onReady: (BulkPreview) -> Unit) {
        val config = state.config ?: return
        if (state.busy || !state.online) return
        state = state.copy(busy = true)
        viewModelScope.launch {
            try { onReady(CamaraApi(config).preview(text)) }
            catch (e: Exception) { failure(e) }
            finally { state = state.copy(busy = false) }
        }
    }

    fun importBulk(id: String, items: List<PantryItem>, onSuccess: () -> Unit) = enqueue(onSuccess) {
        ActionRules.action("Importă ${items.size} alimente", items.map { Change("pantry", it.id, after = ActionRules.record(it)) },
            rebasable = false).copy(id = id)
    }

    fun setAiMode(mode: String) {
        if (mode == state.aiMode) return
        if (mode == "device" && !localAi.isReady()) return
        if (mode == "groq" && state.cloudProviders.keys.none(cloudAiLabels::containsKey)) return
        aiPreferences.mode = mode
        state = state.copy(aiMode = mode)
        getApplication<CamaraApplication>().serverAiPresence.settingsChanged()
        if (mode == "server" && state.online) state.config?.let { config ->
            viewModelScope.launch { refreshAi(config, force = true) }
        }
    }

    fun downloadLocalModel() {
        val config = state.config ?: return
        if (state.modelDownloadProgress != null || state.generating) return
        state = state.copy(modelDownloadProgress = 0)
        viewModelScope.launch {
            try {
                localAi.download(CamaraApi(config)) { percent -> viewModelScope.launch { state = state.copy(modelDownloadProgress = percent) } }
                state = state.copy(localModelReady = localAi.isReady(), message = "Modelul este verificat și pregătit pe dispozitiv.")
            } catch (e: Exception) { failure(e) }
            finally { state = state.copy(modelDownloadProgress = null) }
        }
    }

    fun saveReminderSettings(settings: ReminderSettings) {
        reminders.save(settings)
        ReminderScheduler.schedule(getApplication())
        state = state.copy(reminderSettings = reminders.read(), message = "Preferințele pentru notificări au fost salvate.")
    }

    fun suggestRecipes(request: RecipeRequest) {
        if (state.suggesting || request.equipment.isEmpty()) return
        val config = state.config ?: return
        state = state.copy(suggesting = true, quickSuggestions = null)
        viewModelScope.launch {
            try { loadSuggestions(config, request) }
            catch (e: Exception) { failure(e) }
            finally { state = state.copy(suggesting = false) }
        }
    }

    private suspend fun loadSuggestions(config: ConnectionConfig, request: RecipeRequest, aiNote: String? = null) {
        var note = aiNote
        val start = state.snapshot
        val catalog = if (state.online && state.pending.isEmpty()) {
            try {
                CamaraApi(config, fast = true).recipeSuggestions(request)
            } catch (e: Exception) {
                val reason = when {
                    e is IOException && e !is SSLException -> "Serverul nu a putut fi contactat."
                    e is ApiException && e.code == 404 -> "Catalogul rapid va fi disponibil după actualizarea serverului."
                    e is ApiException && (e.code == 429 || e.code in 500..599) -> "Catalogul nu este disponibil momentan."
                    else -> throw e
                }
                note = listOfNotNull(aiNote, reason).joinToString(" ")
                null
            }
        } else null
        if (state.config != config) return
        val current = state.snapshot
        if (catalog != null && (current.epoch != start.epoch || current.pantry != start.pantry ||
                current.personal_recipes != start.personal_recipes || state.pending.isNotEmpty())) return
        val recipes = catalog ?: withContext(Dispatchers.Default) { savedRecipeSuggestions(current, request) }
        if (catalog == null && needsServerIngredientCheck(request)) {
            note = listOfNotNull(note, "Mută cerințele despre ingrediente în «Filtre și echipamente» pentru sugestii offline.")
                .joinToString(" ")
        }
        if (state.config == config && state.snapshot == current) {
            state = state.copy(quickSuggestions = QuickSuggestions(request, recipes, catalog != null, note))
        }
    }

    fun generate(request: RecipeRequest) {
        val config = state.config ?: return
        if (!state.online || state.busy || state.generating || state.suggesting || state.syncing) return
        if (state.pending.isNotEmpty()) { state = state.copy(message = "Sincronizează întâi modificările inventarului."); return }
        val mode = state.aiMode
        val cloud = mode in cloudAiLabels
        state = state.copy(generating = true, quickSuggestions = null)
        viewModelScope.launch {
            try {
                val started = SystemClock.elapsedRealtime()
                val api = CamaraApi(config, epoch = state.snapshot.epoch)
                val recipe = try {
                    when {
                        mode == "device" -> {
                            val options = api.recipeOptions(request)
                            val selected = localAi.select(options)
                            api.choose(DeviceChoice(request, options.fingerprint, selected.recipeId, UUID.randomUUID().toString(), selected.milliseconds, selected.cached))
                        }
                        cloud -> api.generateCloud(mode, request)
                        else -> api.generate(request)
                    }
                } catch (e: Exception) {
                    val note = when {
                        cloud && e is ApiException && e.code == 404 -> "Actualizează API-ul de pe ZimaOS pentru motorul online."
                        cloud && e is ApiException && (e.code == 429 || e.code in 500..599) ->
                            e.message ?: "Motorul online nu este disponibil momentan."
                        e is ApiException && e.code == 429 -> "AI-ul este ocupat."
                        e is ApiException && e.code in 500..599 -> "AI-ul nu este disponibil momentan."
                        e is LocalAiUnavailable -> e.message ?: "Motorul local nu este disponibil momentan."
                        e is IOException && e !is SSLException -> "Răspunsul AI nu a putut fi confirmat."
                        else -> throw e
                    }
                    val ready = state.quickSuggestions
                    when {
                        state.suggesting -> state = state.copy(message = note)
                        ready?.request == request -> state = state.copy(quickSuggestions = ready.copy(note = listOfNotNull(note, ready.note).joinToString(" ")))
                        else -> {
                            state = state.copy(suggesting = true)
                            try { loadSuggestions(config, request, note) }
                            finally { state = state.copy(suggesting = false) }
                        }
                    }
                    return@launch
                }
                showLocal(withContext(Dispatchers.IO) { SyncEngine.sync(getApplication(), config) })
                state = state.copy(online = true)
                val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
                val result = when {
                    cloud && recipe.model.startsWith("OpenRouter · ") -> "Recomandare de rezervă prin OpenRouter"
                    recipe.selection_cached -> "Recomandare reutilizată pentru aceleași date"
                    else -> "Recomandare pregătită"
                }
                state = state.copy(message = "$result · ${"%.1f".format(seconds)} s")
            } catch (e: Exception) { failure(e) }
            finally { state = state.copy(generating = false) }
        }
    }
}
