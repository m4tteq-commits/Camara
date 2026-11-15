package ro.camara.app.ui

import android.Manifest
import android.os.Build
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ro.camara.app.notifications.ReminderScheduler

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.camara.app.CamaraViewModel
import ro.camara.app.UiState
import ro.camara.app.data.*
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import android.net.Uri
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

private val PreviewRecipeSaver = Saver<Recipe?, String>(
    save = { recipe -> recipe?.let { ActionRules.json.encodeToString(Recipe.serializer(), it) } },
    restore = { ActionRules.json.decodeFromString(Recipe.serializer(), it) },
)
private val PersonalRecipeSaver = Saver<PersonalRecipe?, String>(
    save = { recipe -> recipe?.let { ActionRules.json.encodeToString(PersonalRecipe.serializer(), it) } },
    restore = { ActionRules.json.decodeFromString(PersonalRecipe.serializer(), it) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamaraApp(model: CamaraViewModel, appearance: CamaraAppearance, sharedLink: String? = null, onShareHandled: () -> Unit = {},
    onAppearanceChange: (CamaraAppearance) -> Unit) {
    val state by remember(model) {
        derivedStateOf(structuralEqualityPolicy()) { model.state.copy(message = null, syncing = false, lastSync = null) }
    }
    val connection = remember(model) { derivedStateOf(structuralEqualityPolicy()) { model.state.connectionDisplay() } }
    val recipeLibrary = rememberRecipeLibrary(state.snapshot)
    var selected by rememberSaveable { mutableStateOf(Destination.Today) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var remindersDialog by rememberSaveable { mutableStateOf(false) }
    var bulkDialog by rememberSaveable { mutableStateOf(false) }
    var bulkBatchId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var batchScan by rememberSaveable { mutableStateOf(false) }
    var reviewPantryTerms by rememberSaveable { mutableStateOf(false) }
    var focusMealDate by rememberSaveable { mutableStateOf<String?>(null) }
    var pantryDialog by remember { mutableStateOf(false) }
    var pantryDraftId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var editing by remember { mutableStateOf<PantryItem?>(null) }
    var pantryName by remember { mutableStateOf("") }
    fun addPantry(name: String = "") {
        editing = null
        pantryName = name
        pantryDraftId = UUID.randomUUID().toString()
        pantryDialog = true
    }
    var deleting by remember { mutableStateOf<PantryItem?>(null) }
    var shoppingDialog by remember { mutableStateOf(false) }
    var shoppingDraftId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var selectedRecipe by rememberSaveable { mutableStateOf<String?>(null) }
    var previewRecipe by rememberSaveable(stateSaver = PreviewRecipeSaver) { mutableStateOf<Recipe?>(null) }
    var cookingGuide by rememberSaveable { mutableStateOf(false) }
    var recipeReturn by rememberSaveable { mutableStateOf(Destination.Recipes) }
    var navigationHistory by rememberSaveable { mutableStateOf(emptyList<Destination>()) }
    fun navigate(destination: Destination, clearHistory: Boolean = false) {
        if (destination != selected) navigationHistory = if (destination in primaryDestinations || clearHistory) emptyList() else (navigationHistory + selected).takeLast(12)
        selected = destination; selectedRecipe = null; cookingGuide = false
    }
    fun openRecipe(recipe: Recipe) {
        previewRecipe = null
        recipeReturn = selected
        selectedRecipe = recipe.id
        selected = Destination.Recipes
        cookingGuide = false
    }
    fun openRecipePreview(recipe: Recipe) {
        openRecipe(recipe)
        previewRecipe = recipe
    }
    fun goBack() {
        if (cookingGuide) cookingGuide = false
        else if (selectedRecipe != null) { selectedRecipe = null; selected = recipeReturn }
        else if (navigationHistory.isNotEmpty()) { selected = navigationHistory.last(); navigationHistory = navigationHistory.dropLast(1) }
        else selected = selected.backFromRoot
    }
    BackHandler(enabled = selected != Destination.Today || selectedRecipe != null) { goBack() }
    var syncDialog by remember { mutableStateOf(false) }
    var cookingRecipe by remember { mutableStateOf<Recipe?>(null) }
    var cookingServings by remember { mutableIntStateOf(1) }
    var cookingPlan by remember { mutableStateOf<String?>(null) }
    var cookingKeepBatch by remember { mutableStateOf(false) }
    var initialPlanBatch by remember { mutableStateOf<PreparedBatch?>(null) }
    var servePlan by remember { mutableStateOf<MealPlan?>(null) }
    var wasteItem by remember { mutableStateOf<PantryItem?>(null) }
    var quickUseItem by remember { mutableStateOf<PantryItem?>(null) }
    var planDialog by remember { mutableStateOf(false) }
    var planDate by remember { mutableStateOf(LocalDate.now()) }
    var planRecipe by remember { mutableStateOf<Recipe?>(null) }
    var editingPlan by remember { mutableStateOf<MealPlan?>(null) }
    var deletingPlan by remember { mutableStateOf<MealPlan?>(null) }
    var shoppingPreview by remember { mutableStateOf<ShoppingPreview?>(null) }
    var expenseDialog by remember { mutableStateOf(false) }
    var editingExpense by remember { mutableStateOf<Expense?>(null) }
    var deletingExpense by remember { mutableStateOf<Expense?>(null) }
    var budgetMonth by remember { mutableStateOf<String?>(null) }
    var undoEvent by remember { mutableStateOf<FoodEvent?>(null) }
    var productDialog by remember { mutableStateOf(false) }
    var editingProduct by remember { mutableStateOf<Product?>(null) }
    var productCode by remember { mutableStateOf("") }
    var addAfterProduct by remember { mutableStateOf(false) }
    var stockProduct by remember { mutableStateOf<Product?>(null) }
    var deletingProduct by remember { mutableStateOf<Product?>(null) }
    var personalDialog by rememberSaveable { mutableStateOf(false) }
    var personalNotesOnly by rememberSaveable { mutableStateOf(false) }
    var importRecipe by rememberSaveable { mutableStateOf(false) }
    var importSources by rememberSaveable { mutableStateOf(false) }
    var sourceLink by rememberSaveable { mutableStateOf("") }
    var initialRecipeText by rememberSaveable { mutableStateOf("") }
    var variantJson by rememberSaveable { mutableStateOf<String?>(null) }
    var variantId by rememberSaveable { mutableStateOf("") }
    var recipePhotoReading by remember { mutableStateOf(false) }
    var recipePhotoError by remember { mutableStateOf<String?>(null) }
    var importPdf by rememberSaveable { mutableStateOf(false) }
    var editingPersonal by rememberSaveable(stateSaver = PersonalRecipeSaver) { mutableStateOf<PersonalRecipe?>(null) }
    var deletingPersonal by remember { mutableStateOf<PersonalRecipe?>(null) }
    var receiptPreview by remember { mutableStateOf<ReceiptPreview?>(null) }
    var receiptDetails by remember { mutableStateOf<ReceiptRecord?>(null) }
    var restorePreview by remember { mutableStateOf<RestorePreview?>(null) }
    var photoUri by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraAction by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val canWrite = !state.busy && !state.generating
    LaunchedEffect(selected) { if (selected == Destination.InStore && model.state.cart == null) model.startCart() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun showImportedText(text: String) {
        model.clearMessage()
        importSources = false
        initialRecipeText = text
        importRecipe = true
    }
    LaunchedEffect(sharedLink, state.config) {
        if (sharedLink != null && state.config != null) {
            navigate(Destination.Recipes)
            sourceLink = sharedLink
            recipePhotoError = null
            importSources = true
            onShareHandled()
        }
    }
    fun readRecipePhoto(uri: Uri) {
        val config = state.config ?: return
        if (recipePhotoReading) return
        recipePhotoReading = true
        recipePhotoError = null
        scope.launch {
            try {
                val photo = withContext(Dispatchers.IO) { ImageFiles.jpeg(context, uri) }
                showImportedText(CamaraApi(config).recipePhotoText(photo))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recipePhotoError = e.message ?: "Fotografia nu a putut fi citită."
            } finally {
                recipePhotoReading = false
            }
        }
    }
    val barcodeLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { code ->
            if (!productBarcode.matches(code)) model.message("Codul nu este un EAN/UPC acceptat. Îl poți introduce manual în catalog.")
            else {
                val known = state.snapshot.products.firstOrNull { it.barcode == code }
                if (known != null) stockProduct = known
                else { editingProduct = null; productCode = code; addAfterProduct = true; productDialog = true }
            }
        }
    }
    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let {
            val uri = Uri.parse(it)
            if (cameraAction == "recipe") readRecipePhoto(uri)
            else model.scanReceipt(uri) { preview -> receiptPreview = preview }
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { model.scanReceipt(it) { preview -> receiptPreview = preview } } }
    val recipeGallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(::readRecipePhoto) }
    fun launchCamera(action: String) {
        runCatching {
            when (action) {
                "barcode" -> barcodeLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.PRODUCT_CODE_TYPES)
                    .setPrompt("Încadrează codul de bare al produsului").setBeepEnabled(false).setOrientationLocked(false))
                "batch" -> { model.clearMessage(); batchScan = true }
                "receipt", "recipe" -> { val uri = ImageFiles.captureUri(context); photoUri = uri.toString(); captureLauncher.launch(uri) }
            }
        }.onFailure { model.message("Camera nu poate fi deschisă. Poți introduce codul manual sau alege o fotografie.") }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera(cameraAction) else model.message("Permite camera din setările Android pentru scanare. Introducerea manuală și alegerea fotografiilor rămân disponibile.")
    }
    fun requestCamera(action: String) {
        cameraAction = action
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera(action)
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(selected) { if (selected == Destination.Backups) model.loadBackups() }
    var notificationAllowed by remember { mutableStateOf(ReminderScheduler.allowed(context)) }
    var pendingTest by remember { mutableStateOf(false) }
    fun testNotification() { ReminderScheduler.post(context, "Cămara · notificare de test", "Notificările Cămara funcționează. Ora rezumatului se schimbă din clopoțel.", test = true) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationAllowed = ReminderScheduler.allowed(context)
        if (granted && pendingTest) testNotification()
        pendingTest = false
    }
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notificationAllowed = ReminderScheduler.allowed(context) }

    ShowAppMessage(model, snackbar)

    if (appearanceOpen) AppearanceSheet(appearance, onAppearanceChange) { appearanceOpen = false }

    if (state.initializing) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (state.config == null) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            TopAppBar(title = { Text("Cămara") }, actions = {
                IconButton(onClick = { appearanceOpen = true }) { Icon(Icons.Outlined.Palette, "Teme și aspect") }
            })
        }) { padding ->
            SetupScreen(state, Modifier.padding(padding), model::connect)
        }
        return
    }

    AppShell(selected, selectedRecipe, selected == Destination.Recipes && recipeReturn == Destination.Recipes,
        cookingGuide, state.busy, canWrite, connection, snackbar,
        navigate = { navigate(it) }, goBack = ::goBack, onSync = { syncDialog = true }, onRefresh = { model.refresh() },
        onReminders = { remindersDialog = true }, onAppearance = { appearanceOpen = true }, onSettings = { settings = true },
        onAdd = {
            if (selected == Destination.Pantry) addPantry()
            else { shoppingDraftId = UUID.randomUUID().toString(); shoppingDialog = true }
        }
    ) { page, wide, recipeInPane ->
        val body = Modifier.fillMaxSize()
        when (page.destination) {
            Destination.Pantry -> PantryScreen(state.snapshot.pantry,
                onEdit = { if (canWrite) { editing = it; pantryDialog = true } },
                onDelete = { deleting = it }, onBulk = { model.clearMessage(); bulkBatchId = UUID.randomUUID().toString(); bulkDialog = true }, canWrite = canWrite, bulkAvailable = state.online, onWaste = { wasteItem = it },
                modifier = body, onOpenMinimums = { navigate(Destination.Minimums) }, onUse = { quickUseItem = it },
                stockTools = state.snapshot.stock_tools, onScanBatch = { requestCamera("batch") },
                reviewTerms = reviewPantryTerms, onTermsOpened = { reviewPantryTerms = false }, wide = wide,
                onQuickAdd = ::addPantry)
            Destination.Recipes -> {
                val recipeId = page.recipeId ?: selectedRecipe.takeIf { recipeInPane }
                val recipe = previewRecipe?.takeIf { it.id == recipeId }
                    ?: recipeLibrary.firstOrNull { it.id == recipeId }
                val details: @Composable (Recipe, Modifier) -> Unit = { value, pane ->
                    key(value.id) {
                        val personal = state.snapshot.personal_recipes.firstOrNull { it.id == value.id }
                        val cloudApi = state.config?.takeIf { state.online &&
                            state.cloudProviders.isNotEmpty() }?.let { CamaraApi(it) }
                        val personalApi = cloudApi?.takeIf { state.pending.isEmpty() && personal != null }
                        RecipeDetails(value, canWrite, { cookingGuide = true }, { model.addMissing(value); navigate(Destination.Shopping) }, pane,
                            onCook = { cookingPlan = null; cookingKeepBatch = false; cookingRecipe = value; cookingServings = value.servings },
                            onPlan = { initialPlanBatch = null; editingPlan = null; planRecipe = value; planDate = LocalDate.now(); planDialog = true },
                            onAdvice = personalApi?.let { api -> { goal -> api.recipeAdvice(value.id, goal) } },
                            onConversation = if (personal != null) personalApi?.let { api -> { input -> api.recipeConversation(value.id, input) } }
                                else cloudApi?.let { api -> { input -> api.catalogConversation(value, input) } },
                            onVariant = { draft ->
                                variantId = UUID.randomUUID().toString()
                                variantJson = ActionRules.json.encodeToString(AiImportedRecipe.serializer(), draft)
                            },
                            onNotes = personal?.let { item -> {
                                editingPersonal = item
                                personalNotesOnly = true
                                personalDialog = true
                            } })
                    }
                }
                if (page.recipeId == null) {
                    ListDetailLayout(body, wide, list = { pane ->
                        RecipesScreen(state.copy(syncing = connection.value.syncing), canWrite, model::generate, ::openRecipe, pane, model::setAiMode, model::downloadLocalModel,
                            onPersonal = { editingPersonal = it; personalNotesOnly = false; personalDialog = true },
                            onDeletePersonal = { deletingPersonal = it },
                            onSuggest = model::suggestRecipes, onPreview = ::openRecipePreview,
                            onImport = { showImportedText("") }, selectedRecipeId = recipeId, onTabChange = { selectedRecipe = null },
                            onPdfImport = if (state.online && state.cloudProviders.isNotEmpty()) {
                                { model.clearMessage(); importPdf = true }
                            } else null,
                            onSources = { sourceLink = ""; recipePhotoError = null; importSources = true },
                            onFavorite = { model.savePersonal(it.copy(favorite = !it.favorite)) {} })
                    }, detail = { pane ->
                        if (recipe != null) details(recipe, pane)
                        else DetailPlaceholder(pane, Icons.Outlined.RestaurantMenu, "Detalii rețetă", "Alege o rețetă din listă.")
                    })
                } else if (recipe == null) {
                    DetailPlaceholder(body, Icons.Outlined.RestaurantMenu, "Rețetă indisponibilă", "Revino la lista de rețete.")
                } else if (cookingGuide && page.recipeId == selectedRecipe) CookingMode(recipe, body) { cookingGuide = false }
                else details(recipe, body)
            }
            Destination.Shopping -> ShoppingScreen(state.snapshot, canWrite,
                onCheck = { model.saveShopping(it.copy(checked = !it.checked)) },
                onDelete = { model.deleteShopping(it.id) }, modifier = body, onStore = { navigate(Destination.InStore) },
                onRepeat = model::repeatPurchase, onPrices = { navigate(Destination.Prices) }, onAisleChange = model::saveShopping)
            Destination.Plan -> PlanScreen(state.snapshot, canWrite,
                onAdd = { initialPlanBatch = null; planDate = it; planRecipe = null; editingPlan = null; planDialog = true },
                onEdit = { initialPlanBatch = null; editingPlan = it; planRecipe = it.recipe; planDate = LocalDate.parse(it.on_date); planDialog = true },
                onDelete = { deletingPlan = it }, onCook = { if (it.prepared_batch_id != null) servePlan = it else { cookingKeepBatch = false; cookingPlan = it.id; cookingRecipe = it.recipe; cookingServings = it.servings } },
                onShopping = { shoppingPreview = RecipeMath.shopping(state.snapshot, it) }, modifier = body, onAutomatic = { navigate(Destination.Automatic) }, onPrepared = { navigate(Destination.Prepared) }, onRecipe = ::openRecipe,
                focusDate = focusMealDate, onDateOpened = { focusMealDate = null })
            Destination.Budget -> BudgetScreen(state.snapshot, canWrite,
                onExpense = { editingExpense = it; expenseDialog = true }, onDelete = { deletingExpense = it },
                onBudget = { budgetMonth = it }, onUndo = { undoEvent = it }, modifier = body)
            Destination.Today -> TodayScreen(state, { navigate(it) }, { if (canWrite) requestCamera("batch") }, { navigate(Destination.Receipts) }, body,
                onAdd = { addPantry() },
                onReviewTerms = { reviewPantryTerms = true; navigate(Destination.Pantry) },
                onOpenMeal = { date -> focusMealDate = date; navigate(Destination.Plan) })
            Destination.More -> MoreScreen({ navigate(it) }, body, appearance,
                onAppearance = { appearanceOpen = true }, onReminders = { remindersDialog = true },
                onServer = { settings = true }, serverEnabled = canWrite && !connection.value.syncing, storeEnabled = canWrite)
            Destination.Products -> ProductsScreen(state.snapshot.products, canWrite,
                onAdd = { editingProduct = null; productCode = ""; addAfterProduct = false; productDialog = true }, onScan = { requestCamera("barcode") },
                onEdit = { editingProduct = it; productCode = it.barcode; addAfterProduct = false; productDialog = true }, onStock = { stockProduct = it }, onDelete = { deletingProduct = it }, modifier = body)
            Destination.Receipts -> ReceiptsScreen(state, { requestCamera("receipt") }, { galleryLauncher.launch("image/*") }, { receiptDetails = it }, body)
            Destination.Backups -> BackupsScreen(state, model::loadBackups, model::createBackup, model::configureBackups, { backup -> model.previewRestore(backup.id) { restorePreview = it } }, body)
            Destination.InStore -> InStoreScreen(state.cart, state.snapshot, canWrite, model::updateCart, { model.checkout { navigate(Destination.Shopping) } }, { model.discardCart { navigate(Destination.Shopping) } }, body)
            Destination.Prepared -> PreparedScreen(state.snapshot, canWrite,
                onCook = { cookingKeepBatch = true; cookingPlan = null; cookingRecipe = it; cookingServings = it.servings },
                onPlan = { initialPlanBatch = it; editingPlan = null; planRecipe = it.recipe; planDate = LocalDate.now(); planDialog = true },
                onServe = { batch, portions, wasted -> model.serveBatch(batch, portions, wasted, null) {} }, modifier = body, onOpenPlan = { navigate(Destination.Plan) })
            Destination.Automatic -> AutoPlanScreen(state.snapshot, canWrite, state.online, state.busy, model::previewWeek, { model.acceptWeek(it) { navigate(Destination.Plan, clearHistory = true) } }, body)
            Destination.Minimums -> StockMinimumScreen(state.snapshot, canWrite && state.snapshot.stock_tools,
                model::saveStockMinimum, model::deleteStockMinimum, model::replenish, body)
            Destination.UseSoon -> UseSoonScreen(state, model::useSoonIdeas, onRecipe = ::openRecipePreview, modifier = body)
            Destination.Prices -> PriceHistoryScreen(state.snapshot, body)
            Destination.History -> HistoryScreen(state.snapshot, state.config, state.online, body)
        }
    }

    if (settings) AlertDialog(
        onDismissRequest = { if (!state.busy) settings = false },
        title = { Text("Serverul tău") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                ConnectionForm(state.config, state.busy, connection.value.lastSync, model::connect,
                    allowSave = !connection.value.syncing && !state.generating)
            }
        },
        confirmButton = { TextButton(onClick = { settings = false }, enabled = !state.busy) { Text("Închide") } },
    )
    if (pantryDialog) PantryDialog(editing, enabled = canWrite, initialName = pantryName,
        onDismiss = { pantryDialog = false },
        onSave = { model.savePantry(editing?.id ?: pantryDraftId, it) { pantryDialog = false } })
    deleting?.let { item ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Elimini ${item.name}?") },
            text = { Text("Alimentul va fi eliminat și de pe celălalt dispozitiv la actualizare.") },
            confirmButton = { TextButton(onClick = { model.deletePantry(item.id); deleting = null }, enabled = canWrite) { Text("Elimină") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Păstrează") } })
    }
    if (shoppingDialog) ShoppingDialog(enabled = canWrite, onDismiss = { shoppingDialog = false }, onSave = { name, quantity ->
        model.addShopping(shoppingDraftId, name, quantity) { shoppingDialog = false }
    })
    quickUseItem?.let { item ->
        QuickUseDialog(item, canWrite && state.snapshot.stock_tools, { quickUseItem = null }) { amount -> model.quickUse(item, amount) { quickUseItem = null } }
    }
    if (bulkDialog) BulkAddDialog(state.busy, model.state.message, onDismiss = { bulkDialog = false }, onPreview = model::previewBulk,
        onSave = { items -> model.importBulk(bulkBatchId, items) { bulkDialog = false } })
    if (batchScan) BatchScanDialog(state.snapshot, canWrite, model.state.message, onDismiss = { batchScan = false },
        onSave = { epoch, rows -> model.importScans(epoch, rows) { batchScan = false } })
    if (remindersDialog) ReminderDialog(state.reminderSettings, notificationAllowed, { remindersDialog = false }, model::saveReminderSettings,
        onPermission = ::requestNotificationPermission, onTest = { if (notificationAllowed) testNotification() else { pendingTest = true; requestNotificationPermission() } })
    if (syncDialog) SyncDialog(state.pending, state.serverSnapshot, canWrite && !connection.value.syncing, { syncDialog = false }, { model.refresh() },
        model::resolveConflict, model::discardRestoredChanges, status = syncLabel(connection.value))
    cookingRecipe?.let { recipe -> CookingDialog(state.snapshot, recipe, cookingServings, canWrite, { cookingRecipe = null }, cookingKeepBatch) { uses, portions, details ->
        if (details == null) model.consume(recipe.title, uses, false, "", null, cookingPlan) { cookingRecipe = null }
        else model.cookBatch(recipe, portions, uses, details, cookingPlan) { cookingRecipe = null }
    } }
    wasteItem?.let { item -> WasteDialog(item, canWrite, { wasteItem = null }) { amount, reason, cents ->
        model.consume(item.name, listOf(StockUse(item, amount)), true, reason, cents, null) { wasteItem = null }
    } }
    if (planDialog) PlanDialog(recipeLibrary, planDate, planRecipe, editingPlan, canWrite, { planDialog = false }, state.snapshot.prepared, initialPlanBatch) { model.savePlan(it) { planDialog = false } }
    servePlan?.let { plan -> ConfirmAction("Confirmi porțiile mâncate?", "${plan.recipe.title} · ${plan.servings} porții. Ingredientele au fost deja scăzute la gătire.", canWrite, { servePlan = null }) {
        val batch = state.snapshot.prepared.firstOrNull { it.id == plan.prepared_batch_id }
        if (batch == null) model.message("Lotul a fost schimbat. Actualizează datele.") else model.serveBatch(batch, plan.servings, false, plan.id) { servePlan = null }
    } }
    shoppingPreview?.let { preview -> ShoppingPreviewDialog(preview, canWrite, { shoppingPreview = null }) { model.addPlannedShopping(preview) { shoppingPreview = null } } }
    if (expenseDialog) ExpenseDialog(editingExpense, canWrite, { expenseDialog = false }) { model.saveExpense(it) { expenseDialog = false } }
    budgetMonth?.let { month -> BudgetDialog(month, state.snapshot.budgets.firstOrNull { it.month == month }?.limit_cents ?: 0, canWrite, { budgetMonth = null }) { model.saveBudget(month, it) { budgetMonth = null } } }
    deletingPlan?.let { plan -> ConfirmAction("Elimini masa din plan?", plan.recipe.title, canWrite, { deletingPlan = null }) { model.deletePlan(plan.id); deletingPlan = null } }
    deletingExpense?.let { expense -> ConfirmAction("Elimini cheltuiala?", "${expense.label} · ${money(expense.amount_cents.toLong())}", canWrite, { deletingExpense = null }) { model.deleteExpense(expense.id); deletingExpense = null } }
    undoEvent?.let { event -> ConfirmAction("Restabilești cantitățile?", "Înregistrarea ${event.title} va fi marcată ca anulată. Cantitățile consumate sau aruncate revin în inventar.", canWrite, { undoEvent = null }) { model.undoEvent(event.id); undoEvent = null } }
    if (productDialog) ProductDialog(editingProduct, productCode, canWrite, { productDialog = false }) { product ->
        model.saveProduct(product) { productDialog = false; if (addAfterProduct) stockProduct = product }
    }
    stockProduct?.let { product -> ProductStockDialog(product, canWrite, { stockProduct = null }) { id, input -> model.savePantry(id, input) { stockProduct = null } } }
    deletingProduct?.let { product -> ConfirmAction("Elimini produsul din catalog?", "Alimentele deja adăugate în cămară rămân păstrate.", canWrite, { deletingProduct = null }) { model.deleteProduct(product.id); deletingProduct = null } }
    if (personalDialog) PersonalRecipeDialog(editingPersonal, canWrite, state.snapshot.aliases, { personalDialog = false },
        notesOnly = personalNotesOnly) {
        model.savePersonal(it) { personalDialog = false }
    }
    if (importSources) RecipeSourceDialog(sourceLink, state.online, recipePhotoReading, recipePhotoError,
        onCamera = { requestCamera("recipe") }, onGallery = { recipeGallery.launch("image/*") },
        onText = ::showImportedText, onDismiss = { importSources = false })
    variantJson?.let { serialized ->
        val draft = remember(serialized) { ActionRules.json.decodeFromString(AiImportedRecipe.serializer(), serialized).draft() }
        PersonalRecipeDialog(null, canWrite, state.snapshot.aliases, { variantJson = null }, importDraft = draft,
            importId = variantId, saveError = model.state.message, reviewTitle = "Verifică varianta propusă") {
            model.saveImportedPersonal(it) { variantJson = null }
        }
    }
    if (importRecipe) {
        val aiImport = state.config?.takeIf { state.online && state.cloudProviders.isNotEmpty() }?.let { config ->
            val api = CamaraApi(config)
            api::importRecipeText
        }
        RecipeImportDialog(canWrite, state.snapshot.aliases, { importRecipe = false }, model.state.message, aiImport, initialRecipeText) {
            model.saveImportedPersonal(it) { importRecipe = false }
        }
    }
    if (importPdf) state.config?.let { config ->
        RecipePdfImportDialog(config, canWrite, state.snapshot.aliases, model.state.message, onDismiss = { importPdf = false },
            onSave = model::saveImportedPersonal)
    }
    deletingPersonal?.let { recipe -> ConfirmAction("Elimini rețeta personală?", "Mesele care o au deja în plan rămân păstrate.", canWrite, { deletingPersonal = null }) { model.deletePersonal(recipe.id); deletingPersonal = null } }
    receiptPreview?.let { preview -> ReceiptPreviewDialog(preview, canWrite, { receiptPreview = null }) { merchant, date, total, lines, pantry ->
        model.importReceipt(preview, merchant, date, total, lines, pantry) { receiptPreview = null }
    } }
    receiptDetails?.let { receipt -> ReceiptDetailsDialog(receipt) { receiptDetails = null } }
    restorePreview?.let { preview -> RestoreDialog(preview, canWrite, { restorePreview = null }) { requestId -> model.restore(preview, requestId) { restorePreview = null } } }
}

@Composable
private fun ShowAppMessage(model: CamaraViewModel, snackbar: SnackbarHostState) {
    val message by remember(model) { derivedStateOf(structuralEqualityPolicy()) { model.state.message } }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); model.clearMessage() }
    }
}

@Composable
private fun ConfirmAction(title: String, text: String, enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text("Confirmă") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } })
}

