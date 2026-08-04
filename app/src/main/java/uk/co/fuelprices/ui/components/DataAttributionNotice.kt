package uk.co.fuelprices.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Google Play "Misleading Claims" policy requires apps that surface government data to
 * provide a clear, functional link to the original .gov source, alongside a non-affiliation
 * disclaimer — plain-text mentions of "gov.uk/..." are not tappable and don't satisfy this.
 */
const val DEFAULT_DATA_NOTICE: String =
    "Prices sourced from the UK Government's Fuel Finder scheme " +
        "(gov.uk/government/collections/fuel-finder) under the Open Government " +
        "Licence. Data is presented without modification. Fuel Tracker UK is an " +
        "independent app and is not affiliated with or endorsed by HM Government."

private const val OFFICIAL_SOURCE_URL = "https://www.gov.uk/government/collections/fuel-finder"
private const val LIVE_SERVICE_URL = "https://www.fuel-finder.service.gov.uk/"

@Composable
fun DataAttributionNotice(
    modifier: Modifier = Modifier,
    noticeText: String = DEFAULT_DATA_NOTICE,
    showDiscrepancyButton: Boolean = true,
) {
    val context = LocalContext.current

    Column(modifier) {
        if (showDiscrepancyButton) {
            TextButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LIVE_SERVICE_URL)))
                },
                modifier = Modifier.padding(16.dp, 0.dp),
            ) {
                Icon(Icons.Default.Warning, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Report a price discrepancy")
            }
        }

        TextButton(
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_SOURCE_URL)))
            },
            modifier = Modifier.padding(16.dp, 0.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("View official source (gov.uk)")
        }

        Text(
            noticeText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 16.dp),
        )
    }
}
