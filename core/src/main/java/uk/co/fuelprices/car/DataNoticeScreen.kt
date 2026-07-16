package uk.co.fuelprices.car

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template

// The specific report-discrepancy path 404s (confirmed via curl) — same issue fixed on the phone
// app's Detail/Prices screens. Points at the working base domain until the real report URL is known.
private const val DISCREPANCY_REPORT_URL = "https://www.fuel-finder.service.gov.uk/"

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
        // CarContext.startActivity() is the plain inherited Context.startActivity() — the Car
        // App Library itself places no restriction on it, but Android Automotive OS's own
        // ActivityTaskManagerService denies templated car apps permission to launch a browser
        // (confirmed via logcat: SecurityException "Permission Denial: starting Intent
        // act=android.intent.action.VIEW ... cmp=com.android.car.linkviewer/.LinkViewerActivity"
        // — the OS does have a link-viewer component, the app just isn't allowed to start it).
        // This is a genuine OS-level policy, not a missing-app edge case, so there's no way to
        // open a web link from an in-car screen at all; degrade to a toast instead of crashing.
        try {
            carContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(DISCREPANCY_REPORT_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            CarToast.makeText(
                carContext,
                "No browser available in the car. Report discrepancies from the phone app instead.",
                CarToast.LENGTH_LONG,
            ).show()
        } catch (e: SecurityException) {
            CarToast.makeText(
                carContext,
                "Can't open web links from the car. Report discrepancies from the phone app instead.",
                CarToast.LENGTH_LONG,
            ).show()
        }
    }
}
