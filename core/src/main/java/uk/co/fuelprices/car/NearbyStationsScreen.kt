package uk.co.fuelprices.car

import android.Manifest
import android.location.Location
import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.Metadata
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.api.FuelTypes
import uk.co.fuelprices.data.api.NationalAverageDto
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.data.repository.FuelRepository
import uk.co.fuelprices.data.repository.UserPreferences
import uk.co.fuelprices.data.repository.UserPreferencesStore
import uk.co.fuelprices.util.LocationHelper
import uk.co.fuelprices.util.estimateNetSavingsPounds
import kotlin.math.abs

private const val RADIUS_MILES = 15.0
private const val FALLBACK_MAX_ROWS = 6

/**
 * Root Android Auto screen: nearby fuel stations using a POI map template. Sorted by distance,
 * or — once the user has set MPG and tank capacity in the phone app's Preferences screen — by
 * estimated net savings (station price vs national average, minus the round-trip drive cost).
 */
class NearbyStationsScreen(
    carContext: CarContext,
    private val repository: FuelRepository,
    private val locationHelper: LocationHelper,
    private val preferencesStore: UserPreferencesStore,
) : Screen(carContext) {

    private var isLoading = true
    private var stations: List<StationDto> = emptyList()
    private var errorMessage: String? = null
    private var currentLocation: Location? = null
    private var preferences: UserPreferences = UserPreferences()
    private var averages: List<NationalAverageDto> = emptyList()

    init {
        if (locationHelper.hasPermission()) {
            lifecycle.coroutineScope.launch { refresh() }
        } else {
            isLoading = false
        }
    }

    private suspend fun refresh() {
        isLoading = true
        errorMessage = null
        invalidate()

        val location: Location? = locationHelper.getCurrentLocation()
        currentLocation = location
        if (location == null) {
            isLoading = false
            errorMessage = "Couldn't determine your location."
            invalidate()
            return
        }

        try {
            preferences = preferencesStore.get()
            val response = repository.getNearbyStations(
                lat = location.latitude,
                lng = location.longitude,
                radiusMiles = RADIUS_MILES,
            )
            stations = if (preferences.canEstimateDriveCost) {
                averages = repository.getNationalAverages().averages
                response.stations.sortedByDescending { station ->
                    estimateNetSavingsPounds(station, averages, preferences) ?: Double.NEGATIVE_INFINITY
                }
            } else {
                response.stations.sortedBy { it.distanceMiles ?: Double.MAX_VALUE }
            }
        } catch (e: Exception) {
            errorMessage = "Couldn't load fuel stations."
        }
        isLoading = false
        invalidate()
    }

    /** Prefer the host's actual place-list limit over our own guess; falls back if unsupported. */
    private fun maxRows(): Int = try {
        maxOf(
            FALLBACK_MAX_ROWS,
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST)
        )
    } catch (e: Exception) {
        FALLBACK_MAX_ROWS
    }

    override fun onGetTemplate(): Template {
        if (!locationHelper.hasPermission()) {
            return buildPermissionPromptTemplate()
        }

        val itemListBuilder = ItemList.Builder()
        when {
            isLoading -> itemListBuilder.setNoItemsMessage("Finding nearby fuel stations…")
            stations.isEmpty() -> itemListBuilder.setNoItemsMessage(
                errorMessage ?: "No fuel stations found nearby."
            )
            else -> {
                val limit = minOf(stations.size, maxRows())
                stations.take(limit).forEach { itemListBuilder.addItem(buildStationRow(it)) }
            }
        }

        val templateBuilder = PlaceListMapTemplate.Builder()
            .setItemList(itemListBuilder.build())
            .setTitle("Nearby Fuel Prices")
            .setHeaderAction(Action.APP_ICON)
            .setCurrentLocationEnabled(true)

        // Anchors the host's camera on the device's location; without this the map has
        // nothing to frame and defaults to a static full-world view.
        currentLocation?.let { location ->
            templateBuilder.setAnchor(Place.Builder(CarLocation.create(location)).build())
        }

        return templateBuilder
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Refresh")
                            .setOnClickListener { lifecycle.coroutineScope.launch { refresh() } }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle("Data & Reporting")
                            .setOnClickListener { screenManager.push(DataNoticeScreen(carContext)) }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun buildPermissionPromptTemplate(): Template {
        return LongMessageTemplate.Builder(
            "Fuel Prices UK needs location access to find nearby stations. " +
                "Grant the permission below, or on your phone if prompted."
        )
            .setTitle("Location Access Needed")
            .setHeaderAction(Action.APP_ICON)
            .addAction(
                Action.Builder()
                    .setTitle("Grant Access")
                    .setBackgroundColor(CarColor.BLUE)
                    .setOnClickListener(ParkedOnlyOnClickListener.create { requestLocationPermission() })
                    .build()
            )
            .build()
    }

    private fun requestLocationPermission() {
        carContext.requestPermissions(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) { _, _ ->
            if (locationHelper.hasPermission()) {
                lifecycle.coroutineScope.launch { refresh() }
            }
            invalidate()
        }
    }

    private fun buildStationRow(station: StationDto): Row {
        val cheapest = station.prices.minByOrNull { it.pricePence }
        val priceText = cheapest?.let {
            "${FuelTypes.shortLabel(it.fuelType)}: %.1fp".format(it.pricePence)
        } ?: "No price reported"

        val netSavings = if (preferences.canEstimateDriveCost) {
            estimateNetSavingsPounds(station, averages, preferences)
        } else {
            null
        }
        val trailingText = netSavings?.let { formatNetSavings(it) } ?: priceText

        val hasDistance = station.distanceMiles != null
        val description = SpannableStringBuilder()
        station.distanceMiles?.let { miles ->
            description.append(
                " ",
                DistanceSpan.create(Distance.create(miles, Distance.UNIT_MILES)),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            description.append(" · ")
        }
        description.append(trailingText)

        val location = Location("station").apply {
            latitude = station.latitude
            longitude = station.longitude
        }

        return Row.Builder()
            .setTitle(station.brand?.takeIf { it.isNotBlank() } ?: station.name)
            .addText(description)
            .setOnClickListener {
                screenManager.push(StationDetailScreen(carContext, station))
            }
            .setMetadata(
                Metadata.Builder()
                    .setPlace(
                        Place.Builder(CarLocation.create(location))
                            .setMarker(PlaceMarker.Builder().build())
                            .build()
                    )
                    .build()
            )
            // PlaceListMapTemplate requires every non-browsable row to carry a DistanceSpan;
            // fall back to browsable (adds a chevron, still pushes the detail screen) when
            // the API didn't return a distance for this station.
            .setBrowsable(!hasDistance)
            .build()
    }

    private fun formatNetSavings(pounds: Double): String {
        val amount = "£%.2f".format(abs(pounds))
        return if (pounds >= 0) "Save $amount (half tank, net)" else "Costs $amount more (net)"
    }
}
