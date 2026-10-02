package uk.co.fuelprices.util

import uk.co.fuelprices.data.api.NationalAverageDto
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.data.repository.UserPreferences
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val LITRES_PER_UK_GALLON = 4.546
private const val EARTH_RADIUS_MILES = 3958.8

/** Haversine distance in miles between two lat/lng points. */
fun haversineMiles(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_MILES * c
}

/** Estimated one-way fuel cost (£) to drive [distanceMiles] at [mpg], using [pricePence] (pence
 * per litre) as the cost basis. */
fun estimateDriveCostPounds(distanceMiles: Double, mpg: Double, pricePence: Double): Double {
    val costPerMilePounds = (pricePence / 100.0 * LITRES_PER_UK_GALLON) / mpg
    return costPerMilePounds * distanceMiles
}

/**
 * Net value (£) of filling half a tank at [station] at its price for [UserPreferences.fuelType],
 * versus the national average price, minus the estimated round-trip fuel cost of driving there
 * (using the national average price as the cost basis for the fuel already in the tank).
 * Positive means the detour is worth it; negative means it costs more than it saves.
 *
 * Returns null if preferences don't have enough info yet, or the station has no distance or no
 * price for the preferred fuel type.
 */
fun estimateNetSavingsPounds(
    station: StationDto,
    averages: List<NationalAverageDto>,
    preferences: UserPreferences,
): Double? {
    val mpg = preferences.mpg ?: return null
    val tankCapacityLitres = preferences.tankCapacityLitres ?: return null
    val distanceMiles = station.distanceMiles ?: return null
    val stationPricePence = station.prices
        .firstOrNull { it.fuelType == preferences.fuelType }?.pricePence ?: return null
    val avgPricePence = averages
        .firstOrNull { it.fuelType == preferences.fuelType }?.avgPricePence ?: return null

    val litresToFill = tankCapacityLitres / 2
    val grossSavingsPounds = (avgPricePence - stationPricePence) / 100.0 * litresToFill

    val roundTripCostPounds = estimateDriveCostPounds(distanceMiles, mpg, avgPricePence) * 2

    return grossSavingsPounds - roundTripCostPounds
}
