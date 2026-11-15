package ro.camara.app.debug

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import ro.camara.app.UiState
import ro.camara.app.data.*
import ro.camara.app.ui.*

/** Visual fixtures only. Never connects to the server or persists sample data. */
class DesignPreviewActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27 && intent.getBooleanExtra("show_locked", false)) {
            // Only this synthetic preview may be shown while the device stays locked.
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (intent.getBooleanExtra("landscape", false)) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val page = intent.getStringExtra("page") ?: "home"
        val dark = intent.getStringExtra("theme") != "light"
        val scale = intent.getFloatExtra("font", 1f).coerceIn(1f, 1.5f)
        val sampleBarcode = "5941234123458"
        val newBarcode = "4006381333931"
        val today = LocalDate.now()
        val recipe = Recipe("visual-recipe", "Paste cu sos de roșii", 20, 2,
            listOf(Ingredient("Paste", "200 g"), Ingredient("Sos de roșii", "300 g")), listOf("Respectă instrucțiunile de pe ambalaj."), model = "Rețeta mea", generated_at = "", generation_ms = 0)
        val batch = PreparedBatch("visual-batch", recipe, today.toString(), today.plusDays(1).toString(), total = 4, remaining = 3)
        val expiringRecipe = recipe.copy(id = "visual-pancakes", title = "Clătite cu lapte",
            ingredients = listOf(Ingredient("Lapte", "200 ml"), Ingredient("Ouă", "1 buc")),
            steps = listOf("Amestecă ingredientele.", "Coace clătitele în tigaie."))
        val initialSnapshot = Snapshot(
            pantry = listOf(PantryItem("visual-eggs", "Ouă", 6.0, expires_on = today.plusDays(1).toString()), PantryItem("visual-milk", "Lapte", 1.0, "l", expires_on = today.plusDays(2).toString()), PantryItem("visual-pasta", "Paste integrale", 500.0, "g", "camara")),
            shopping = listOf(ShoppingItem("visual-shopping-1", "Iaurt natural", "400 g"), ShoppingItem("visual-shopping-2", "Roșii", "500 g"), ShoppingItem("visual-shopping-3", "Pâine integrală", "1 buc", true)),
            plans = listOf(MealPlan("visual-plan", today.toString(), "lunch", recipe, 1, prepared_batch_id = batch.id)), prepared = listOf(batch), recipes = listOf(recipe, expiringRecipe),
            stock_minimums = listOf(StockMinimum("visual-min-eggs", "Ouă", 8.0, "buc"), StockMinimum("visual-min-milk", "Lapte", 2.0, "l")),
            stock_tools = true,
            products = listOf(Product("visual-product", "Iaurt natural", sampleBarcode, 400.0, "g", receipt_names = listOf("IAURT NAT"))),
            receipts = listOf(ReceiptRecord("visual-receipt", "0".repeat(64), "Magazin A", today.minusDays(3).toString(), 800, "receipt-expense", "",
                listOf(ReceiptLine("IAURT NAT", 400.0, "g", line_cents = 800)))),
            purchases = listOf(Purchase("visual-purchase", "Magazin B", today.toString(), 1800, "purchase-expense",
                listOf(PurchaseLine("Iaurt natural", 1.0, "kg", "frigider", 1800, true)))),
            expenses = listOf(Expense("visual-expense", "Cumpărături", 6345, today.toString())), budgets = listOf(Budget("visual-budget", today.toString().take(7), 50000)))
        val cart = ShoppingCart("visual-cart", "Magazinul de lângă casă", today.toString(), listOf(CartRow("visual-cart-1", "Iaurt natural", "400", "g", "8.50", "frigider"), CartRow("visual-cart-2", "Roșii", "500", "g", "6.20")))
        setContent {
            var snapshot by remember { mutableStateOf(initialSnapshot) }
            var activePage by rememberSaveable { mutableStateOf(page) }
            var appearance by remember { mutableStateOf(CamaraAppearance(mode = if (dark) CamaraMode.Dark else CamaraMode.Light)) }
            var appearanceOpen by remember { mutableStateOf(page == "themes") }
            var quickUse by remember { mutableStateOf<PantryItem?>(null) }
            var selectedIdea by remember { mutableStateOf(expiringRecipe) }
            var scanOpen by rememberSaveable { mutableStateOf(page == "scan") }
            var scanError by remember { mutableStateOf<String?>(null) }
            fun apply(action: PendingAction) { snapshot = ActionRules.apply(snapshot, action.mutation) }
            BackHandler(activePage in listOf("minimums", "use-soon", "idea", "guide")) {
                activePage = when (activePage) { "minimums" -> "pantry"; "idea" -> "use-soon"; "guide" -> "idea"; else -> "home" }
            }
            val actual = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(actual.density, scale)) {
                CamaraTheme(appearance.dark(dark), appearance.palette, appearance.motion) {
                    Scaffold(topBar = { TopAppBar(title = { Text("Design · date fictive", style = MaterialTheme.typography.titleMedium) },
                        actions = { IconButton(onClick = { appearanceOpen = true }) { Icon(Icons.Outlined.Palette, "Teme și aspect") } }) }, bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            listOf("Astăzi" to Icons.Outlined.Today, "Cămară" to Icons.Outlined.Kitchen, "Gătește" to Icons.Outlined.RestaurantMenu, "Listă" to Icons.Outlined.ShoppingBasket, "Mai mult" to Icons.Outlined.MoreHoriz).forEachIndexed { index, item ->
                                val destination = listOf("home", "pantry", "recipes", "shopping", "more")[index]
                                NavigationBarItem(activePage == destination, { activePage = destination }, icon = { Icon(item.second, null) }, label = { Text(item.first, style = MaterialTheme.typography.labelSmall) })
                            }
                        }
                    }) { padding ->
                        val modifier = Modifier.padding(padding).fillMaxSize()
                        when (activePage) {
                            "pantry" -> PantryScreen(snapshot.pantry, {}, {}, {}, true, true, {}, modifier,
                                onOpenMinimums = { activePage = "minimums" }, onUse = { quickUse = it }, onScanBatch = { scanOpen = true })
                            "shopping" -> ShoppingScreen(snapshot, true, {}, {}, modifier, {}, { _, _, _ -> }, { activePage = "prices" })
                            "prices" -> PriceHistoryScreen(snapshot, modifier)
                            "store" -> InStoreScreen(cart, snapshot, true, {}, {}, {}, modifier)
                            "plan" -> PlanScreen(snapshot, true, {}, {}, {}, {}, {}, modifier)
                            "prepared" -> PreparedScreen(snapshot, true, {}, {}, { _,_,_ -> }, modifier)
                            "more" -> MoreScreen({}, modifier, appearance, onAppearance = { appearanceOpen = true })
                            "recipes" -> RecipesScreen(UiState(snapshot = snapshot, online = true, aiMode = "device", localModelReady = true),
                                true, {}, {}, modifier, {}, {}, {}, {}, onSuggest = {}, onPreview = {})
                            "auto" -> AutoPlanScreen(snapshot, true, true, false, { _,_ -> }, {}, modifier)
                            "minimums" -> StockMinimumScreen(snapshot, true,
                                { previous, rule, done -> apply(StockMinimumRules.save(snapshot, previous, rule)); done() },
                                { rule, done -> apply(StockMinimumRules.remove(snapshot, rule)); done() },
                                { preview -> snapshot = ActionRules.apply(snapshot, Mutation("Reaprovizionare", preview.changes)) }, modifier)
                            "use-soon" -> UseSoonScreen(UiState(snapshot = snapshot), { emptyList() }, { selectedIdea = it; activePage = "idea" }, modifier)
                            "idea" -> RecipeDetails(selectedIdea, true, { activePage = "guide" }, {}, modifier, {}, {})
                            "guide" -> CookingMode(selectedIdea, modifier) { activePage = "idea" }
                            else -> TodayScreen(UiState(snapshot = snapshot, online = true), { index ->
                                activePage = when (index) { Destination.Pantry -> "pantry"; Destination.Recipes -> "recipes"; Destination.Shopping -> "shopping"; Destination.Plan -> "plan"; Destination.Prepared -> "prepared"; Destination.Automatic -> "auto"; Destination.Minimums -> "minimums"; Destination.UseSoon -> "use-soon"; else -> activePage }
                            }, {}, {}, modifier)
                        }
                    }
                    if (appearanceOpen) AppearanceSheet(appearance, { appearance = it }) { appearanceOpen = false }
                    if (scanOpen) BatchScanDialog(snapshot, true, scanError, onDismiss = { scanOpen = false }, onSave = { epoch, rows ->
                        runCatching { apply(scannedStockAction(snapshot, epoch, rows)) }.onSuccess {
                            scanOpen = false
                            activePage = "pantry"
                        }.onFailure { scanError = it.message }
                    }, sampleCodes = listOf(sampleBarcode, newBarcode))
                    quickUse?.let { item ->
                        QuickUseDialog(item, true, { quickUse = null }) { quantity ->
                            apply(RecipeMath.consume(snapshot, item.name, listOf(StockUse(item, quantity)), quickUse = true))
                            quickUse = null
                        }
                    }
                }
            }
        }
    }
}
