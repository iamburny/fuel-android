package uk.co.fuelprices.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import uk.co.fuelprices.ui.components.CoffeeSupportDialog
import uk.co.fuelprices.ui.screens.auth.AuthScreen
import uk.co.fuelprices.ui.screens.detail.DetailScreen
import uk.co.fuelprices.ui.screens.favourites.FavouritesScreen
import uk.co.fuelprices.ui.screens.heatmap.HeatmapScreen
import uk.co.fuelprices.ui.screens.map.NearbyScreen
import uk.co.fuelprices.ui.screens.preferences.PreferencesScreen
import uk.co.fuelprices.ui.screens.prices.PricesScreen
import uk.co.fuelprices.ui.theme.LocalUseLongFuelNames

sealed class Screen(val route: String) {
    data object Nearby : Screen("nearby")
    data object Prices : Screen("prices")
    data object Heatmap : Screen("heatmap")
    data object Favourites : Screen("favourites")
    data object Preferences : Screen("preferences")
    data object Auth : Screen("auth")
    data object Detail : Screen("detail/{stationId}") {
        fun createRoute(stationId: Int) = "detail/$stationId"
    }
}

/**
 * An in-app destination resolved from a launch source — either a price-drop notification tap
 * (FCM) or an Android App Link (`https://fueltracker.uk/...`). Keeping URL-path knowledge here,
 * next to the routes, means MainActivity only does Android intent plumbing.
 */
sealed interface DeepLinkTarget {
    data class Station(val id: Int) : DeepLinkTarget
    data object Prices : DeepLinkTarget
    data object Settings : DeepLinkTarget
    data object Home : DeepLinkTarget

    companion object {
        /**
         * Maps an App Link URI to a destination. Paths mirror the fuel-web routes:
         * `/stations/{id}` → Detail, `/prices` → Prices, `/settings` → Preferences, `/` → Nearby.
         * Returns null for any unrecognised host/path (or a non-numeric station id) so the caller
         * can fall back to just opening the app on its default screen.
         */
        fun fromUri(uri: Uri): DeepLinkTarget? {
            if (!uri.host.equals("fueltracker.uk", ignoreCase = true)) return null
            val segments = uri.pathSegments
            return when {
                segments.isEmpty() -> Home
                segments[0] == "stations" && segments.size >= 2 ->
                    segments[1].toIntOrNull()?.let { Station(it) }
                segments[0] == "prices" -> Prices
                segments[0] == "settings" -> Settings
                else -> null
            }
        }
    }
}

/**
 * Navigate to a bottom-nav tab with the standard single-top / restore-state behaviour, so a deep
 * link into a tab behaves identically to tapping it. Shared by the nav bar and deep-link handling.
 */
private fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private data class BottomNavItem(val screen: Screen, val label: String, val icon: ImageVector)

private val bottomNavItems = listOf(
    BottomNavItem(Screen.Nearby, "Nearby", Icons.Default.Map),
    BottomNavItem(Screen.Prices, "Prices", Icons.AutoMirrored.Filled.TrendingUp),
    BottomNavItem(Screen.Favourites, "Favourites", Icons.Default.Favorite),
    BottomNavItem(Screen.Preferences, "Settings", Icons.Default.Settings),
)

@Composable
fun FuelApp(
    appPreferencesViewModel: AppPreferencesViewModel = hiltViewModel(),
    // Set when launched from a notification tap or an App Link — navigates to the target once, then
    // calls onStartTargetHandled so a config change / recomposition doesn't re-navigate.
    startTarget: DeepLinkTarget? = null,
    onStartTargetHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val useLongFuelNames by appPreferencesViewModel.useLongFuelNames.collectAsState()
    val showCoffeePrompt by appPreferencesViewModel.showCoffeePrompt.collectAsState()
    val context = LocalContext.current

    if (showCoffeePrompt) {
        CoffeeSupportDialog(
            onConfirm = {
                appPreferencesViewModel.onCoffeeClicked()
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/iamburny"))
                    )
                } catch (_: Exception) {
                }
            },
            onDismiss = { appPreferencesViewModel.onDismissCoffee() },
        )
    }

    LaunchedEffect(startTarget) {
        val target = startTarget ?: return@LaunchedEffect
        when (target) {
            is DeepLinkTarget.Station ->
                navController.navigate(Screen.Detail.createRoute(target.id))
            DeepLinkTarget.Prices -> navController.navigateToTab(Screen.Prices.route)
            DeepLinkTarget.Settings -> navController.navigateToTab(Screen.Preferences.route)
            DeepLinkTarget.Home -> navController.navigateToTab(Screen.Nearby.route)
        }
        onStartTargetHandled()
    }

    // Hide bottom bar on full-screen destinations reached by pushing (not a bottom-nav tab)
    val showBottomBar = currentRoute != Screen.Detail.route &&
        currentRoute != Screen.Auth.route &&
        currentRoute != Screen.Heatmap.route

    CompositionLocalProvider(LocalUseLongFuelNames provides useLongFuelNames) {
        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        bottomNavItems.forEach { item ->
                            val selected = navBackStackEntry?.destination?.hierarchy?.any {
                                it.route == item.screen.route
                            } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.navigateToTab(item.screen.route) },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            // Only the bottom nav bar's own inset is applied here — each screen has its own
            // TopAppBar/Scaffold that already handles the top status-bar inset itself. Applying
            // innerPadding's top value too would double up that space (Scaffold reserves it
            // whether or not a topBar is actually declared).
            NavHost(
                navController = navController,
                startDestination = Screen.Nearby.route,
                modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
            ) {
                composable(Screen.Nearby.route) {
                    NearbyScreen(
                        onStationClick = { id ->
                            navController.navigate(Screen.Detail.createRoute(id))
                        }
                    )
                }

                composable(Screen.Prices.route) {
                    PricesScreen(
                        onOpenHeatmap = { navController.navigate(Screen.Heatmap.route) },
                    )
                }

                composable(Screen.Heatmap.route) {
                    HeatmapScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.Favourites.route) {
                    FavouritesScreen(
                        onStationClick = { id ->
                            navController.navigate(Screen.Detail.createRoute(id))
                        },
                        onSignIn = { navController.navigate(Screen.Auth.route) },
                    )
                }

                composable(Screen.Auth.route) {
                    AuthScreen(
                        onAuthed = { navController.popBackStack() },
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Screen.Preferences.route) {
                    PreferencesScreen(
                        onSignIn = { navController.navigate(Screen.Auth.route) },
                    )
                }

                composable(
                    route = Screen.Detail.route,
                    arguments = listOf(navArgument("stationId") { type = NavType.IntType }),
                ) {
                    DetailScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
