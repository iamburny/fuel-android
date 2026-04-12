package uk.co.fuelprices.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import uk.co.fuelprices.ui.screens.detail.DetailScreen
import uk.co.fuelprices.ui.screens.map.NearbyScreen

sealed class Screen(val route: String) {
    data object Nearby : Screen("nearby")
    data object Detail : Screen("detail/{stationId}") {
        fun createRoute(stationId: Int) = "detail/$stationId"
    }
}

@Composable
fun FuelApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Screen.Nearby.route) {
        composable(Screen.Nearby.route) {
            NearbyScreen(
                onStationClick = { id ->
                    navController.navigate(Screen.Detail.createRoute(id))
                }
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
