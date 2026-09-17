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
    val useLongFuelNames: Boolean = true,
    /** Appearance selector; stored as ThemeMode.name ("SYSTEM" | "LIGHT" | "DARK"). */
    val themeMode: String = "SYSTEM",
    /** Number of cold app launches so far (drives the support prompt cadence). */
    val appOpenCount: Int = 0,
    /** App-open count until which the support prompt is suppressed (set when the CTA is tapped). */
    val coffeePromptPausedUntilOpen: Int = 0,
    /** Text of the last-dismissed announcement banner message — re-shows automatically if the
     *  flag's variant text changes (a new announcement), same behaviour as the web/admin banner. */
    val dismissedAnnouncementMessage: String? = null,
    /** [uk.co.fuelprices.ui.components.ReleaseNoticeContent.dismissKey] of the last-dismissed
     *  release notice — re-shows automatically if the flag's variant content changes. */
    val dismissedReleaseNoticeKey: String? = null,
    /** True once the Nearby screen's one-time "Cheapest prices" toggle tooltip has been shown.
     *  Unlike [dismissedAnnouncementMessage]/[dismissedReleaseNoticeKey], this isn't tied to any
     *  remote flag/content — it's a plain permanent flag that, once true, never re-arms. */
    val hasSeenNearbyCheapestTooltip: Boolean = false,
    /** True once the Nearby screen's one-time fuel-type pill tooltip has been shown. Chained after
     *  [hasSeenNearbyCheapestTooltip] — see [NearbyViewModel][uk.co.fuelprices.ui.screens.map.NearbyViewModel]'s
     *  init block. Same permanent, never-re-armed semantics. */
    val hasSeenFuelTypePillTooltip: Boolean = false,
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
    private val dismissedAnnouncementKey = stringPreferencesKey("dismissed_announcement_message")
    private val dismissedReleaseNoticePrefKey = stringPreferencesKey("dismissed_release_notice_key")
    private val hasSeenNearbyCheapestTooltipKey = booleanPreferencesKey("has_seen_nearby_cheapest_tooltip")
    private val hasSeenFuelTypePillTooltipKey = booleanPreferencesKey("has_seen_fuel_type_pill_tooltip")

    val preferences: Flow<UserPreferences> = context.userPreferencesDataStore.data.map { prefs ->
        UserPreferences(
            fuelType = prefs[fuelTypeKey] ?: "E10",
            mpg = prefs[mpgKey],
            tankCapacityLitres = prefs[tankCapacityKey],
            useLongFuelNames = prefs[useLongFuelNamesKey] ?: true,
            themeMode = prefs[themeModeKey] ?: "SYSTEM",
            appOpenCount = prefs[appOpenCountKey] ?: 0,
            coffeePromptPausedUntilOpen = prefs[coffeePromptPausedUntilKey] ?: 0,
            dismissedAnnouncementMessage = prefs[dismissedAnnouncementKey],
            dismissedReleaseNoticeKey = prefs[dismissedReleaseNoticePrefKey],
            hasSeenNearbyCheapestTooltip = prefs[hasSeenNearbyCheapestTooltipKey] ?: false,
            hasSeenFuelTypePillTooltip = prefs[hasSeenFuelTypePillTooltipKey] ?: false,
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

    /** Records [message] as dismissed — the announcement banner stays hidden until the flag's
     *  variant text changes to something else. */
    suspend fun dismissAnnouncement(message: String) {
        context.userPreferencesDataStore.edit { prefs ->
            prefs[dismissedAnnouncementKey] = message
        }
    }

    /** Records [key] as dismissed — the release notice stays hidden until the flag's variant
     *  content changes to something else. */
    suspend fun dismissReleaseNotice(key: String) {
        context.userPreferencesDataStore.edit { prefs ->
            prefs[dismissedReleaseNoticePrefKey] = key
        }
    }

    /** Marks the Nearby screen's one-time "Cheapest prices" toggle tooltip as seen — permanent,
     *  never re-armed (unlike [dismissAnnouncement]/[dismissReleaseNotice]). */
    suspend fun markNearbyCheapestTooltipSeen() {
        context.userPreferencesDataStore.edit { prefs ->
            prefs[hasSeenNearbyCheapestTooltipKey] = true
        }
    }

    /** Marks the Nearby screen's one-time fuel-type pill tooltip as seen — permanent, never
     *  re-armed, mirroring [markNearbyCheapestTooltipSeen]. */
    suspend fun markFuelTypePillTooltipSeen() {
        context.userPreferencesDataStore.edit { prefs ->
            prefs[hasSeenFuelTypePillTooltipKey] = true
        }
    }
}
