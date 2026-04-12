package uk.co.fuelprices.ui.screens.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.station?.name ?: "Station") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleFavourite() }) {
                        Icon(
                            if (state.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            "Toggle favourite",
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val station = state.station ?: return@Scaffold

        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Mini map
            val pos = LatLng(station.latitude, station.longitude)
            GoogleMap(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                cameraPositionState = rememberCameraPositionState {
                    position = CameraPosition.fromLatLngZoom(pos, 15f)
                },
            ) {
                Marker(state = MarkerState(pos), title = station.name)
            }

            // Station info
            Column(Modifier.padding(16.dp)) {
                station.brand?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(2.dp))
                }

                val address = listOfNotNull(station.addressLine1, station.town, station.postcode)
                    .joinToString(", ")
                if (address.isNotBlank()) {
                    Text(address, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                }

                station.distanceMiles?.let {
                    Text("%.1f miles away".format(it), style = MaterialTheme.typography.bodySmall)
                }

                // Directions button
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    val uri = Uri.parse("google.navigation:q=${station.latitude},${station.longitude}")
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps"))
                }) {
                    Icon(Icons.Default.Directions, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Get directions")
                }
            }

            HorizontalDivider()

            // Current prices — presented unmodified per Fair Use Policy
            Text(
                "Current Prices",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
            )

            station.prices.sortedBy { it.pricePence }.forEach { price ->
                ListItem(
                    headlineContent = {
                        Text(fuelTypeLabel(price.fuelType), fontWeight = FontWeight.Medium)
                    },
                    supportingContent = {
                        // Compliance: show original timestamp unmodified
                        Text("Reported: ${price.reportedAt}")
                    },
                    trailingContent = {
                        Text(
                            "%.1fp".format(price.pricePence),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }

            if (station.prices.isEmpty()) {
                Text(
                    "No prices currently available for this station.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            HorizontalDivider()

            // Price history
            if (state.priceHistory.isNotEmpty()) {
                Text(
                    "Price History (30 days)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                )
                state.priceHistory.takeLast(10).forEach { point ->
                    ListItem(
                        headlineContent = { Text("%.1fp".format(point.pricePence)) },
                        supportingContent = { Text(point.reportedAt.take(10)) },
                    )
                }
            }

            HorizontalDivider()

            // Compliance: discrepancy report link (required by Fair Use Policy)
            TextButton(
                onClick = {
                    val url = "https://www.fuel-finder.service.gov.uk/report-discrepancy"
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
                modifier = Modifier.padding(16.dp),
            ) {
                Icon(Icons.Default.Warning, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Report a price discrepancy")
            }

            // Compliance: data notice
            Text(
                "Prices sourced from the UK Government Fuel Finder scheme under the Open Government Licence. Data is presented without modification.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 16.dp),
            )
        }
    }
}

private fun fuelTypeLabel(code: String): String = when (code) {
    "E10" -> "Unleaded (E10)"
    "E5" -> "Super Unleaded (E5)"
    "B7" -> "Diesel (B7)"
    "SDV" -> "Super Diesel"
    "B10" -> "Diesel (B10)"
    "HVO" -> "HVO Diesel"
    else -> code
}
