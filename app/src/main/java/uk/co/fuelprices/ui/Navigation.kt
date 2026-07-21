package uk.co.fuelprices.ui

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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import uk.co.fuelprices.ui.screens.detail.DetailScreen
import uk.co.fuelprices.ui.screens.favourites.FavouritesScreen
import uk.co.fuelprices.ui.screens.map.NearbyScreen
import uk.co.fuelprices.ui.screens.preferences.PreferencesScreen
import uk.co.fuelprices.ui.screens.prices.PricesScreen
import uk.co.fuelprices.ui.theme.LocalUseLongFuelNames

sealed class Screen(val route: String) {
    data object Nearby : Screen("nearby")
    data object Prices : Screen("prices")
    data object Favourites : Screen("favourites")
    data object Preferences : Screen("preferences")
    data object Detail : Screen("detail/{stationId}") {
        fun createRoute(stationId: Int) = "detail/$stationId"
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
    // Set when launched from a price-drop notification tap — navigates straight to that station's
    // Detail once, then calls onStartStationHandled so a config change / recomposition doesn't
    // re-navigate.
    startStationId: Int? = null,
    onStartStationHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val useLongFuelNames by appPreferencesViewModel.useLongFuelNames.collectAsState()

    LaunchedEffect(startStationId) {
        startStationId?.let { id ->
            navController.navigate(Screen.Detail.createRoute(id))
            onStartStationHandled()
        }
    }

    // Hide bottom bar on detail screen
    val showBottomBar = currentRoute != Screen.Detail.route

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
                                onClick = {
                                    navController.navigate(item.screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
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
                    PricesScreen()
                }

                composable(Screen.Favourites.route) {
                    FavouritesScreen(
                        onStationClick = { id ->
                            navController.navigate(Screen.Detail.createRoute(id))
                        }
                    )
                }

                composable(Screen.Preferences.route) {
                    PreferencesScreen()
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
