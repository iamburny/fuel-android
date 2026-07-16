package uk.co.fuelprices.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.HostException
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import uk.co.fuelprices.data.api.FuelTypes
import uk.co.fuelprices.data.api.NationalAverageDto
import uk.co.fuelprices.data.api.StationDto

/** Android Auto detail screen for a single station: prices per fuel type + navigate action. */
class StationDetailScreen(
    carContext: CarContext,
    private val station: StationDto,
    private val useLongFuelNames: Boolean = true,
    private val nationalAverages: List<NationalAverageDto> = emptyList(),
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val paneBuilder = Pane.Builder()

        listOfNotNull(station.addressLine1, station.town, station.postcode)
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.let { addressLines ->
                paneBuilder.addRow(
                    Row.Builder()
                        .setTitle("Address")
                        .addText(addressLines.joinToString(", "))
                        .build()
                )
            }

        if (station.prices.isEmpty()) {
            paneBuilder.addRow(Row.Builder().setTitle("No prices reported").build())
        } else {
            station.prices.sortedBy { it.pricePence }.forEach { price ->
                val label = if (useLongFuelNames) FuelTypes.longLabel(price.fuelType) else FuelTypes.shortLabel(price.fuelType)
                val rowBuilder = Row.Builder()
                    .setTitle(label)
                    .addText("%.1fp".format(price.pricePence))
                val avgPence = nationalAverages.firstOrNull { it.fuelType == price.fuelType }?.avgPricePence
                if (avgPence != null) {
                    rowBuilder.addText("%+.1fp vs national avg".format(price.pricePence - avgPence))
                }
                paneBuilder.addRow(rowBuilder.build())
            }
        }

        paneBuilder.addAction(
            Action.Builder()
                .setTitle("Navigate")
                .setBackgroundColor(CarColor.BLUE)
                .setOnClickListener { navigate() }
                .build()
        )

        return PaneTemplate.Builder(paneBuilder.build())
            .setHeader(
                Header.Builder()
                    .setTitle(station.brand?.takeIf { it.isNotBlank() } ?: station.name)
                    .setStartHeaderAction(Action.BACK)
                    .addEndHeaderAction(
                        Action.Builder()
                            .setTitle("Data & Reporting")
                            .setOnClickListener { screenManager.push(DataNoticeScreen(carContext)) }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun navigate() {
        val label = Uri.encode(station.brand?.takeIf { it.isNotBlank() } ?: station.name)
        val uri = Uri.parse("geo:0,0?q=${station.latitude},${station.longitude}($label)")
        try {
            carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, uri))
        } catch (e: HostException) {
            CarToast.makeText(carContext, "Couldn't start navigation.", CarToast.LENGTH_LONG).show()
        }
    }
}
