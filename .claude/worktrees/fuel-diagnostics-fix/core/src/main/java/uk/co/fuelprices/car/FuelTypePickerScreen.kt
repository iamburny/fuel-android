package uk.co.fuelprices.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.api.FuelTypes
import uk.co.fuelprices.data.repository.UserPreferencesStore

/** Single-choice picker for the "usual fuel" preference, saved directly to this device's store. */
class FuelTypePickerScreen(
    carContext: CarContext,
    private val preferencesStore: UserPreferencesStore,
    private val currentFuelType: String,
    private val useLongFuelNames: Boolean,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val itemList = ItemList.Builder()
            .setOnSelectedListener { index -> onSelected(FuelTypes.ALL[index]) }
            .setSelectedIndex(FuelTypes.ALL.indexOf(currentFuelType).coerceAtLeast(0))

        FuelTypes.ALL.forEach { type ->
            val label = if (useLongFuelNames) FuelTypes.longLabel(type) else FuelTypes.shortLabel(type)
            itemList.addItem(Row.Builder().setTitle(label).build())
        }

        return ListTemplate.Builder()
            .setSingleList(itemList.build())
            .setHeader(
                Header.Builder()
                    .setTitle("Usual Fuel")
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }

    private fun onSelected(fuelType: String) {
        lifecycle.coroutineScope.launch {
            val current = preferencesStore.get()
            preferencesStore.save(
                fuelType = fuelType,
                mpg = current.mpg,
                tankCapacityLitres = current.tankCapacityLitres,
                useLongFuelNames = current.useLongFuelNames,
                themeMode = current.themeMode,
            )
            screenManager.pop()
        }
    }
}
