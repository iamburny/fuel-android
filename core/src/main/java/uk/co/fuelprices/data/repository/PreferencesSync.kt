package uk.co.fuelprices.data.repository

import uk.co.fuelprices.data.api.PreferencesDto

/**
 * Reconciles this device's local preferences with the account's stored ones: a field the account
 * has already set wins over the local value; a field the account has never set (null) adopts this
 * device's local value instead, seeding the account the first time it's fetched. Saves the merged
 * result locally and pushes it back (so a field just adopted from local gets persisted server-side
 * too), then returns it.
 *
 * Best-effort — returns null on any failure (including being signed out), so callers can no-op
 * rather than surface an error for what's just a background reconciliation.
 *
 * Shared by [uk.co.fuelprices.ui.screens.auth.AuthViewModel] (right after a successful sign-in)
 * and [uk.co.fuelprices.ui.screens.preferences.PreferencesViewModel] (every time the Preferences
 * screen is (re)entered while already signed in — login alone only reconciles once, so a change
 * made on another device/platform after that first login would otherwise never reach this one).
 */
suspend fun syncPreferencesBestEffort(
    repo: FuelRepository,
    store: UserPreferencesStore,
): UserPreferences? {
    if (!repo.isLoggedIn()) return null
    return try {
        val remote = repo.getPreferences()
        val local = store.get()
        val mergedFuelType = remote.fuelType ?: local.fuelType
        val mergedMpg = remote.mpg ?: local.mpg
        val mergedTankCapacityLitres = remote.tankCapacityLitres ?: local.tankCapacityLitres
        val mergedUseLongFuelNames = remote.useLongFuelNames ?: local.useLongFuelNames
        val mergedThemeMode = remote.themeMode ?: local.themeMode

        store.save(
            fuelType = mergedFuelType,
            mpg = mergedMpg,
            tankCapacityLitres = mergedTankCapacityLitres,
            useLongFuelNames = mergedUseLongFuelNames,
            themeMode = mergedThemeMode,
        )
        try {
            repo.updatePreferences(
                PreferencesDto(
                    fuelType = mergedFuelType,
                    mpg = mergedMpg,
                    tankCapacityLitres = mergedTankCapacityLitres,
                    useLongFuelNames = mergedUseLongFuelNames,
                    themeMode = mergedThemeMode,
                ),
            )
        } catch (_: Exception) {
            // The local merge/save above already succeeded — a failed push just means the account
            // doesn't get this device's contribution this time; it'll retry next sync.
        }
        store.get()
    } catch (_: Exception) {
        null
    }
}
