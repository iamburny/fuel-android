package uk.co.fuelprices.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.userPreferencesDataStore by preferencesDataStore(name = "user_preferences")

data class UserPreferences(
    val fuelType: String = "E10",
    val mpg: Double? = null,
    val tankCapacityLitres: Double? = null,
    val useLongFuelNames: Boolean = false,
) {
    /** True once there's enough info to estimate a driving cost (see FuelCostCalculator). */
    val canEstimateDriveCost: Boolean get() = mpg != null && tankCapacityLitres != null
}

@Singleton
class UserPreferencesStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val fuelTypeKey = stringPreferencesKey("fuel_type")
    private val mpgKey = doublePreferencesKey("mpg")
    private val tankCapacityKey = doublePreferencesKey("tank_capacity_litres")
    private val useLongFuelNamesKey = booleanPreferencesKey("use_long_fuel_names")

    val preferences: Flow<UserPreferences> = context.userPreferencesDataStore.data.map { prefs ->
        UserPreferences(
            fuelType = prefs[fuelTypeKey] ?: "E10",
            mpg = prefs[mpgKey],
            tankCapacityLitres = prefs[tankCapacityKey],
            useLongFuelNames = prefs[useLongFuelNamesKey] ?: false,
        )
    }

    suspend fun get(): UserPreferences = preferences.first()

    suspend fun save(
        fuelType: String,
        mpg: Double?,
        tankCapacityLitres: Double?,
        useLongFuelNames: Boolean,
    ) {
        context.userPreferencesDataStore.edit { prefs ->
            prefs[fuelTypeKey] = fuelType
            if (mpg != null) prefs[mpgKey] = mpg else prefs.remove(mpgKey)
            if (tankCapacityLitres != null) {
                prefs[tankCapacityKey] = tankCapacityLitres
            } else {
                prefs.remove(tankCapacityKey)
            }
            prefs[useLongFuelNamesKey] = useLongFuelNames
        }
    }
}
