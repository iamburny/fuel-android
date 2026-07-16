package uk.co.fuelprices.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import uk.co.fuelprices.data.repository.FuelRepository
import uk.co.fuelprices.data.repository.UserPreferencesStore
import uk.co.fuelprices.util.LocationHelper

class FuelCarSession(
    private val repository: FuelRepository,
    private val locationHelper: LocationHelper,
    private val preferencesStore: UserPreferencesStore,
) : Session() {

    override fun onCreateScreen(intent: Intent): Screen =
        NearbyStationsScreen(carContext, repository, locationHelper, preferencesStore)
}
