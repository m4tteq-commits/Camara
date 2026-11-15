package ro.camara.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

internal enum class Destination(val label: String, val icon: ImageVector, val navLabel: String = label) {
    Pantry("Cămara", Icons.Outlined.Kitchen, "Cămară"),
    Recipes("Gătește", Icons.Outlined.RestaurantMenu),
    Shopping("Cumpărături", Icons.Outlined.ShoppingBasket, "Listă"),
    Plan("Plan", Icons.Outlined.CalendarMonth),
    Budget("Buget", Icons.Outlined.AccountBalanceWallet),
    Today("Astăzi", Icons.Outlined.Today),
    More("Mai multe", Icons.Outlined.MoreHoriz, "Mai mult"),
    Products("Catalog", Icons.Outlined.Inventory2),
    Receipts("Bonuri", Icons.AutoMirrored.Outlined.ReceiptLong),
    Backups("Backup", Icons.Outlined.Backup),
    InStore("În magazin", Icons.Outlined.ShoppingCart),
    Prepared("Porții gătite", Icons.Outlined.TakeoutDining),
    Automatic("Plan automat", Icons.Outlined.AutoAwesome),
    Minimums("Reaprovizionare", Icons.Outlined.Inventory2),
    UseSoon("Rețete din cămară", Icons.Outlined.RestaurantMenu),
    Prices("Prețuri", Icons.AutoMirrored.Outlined.ShowChart),
    History("Istoric", Icons.Outlined.History);

    val navGroup: Destination
        get() = when (this) {
            InStore, Prices -> Shopping
            Minimums -> Pantry
            UseSoon -> Recipes
            Plan, Budget, Products, Receipts, Backups, Prepared, Automatic, History -> More
            else -> this
        }

    val backFromRoot: Destination
        get() = when (this) {
            Automatic -> Plan
            InStore, Prices -> Shopping
            Minimums -> Pantry
            Plan, Budget, Products, Receipts, Backups, Prepared, History -> More
            else -> Today
        }
}

internal val primaryDestinations = listOf(Destination.Today, Destination.Pantry, Destination.Recipes, Destination.Shopping, Destination.More)

internal data class AppPage(val destination: Destination, val recipeId: String?) {
    val saveKey: String get() = recipeId?.let { "recipe:$it" } ?: "screen:${destination.ordinal}"
}
