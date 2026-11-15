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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

private enum class Destination(val label: String, val icon: ImageVector) {
    Pantry("Cămara", Icons.Outlined.Kitchen),
    Recipes("Gătește", Icons.Outlined.RestaurantMenu),
    Shopping("Cumpărături", Icons.Outlined.ShoppingBasket),
    Plan("Plan", Icons.Outlined.CalendarMonth),
    Budget("Buget", Icons.Outlined.AccountBalanceWallet),
    Today("Astăzi", Icons.Outlined.Today),
    More("Mai multe", Icons.Outlined.MoreHoriz),
    Products("Catalog", Icons.Outlined.Inventory2),
    Receipts("Bonuri", Icons.Outlined.ReceiptLong),
    Backups("Backup", Icons.Outlined.Backup),
    InStore("În magazin", Icons.Outlined.ShoppingCart),
    Prepared("Porții gătite", Icons.Outlined.TakeoutDining),
    Automatic("Plan automat", Icons.Outlined.AutoAwesome),
    Minimums("Reaprovizionare", Icons.Outlined.Inventory2),
    UseSoon("Rețete din cămară", Icons.Outlined.RestaurantMenu),
    Prices("Prețuri", Icons.Outlined.ShowChart),
}
private val primaryDestinations = listOf(5, 0, 1, 2, 6)
private fun navLabel(index: Int) = when (index) { 0 -> "Cămară"; 2 -> "Listă"; 6 -> "Mai mult"; else -> Destination.entries[index].label }
private fun navSelected(current: Int, item: Int) = current == item || (item == 2 && current in listOf(10, 15)) ||
    (item == 0 && current == 13) || (item == 1 && current == 14) || (item == 6 && current in listOf(3, 4, 7, 8, 9, 11, 12))

private data class AppPage(val destination: Int, val recipeId: String?) {
    val saveKey: String get() = if (recipeId == null) "screen:$destination" else "recipe:$recipeId"
}

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
fun CamaraApp(model: CamaraViewModel, appearance: CamaraAppearance, onAppearanceChange: (CamaraAppearance) -> Unit) {
    val state = model.state
    var selected by rememberSaveable { mutableIntStateOf(5) }
    val screenStates = rememberSaveableStateHolder()
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
    var recipeReturn by rememberSaveable { mutableIntStateOf(1) }
    var navigationHistory by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var appMenu by remember { mutableStateOf(false) }
    fun navigate(index: Int, clearHistory: Boolean = false) {
        if (index != selected) navigationHistory = if (index in primaryDestinations || clearHistory) emptyList() else (navigationHistory + selected).takeLast(12)
        selected = index; selectedRecipe = null; cookingGuide = false
    }
    fun openRecipe(recipe: Recipe) {
        previewRecipe = null
        recipeReturn = selected
        selectedRecipe = recipe.id
        selected = 1
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
        else selected = when (selected) { 10, 15 -> 2; 12 -> 3; 13 -> 0; 3, 4, 7, 8, 9, 11 -> 6; else -> 5 }
    }
    BackHandler(enabled = selected != 5 || selectedRecipe != null) { goBack() }
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
    var importRecipe by rememberSaveable { mutableStateOf(false) }
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
    LaunchedEffect(selected) { if (selected == 10 && model.state.cart == null) model.startCart() }
    val context = LocalContext.current
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
        if (ok) photoUri?.let { model.scanReceipt(Uri.parse(it)) { preview -> receiptPreview = preview } }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { model.scanReceipt(it) { preview -> receiptPreview = preview } } }
    fun launchCamera(action: String) {
        runCatching {
            when (action) {
                "barcode" -> barcodeLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.PRODUCT_CODE_TYPES)
                    .setPrompt("Încadrează codul de bare al produsului").setBeepEnabled(false).setOrientationLocked(false))
                "batch" -> { model.clearMessage(); batchScan = true }
                "receipt" -> { val uri = ImageFiles.captureUri(context); photoUri = uri.toString(); captureLauncher.launch(uri) }
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
    LaunchedEffect(selected) { if (selected == 9) model.loadBackups() }
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

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); model.clearMessage() }
    }

    if (appearanceOpen) AppearanceSheet(appearance, onAppearanceChange) { appearanceOpen = false }

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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val shortWindow = maxHeight < 520.dp
        val recipeInPane = wide && selected == 1 && recipeReturn == 1 && !cookingGuide
        val motion = LocalCamaraMotion.current
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (selected in primaryDestinations && selectedRecipe == null) {
                                Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Outlined.Eco, null, Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                            }
                            val title = if (cookingGuide) "Mod de gătit" else if (selectedRecipe != null && !recipeInPane) "Rețetă"
                                else if (selected in primaryDestinations) "Cămara" else Destination.entries[selected].label
                            Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ConnectionPill(state) { syncDialog = true }
                        }
                    },
                    navigationIcon = { if (selected !in primaryDestinations || selectedRecipe != null) IconButton(onClick = ::goBack) { Icon(Icons.Outlined.ArrowBack, "Înapoi") } },
                    actions = {
                        IconButton(onClick = { remindersDialog = true }) { Icon(Icons.Outlined.NotificationsNone, "Notificări pentru termene") }
                        Box {
                            IconButton(onClick = { appMenu = true }) { Icon(Icons.Outlined.MoreVert, "Opțiunile aplicației") }
                            DropdownMenu(appMenu, { appMenu = false }) {
                                DropdownMenuItem(text = { Text("Actualizează datele") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, enabled = !state.busy && !state.generating, onClick = { appMenu = false; model.refresh() })
                                DropdownMenuItem(text = { Text("Teme și aspect") }, leadingIcon = { Icon(Icons.Outlined.Palette, null) },
                                    onClick = { appMenu = false; appearanceOpen = true })
                                DropdownMenuItem(text = { Text("Serverul meu") }, leadingIcon = { Icon(Icons.Outlined.Dns, null) }, enabled = !state.busy && !state.generating && !state.syncing, onClick = { appMenu = false; settings = true })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = {
                if (!wide) Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp) {
                        primaryDestinations.forEach { index ->
                            val item = Destination.entries[index]
                            NavigationBarItem(selected = navSelected(selected, index), onClick = { navigate(index) },
                                icon = { Icon(item.icon, null, Modifier.size(24.dp)) }, label = { Text(navLabel(index), style = MaterialTheme.typography.labelSmall, maxLines = 1) })
                        }
                    }
                }
            },
            floatingActionButton = {
                if (selected in listOf(0, 2) && canWrite) ExtendedFloatingActionButton(
                    onClick = {
                        if (selected == 0) addPantry()
                        else { shoppingDraftId = UUID.randomUUID().toString(); shoppingDialog = true }
                    },
                    icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(if (selected == 0) "Adaugă aliment" else "Adaugă produs") },
                )
            },
        ) { padding ->
            Row(Modifier.padding(padding).fillMaxSize()) {
                if (wide) NavigationRail(
                    Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(if (shortWindow) 4.dp else 16.dp),
                    ) {
                        if (!shortWindow) Spacer(Modifier.height(16.dp))
                        primaryDestinations.forEach { index ->
                            val item = Destination.entries[index]
                            NavigationRailItem(selected = navSelected(selected, index), onClick = { navigate(index) },
                                icon = { Icon(item.icon, null) }, label = { Text(navLabel(index)) })
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    AnimatedContent(
                        targetState = AppPage(selected, if (recipeInPane) null else selectedRecipe),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        transitionSpec = {
                            if (motion) {
                                (fadeIn(tween(180, delayMillis = 30)) + slideInVertically(tween(210)) { it / 40 }) togetherWith fadeOut(tween(100))
                            } else EnterTransition.None togetherWith ExitTransition.None
                        },
                        label = "Navigare Camara",
                    ) { page ->
                        screenStates.SaveableStateProvider(page.saveKey) {
                            val body = Modifier.fillMaxSize()
                            when (page.destination) {
                                0 -> PantryScreen(state.snapshot.pantry,
                                    onEdit = { if (canWrite) { editing = it; pantryDialog = true } },
                                    onDelete = { deleting = it }, onBulk = { model.clearMessage(); bulkBatchId = UUID.randomUUID().toString(); bulkDialog = true }, canWrite = canWrite, bulkAvailable = state.online, onWaste = { wasteItem = it },
                                    modifier = body, onOpenMinimums = { navigate(13) }, onUse = { quickUseItem = it },
                                    stockTools = state.snapshot.stock_tools, onScanBatch = { requestCamera("batch") },
                                    reviewTerms = reviewPantryTerms, onTermsOpened = { reviewPantryTerms = false }, wide = wide,
                                    onQuickAdd = ::addPantry)
                                1 -> {
                                    val recipeId = page.recipeId ?: selectedRecipe.takeIf { recipeInPane }
                                    val recipe = previewRecipe?.takeIf { it.id == recipeId }
                                        ?: state.snapshot.allRecipes().firstOrNull { it.id == recipeId }
                                    val details: @Composable (Recipe, Modifier) -> Unit = { value, pane ->
                                        key(value.id) {
                                            RecipeDetails(value, canWrite, { cookingGuide = true }, { model.addMissing(value); navigate(2) }, pane,
                                                onCook = { cookingPlan = null; cookingKeepBatch = false; cookingRecipe = value; cookingServings = value.servings },
                                                onPlan = { initialPlanBatch = null; editingPlan = null; planRecipe = value; planDate = LocalDate.now(); planDialog = true },
                                                onAdvice = state.config?.takeIf { state.online && state.pending.isEmpty() &&
                                                    state.cloudProviders.isNotEmpty() && state.snapshot.personal_recipes.any { recipe -> recipe.id == value.id }
                                                }?.let { config -> { goal -> CamaraApi(config).recipeAdvice(value.id, goal) } })
                                        }
                                    }
                                    if (page.recipeId == null) {
                                        ListDetailLayout(body, wide, list = { pane ->
                                            RecipesScreen(state, canWrite, model::generate, ::openRecipe, pane, model::setAiMode, model::downloadLocalModel,
                                                onPersonal = { editingPersonal = it; personalDialog = true }, onDeletePersonal = { deletingPersonal = it },
                                                onSuggest = model::suggestRecipes, onPreview = ::openRecipePreview,
                                                onImport = { model.clearMessage(); importRecipe = true }, selectedRecipeId = recipeId, onTabChange = { selectedRecipe = null },
                                                onPdfImport = if (state.online && state.cloudProviders.isNotEmpty()) {
                                                    { model.clearMessage(); importPdf = true }
                                                } else null)
                                        }, detail = { pane ->
                                            if (recipe != null) details(recipe, pane)
                                            else DetailPlaceholder(pane, Icons.Outlined.RestaurantMenu, "Detalii rețetă", "Alege o rețetă din listă.")
                                        })
                                    } else if (recipe == null) {
                                        DetailPlaceholder(body, Icons.Outlined.RestaurantMenu, "Rețetă indisponibilă", "Revino la lista de rețete.")
                                    } else if (cookingGuide && page.recipeId == selectedRecipe) CookingMode(recipe, body) { cookingGuide = false }
                                    else details(recipe, body)
                                }
                                2 -> ShoppingScreen(state.snapshot, canWrite,
                                    onCheck = { model.saveShopping(it.copy(checked = !it.checked)) },
                                    onDelete = { model.deleteShopping(it.id) }, modifier = body, onStore = { navigate(10) },
                                    onRepeat = model::repeatPurchase, onPrices = { navigate(15) })
                                3 -> PlanScreen(state.snapshot, canWrite,
                                    onAdd = { initialPlanBatch = null; planDate = it; planRecipe = null; editingPlan = null; planDialog = true },
                                    onEdit = { initialPlanBatch = null; editingPlan = it; planRecipe = it.recipe; planDate = LocalDate.parse(it.on_date); planDialog = true },
                                    onDelete = { deletingPlan = it }, onCook = { if (it.prepared_batch_id != null) servePlan = it else { cookingKeepBatch = false; cookingPlan = it.id; cookingRecipe = it.recipe; cookingServings = it.servings } },
                                    onShopping = { shoppingPreview = RecipeMath.shopping(state.snapshot, it) }, modifier = body, onAutomatic = { navigate(12) }, onPrepared = { navigate(11) }, onRecipe = ::openRecipe,
                                    focusDate = focusMealDate, onDateOpened = { focusMealDate = null })
                                4 -> BudgetScreen(state.snapshot, canWrite,
                                    onExpense = { editingExpense = it; expenseDialog = true }, onDelete = { deletingExpense = it },
                                    onBudget = { budgetMonth = it }, onUndo = { undoEvent = it }, modifier = body)
                                5 -> TodayScreen(state, { navigate(it) }, { if (canWrite) requestCamera("batch") }, { navigate(8) }, body,
                                    onAdd = { addPantry() },
                                    onReviewTerms = { reviewPantryTerms = true; navigate(0) },
                                    onOpenMeal = { date -> focusMealDate = date; navigate(3) })
                                6 -> MoreScreen({ navigate(it) }, body, appearance,
                                    onAppearance = { appearanceOpen = true }, onReminders = { remindersDialog = true },
                                    onServer = { settings = true }, serverEnabled = canWrite && !state.syncing, storeEnabled = canWrite)
                                7 -> ProductsScreen(state.snapshot.products, canWrite,
                                    onAdd = { editingProduct = null; productCode = ""; addAfterProduct = false; productDialog = true }, onScan = { requestCamera("barcode") },
                                    onEdit = { editingProduct = it; productCode = it.barcode; addAfterProduct = false; productDialog = true }, onStock = { stockProduct = it }, onDelete = { deletingProduct = it }, modifier = body)
                                8 -> ReceiptsScreen(state, { requestCamera("receipt") }, { galleryLauncher.launch("image/*") }, { receiptDetails = it }, body)
                                9 -> BackupsScreen(state, model::loadBackups, model::createBackup, model::configureBackups, { backup -> model.previewRestore(backup.id) { restorePreview = it } }, body)
                                10 -> InStoreScreen(state.cart, state.snapshot, canWrite, model::updateCart, { model.checkout { navigate(2) } }, { model.discardCart { navigate(2) } }, body)
                                11 -> PreparedScreen(state.snapshot, canWrite,
                                    onCook = { cookingKeepBatch = true; cookingPlan = null; cookingRecipe = it; cookingServings = it.servings },
                                    onPlan = { initialPlanBatch = it; editingPlan = null; planRecipe = it.recipe; planDate = LocalDate.now(); planDialog = true },
                                    onServe = { batch, portions, wasted -> model.serveBatch(batch, portions, wasted, null) {} }, modifier = body, onOpenPlan = { navigate(3) })
                                12 -> AutoPlanScreen(state.snapshot, canWrite, state.online, state.busy, model::previewWeek, { model.acceptWeek(it) { navigate(3, clearHistory = true) } }, body)
                                13 -> StockMinimumScreen(state.snapshot, canWrite && state.snapshot.stock_tools,
                                    model::saveStockMinimum, model::deleteStockMinimum, model::replenish, body)
                                14 -> UseSoonScreen(state, model::useSoonIdeas, onRecipe = ::openRecipePreview, modifier = body)
                                15 -> PriceHistoryScreen(state.snapshot, body)
                            }
                        }
                    }
                }
            }
        }
    }

    if (settings) AlertDialog(
        onDismissRequest = { if (!state.busy) settings = false },
        title = { Text("Serverul tău") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                ConnectionForm(state.config, state.busy, state.lastSync, model::connect, allowSave = !state.syncing && !state.generating)
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
    if (bulkDialog) BulkAddDialog(state.busy, state.message, onDismiss = { bulkDialog = false }, onPreview = model::previewBulk,
        onSave = { items -> model.importBulk(bulkBatchId, items) { bulkDialog = false } })
    if (batchScan) BatchScanDialog(state.snapshot, canWrite, state.message, onDismiss = { batchScan = false },
        onSave = { epoch, rows -> model.importScans(epoch, rows) { batchScan = false } })
    if (remindersDialog) ReminderDialog(state.reminderSettings, notificationAllowed, { remindersDialog = false }, model::saveReminderSettings,
        onPermission = ::requestNotificationPermission, onTest = { if (notificationAllowed) testNotification() else { pendingTest = true; requestNotificationPermission() } })
    if (syncDialog) SyncDialog(state.pending, state.serverSnapshot, canWrite && !state.syncing, { syncDialog = false }, { model.refresh() },
        model::resolveConflict, model::discardRestoredChanges, status = syncLabel(state))
    cookingRecipe?.let { recipe -> CookingDialog(state.snapshot, recipe, cookingServings, canWrite, { cookingRecipe = null }, cookingKeepBatch) { uses, portions, details ->
        if (details == null) model.consume(recipe.title, uses, false, "", null, cookingPlan) { cookingRecipe = null }
        else model.cookBatch(recipe, portions, uses, details, cookingPlan) { cookingRecipe = null }
    } }
    wasteItem?.let { item -> WasteDialog(item, canWrite, { wasteItem = null }) { amount, reason, cents ->
        model.consume(item.name, listOf(StockUse(item, amount)), true, reason, cents, null) { wasteItem = null }
    } }
    if (planDialog) PlanDialog(state.snapshot.allRecipes(), planDate, planRecipe, editingPlan, canWrite, { planDialog = false }, state.snapshot.prepared, initialPlanBatch) { model.savePlan(it) { planDialog = false } }
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
    if (personalDialog) PersonalRecipeDialog(editingPersonal, canWrite, state.snapshot.aliases, { personalDialog = false }) {
        model.savePersonal(it) { personalDialog = false }
    }
    if (importRecipe) {
        val aiImport = state.config?.takeIf { state.online && state.cloudProviders.isNotEmpty() }?.let { config ->
            val api = CamaraApi(config)
            api::importRecipeText
        }
        RecipeImportDialog(canWrite, state.snapshot.aliases, { importRecipe = false }, state.message, aiImport) {
            model.saveImportedPersonal(it) { importRecipe = false }
        }
    }
    if (importPdf) state.config?.let { config ->
        RecipePdfImportDialog(config, canWrite, state.snapshot.aliases, state.message, onDismiss = { importPdf = false },
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
private fun ConfirmAction(title: String, text: String, enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text("Confirmă") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } })
}

private fun syncLabel(state: UiState, compact: Boolean = false): String {
    val time = if (compact) null else state.lastSync?.let {
        DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
    }
    return when {
        state.pending.any { it.issue != null || staleEpoch(it, state.serverSnapshot) } ->
            if (compact) "De verificat" else "Necesită verificare"
        state.syncing -> "Se sincronizează"
        state.pending.isNotEmpty() -> if (compact) "În așteptare" else "Salvat pe dispozitiv"
        state.online -> if (time == null) "La zi" else "La zi · $time"
        state.lastSync != null -> if (time == null) "Offline" else "Offline · date din $time"
        compact -> "Indisponibil"
        else -> "Server indisponibil"
    }
}

@Composable
private fun ConnectionPill(state: UiState, onSync: () -> Unit) {
    val blocked = state.pending.any { it.issue != null || staleEpoch(it, state.serverSnapshot) }
    val color = when {
        blocked -> MaterialTheme.colorScheme.error
        state.pending.isNotEmpty() || !state.online -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    val container = when {
        blocked -> MaterialTheme.colorScheme.errorContainer
        state.pending.isNotEmpty() || !state.online -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when {
        blocked -> MaterialTheme.colorScheme.onErrorContainer
        state.pending.isNotEmpty() || !state.online -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    val description = listOf("Detalii sincronizare", syncLabel(state))
    val pending = state.pending.size.takeIf { it > 0 }?.let { countLabel(it, "acțiune în așteptare", "acțiuni în așteptare") }
    Box(Modifier.widthIn(max = 118.dp).heightIn(min = 48.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onSync)
        .semantics { contentDescription = (description + listOfNotNull(pending)).joinToString(" · ") },
        contentAlignment = Alignment.Center) {
        Surface(color = container, contentColor = content, shape = CircleShape) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (state.syncing && !blocked) CircularProgressIndicator(Modifier.size(11.dp), color = content, strokeWidth = 1.5.dp)
                else Box(Modifier.size(6.dp).background(color, CircleShape))
                Text(syncLabel(state, compact = true), Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (pending != null) Text("${state.pending.size}", style = MaterialTheme.typography.labelSmall.tabularNumbers())
            }
        }
    }
}

