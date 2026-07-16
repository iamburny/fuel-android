package uk.co.fuelprices.car

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import dagger.hilt.android.AndroidEntryPoint
import uk.co.fuelprices.data.repository.FuelRepository
import uk.co.fuelprices.data.repository.UserPreferencesStore
import uk.co.fuelprices.util.LocationHelper
import javax.inject.Inject

/** Android Auto entry point — hosts the nearby fuel stations POI experience. */
@AndroidEntryPoint
class FuelCarAppService : CarAppService() {

    @Inject lateinit var repository: FuelRepository
    @Inject lateinit var locationHelper: LocationHelper
    @Inject lateinit var preferencesStore: UserPreferencesStore

    override fun onCreateSession(): Session =
        FuelCarSession(repository, locationHelper, preferencesStore)

    override fun createHostValidator(): HostValidator {
        return if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }
}
