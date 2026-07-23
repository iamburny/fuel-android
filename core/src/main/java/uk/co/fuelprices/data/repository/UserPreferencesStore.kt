package uk.co.fuelprices.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    /** Appearance selector; stored as ThemeMode.name ("SYSTEM" | "LIGHT" | "DARK"). */
    val themeMode: String = "SYSTEM",
    /** Number of cold app launches so far (drives the support prompt cadence). */
    val appOpenCount: Int = 0,
    /** App-open count until which the support prompt is suppressed (set when the CTA is tapped). */
    val coffeePromptPausedUntilOpen: Int = 0,
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
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val appOpenCountKey = intPreferencesKey("app_open_count")
    private val coffeePromptPausedUntilKey = intPreferencesKey("coffee_prompt_paused_until")

    val preferences: Flow<UserPreferences> = context.userPreferencesDataStore.data.map { prefs ->
        UserPreferences(
            fuelType = prefs[fuelTypeKey] ?: "E10",
            mpg = prefs[mpgKey],
            tankCapacityLitres = prefs[tankCapacityKey],
            useLongFuelNames = prefs[useLongFuelNamesKey] ?: false,
            themeMode = prefs[themeModeKey] ?: "SYSTEM",
            appOpenCount = prefs[appOpenCountKey] ?: 0,
            coffeePromptPausedUntilOpen = prefs[coffeePromptPausedUntilKey] ?: 0,
        )
    }

    suspend fun get(): UserPreferences = preferences.first()

    // Note: save() rewrites the user-editable settings only. The launch counters below are written
    // by their own dedicated methods so this call never clobbers them (DataStore.edit only touches
    // the keys it sets).
    suspend fun save(
        fuelType: String,
        mpg: Double?,
        tankCapacityLitres: Double?,
        useLongFuelNames: Boolean,
        themeMode: String,
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
            prefs[themeModeKey] = themeMode
        }
    }

    /** Increment the cold-launch counter and return the new value. */
    suspend fun incrementAppOpenCount(): Int {
        var newCount = 0
        context.userPreferencesDataStore.edit { prefs ->
            newCount = (prefs[appOpenCountKey] ?: 0) + 1
            prefs[appOpenCountKey] = newCount
        }
        return newCount
    }

    /** Suppress the support prompt until the app-open count reaches [untilOpen]. */
    suspend fun pauseCoffeePrompt(untilOpen: Int) {
        context.userPreferencesDataStore.edit { prefs ->
            prefs[coffeePromptPausedUntilKey] = untilOpen
        }
    }
}
