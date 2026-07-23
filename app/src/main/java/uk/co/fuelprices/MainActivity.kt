package uk.co.fuelprices

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import uk.co.fuelprices.ui.AppPreferencesViewModel
import uk.co.fuelprices.ui.DeepLinkTarget
import uk.co.fuelprices.ui.FuelApp
import uk.co.fuelprices.ui.theme.FuelPricesTheme
import uk.co.fuelprices.ui.theme.ThemeMode
import uk.co.fuelprices.util.LocationHelper
import javax.inject.Inject

const val EXTRA_STATION_ID = "stationId"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var locationHelper: LocationHelper

    private val appPreferencesViewModel: AppPreferencesViewModel by viewModels()

    // The destination to open from the launch source — an FCM notification tap or a fueltracker.uk
    // App Link. Read from the launch intent (cold start) and from onNewIntent (warm start). Compose
    // observes it and navigates once, then clears it.
    private var pendingTarget by mutableStateOf<DeepLinkTarget?>(null)

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Only the location grants count as a location result — POST_NOTIFICATIONS may be in the
        // same request, and a notification denial must not be misread as location being granted.
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        locationHelper.notifyPermissionResult(locationGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingTarget = targetFromIntent(intent)

        // Count this launch once per cold start (not on config-change recreation).
        if (savedInstanceState == null) {
            appPreferencesViewModel.onAppOpened()
        }

        locationPermission.launch(buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray())

        setContent {
            val themeMode by appPreferencesViewModel.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT.name -> false
                ThemeMode.DARK.name -> true
                else -> isSystemInDarkTheme()
            }
            FuelPricesTheme(darkTheme = darkTheme) {
                FuelApp(
                    // Pass the Activity-scoped instance explicitly so the coffee-prompt state set by
                    // onAppOpened() is the exact one FuelApp observes (no reliance on two owners
                    // resolving to the same ViewModel).
                    appPreferencesViewModel = appPreferencesViewModel,
                    startTarget = pendingTarget,
                    onStartTargetHandled = { pendingTarget = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetFromIntent(intent)?.let { pendingTarget = it }
    }

    private fun targetFromIntent(intent: Intent?): DeepLinkTarget? {
        if (intent == null) return null
        // FCM price-drop notification tap → open that station's Detail (unchanged behaviour).
        intent.getIntExtra(EXTRA_STATION_ID, -1).takeIf { it > 0 }?.let {
            return DeepLinkTarget.Station(it)
        }
        // App Link: map the tapped https://fueltracker.uk/... URL to an in-app destination.
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { return DeepLinkTarget.fromUri(it) }
        }
        return null
    }
}
