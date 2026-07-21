package uk.co.fuelprices

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import uk.co.fuelprices.ui.FuelApp
import uk.co.fuelprices.ui.theme.FuelPricesTheme
import uk.co.fuelprices.util.LocationHelper
import javax.inject.Inject

const val EXTRA_STATION_ID = "stationId"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var locationHelper: LocationHelper

    // A notification tap that opens Detail — read from the launch intent (cold start) and from
    // onNewIntent (warm start). Compose observes it and navigates once, then clears it.
    private var pendingStationId by mutableStateOf<Int?>(null)

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

        pendingStationId = stationIdFromIntent(intent)

        locationPermission.launch(buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray())

        setContent {
            FuelPricesTheme {
                FuelApp(
                    startStationId = pendingStationId,
                    onStartStationHandled = { pendingStationId = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        stationIdFromIntent(intent)?.let { pendingStationId = it }
    }

    private fun stationIdFromIntent(intent: Intent?): Int? =
        intent?.getIntExtra(EXTRA_STATION_ID, -1)?.takeIf { it > 0 }
}
