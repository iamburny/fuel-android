package uk.co.fuelprices.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template

private const val DISCREPANCY_REPORT_URL = "https://www.fuel-finder.service.gov.uk/report-discrepancy"

/**
 * Fair Use Policy compliance: data attribution notice and discrepancy report link.
 * Opening the report link is gated behind [ParkedOnlyOnClickListener] per driver-distraction rules.
 */
class DataNoticeScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        return LongMessageTemplate.Builder(
            "Prices sourced from the UK Government Fuel Finder scheme under the Open " +
                "Government Licence. Data is presented without modification."
        )
            .setTitle("Data & Reporting")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Report a Price Discrepancy")
                    .setBackgroundColor(CarColor.BLUE)
                    .setOnClickListener(ParkedOnlyOnClickListener.create { openReportLink() })
                    .build()
            )
            .build()
    }

    private fun openReportLink() {
        carContext.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(DISCREPANCY_REPORT_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
