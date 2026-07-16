package uk.co.fuelprices.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.api.FuelTypes
import uk.co.fuelprices.data.repository.UserPreferences
import uk.co.fuelprices.data.repository.UserPreferencesStore

/**
 * Car-native equivalent of the phone's Preferences screen. This device has no pairing to the
 * phone, so "usual fuel" / "long fuel names" must be settable here too, saved straight to this
 * device's own local UserPreferencesStore.
 */
class CarPreferencesScreen(
    carContext: CarContext,
    private val preferencesStore: UserPreferencesStore,
) : Screen(carContext) {

    private var preferences: UserPreferences = UserPreferences()

    init {
        // Reload on every resume, not just creation — the fuel-type picker is a child screen
        // that saves directly to the store, so we need fresh data when popping back to this one.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                lifecycle.coroutineScope.launch {
                    preferences = preferencesStore.get()
                    invalidate()
                }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val fuelLabel = if (preferences.useLongFuelNames) {
            FuelTypes.longLabel(preferences.fuelType)
        } else {
            FuelTypes.shortLabel(preferences.fuelType)
        }

        val pane = Pane.Builder()
            .addRow(
                // PaneTemplate rows can't carry an onClickListener (RowConstraints.ROW_CONSTRAINTS_PANE
                // disallows it) — navigation has to go through a row-level Action instead.
                Row.Builder()
                    .setTitle("Usual fuel")
                    .addText(fuelLabel)
                    .addAction(
                        Action.Builder()
                            .setTitle("Change")
                            .setOnClickListener {
                                screenManager.push(
                                    FuelTypePickerScreen(
                                        carContext,
                                        preferencesStore,
                                        preferences.fuelType,
                                        preferences.useLongFuelNames,
                                    )
                                )
                            }
                            .build()
                    )
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Long fuel names")
                    .addText("Show \"Diesel (B7)\" instead of \"Diesel\"")
                    .setToggle(
                        Toggle.Builder { checked -> onLongFuelNamesChanged(checked) }
                            .setChecked(preferences.useLongFuelNames)
                            .build()
                    )
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setHeader(
                Header.Builder()
                    .setTitle("Preferences")
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }

    private fun onLongFuelNamesChanged(checked: Boolean) {
        lifecycle.coroutineScope.launch {
            preferencesStore.save(
                fuelType = preferences.fuelType,
                mpg = preferences.mpg,
                tankCapacityLitres = preferences.tankCapacityLitres,
                useLongFuelNames = checked,
            )
            preferences = preferencesStore.get()
            invalidate()
        }
    }
}
