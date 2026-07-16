package uk.co.fuelprices.util

import uk.co.fuelprices.data.api.NationalAverageDto
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.data.repository.UserPreferences

private const val LITRES_PER_UK_GALLON = 4.546

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

    val costPerMilePounds = (avgPricePence / 100.0 * LITRES_PER_UK_GALLON) / mpg
    val roundTripCostPounds = costPerMilePounds * distanceMiles * 2

    return grossSavingsPounds - roundTripCostPounds
}
