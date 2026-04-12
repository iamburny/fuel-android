package uk.co.fuelprices.ui.screens.map

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Warning
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
import uk.co.fuelprices.data.api.StationDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    onStationClick: (Int) -> Unit,
    viewModel: NearbyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val fuelTypes = listOf("E10", "E5", "B7", "SDV")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fuel Prices") },
                actions = {
                    // Compliance: link to report discrepancies
                    val context = LocalContext.current
                    IconButton(onClick = {
                        val url = state.discrepancyReportUrl.ifBlank {
                            "https://www.fuel-finder.service.gov.uk/report-discrepancy"
                        }
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }) {
                        Icon(Icons.Default.Warning, contentDescription = "Report price discrepancy")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // Fuel type selector
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                fuelTypes.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = state.selectedFuelType == type,
                        onClick = { viewModel.setFuelType(type) },
                        shape = SegmentedButtonDefaults.itemShape(index, fuelTypes.size),
                    ) { Text(type) }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.error != null) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("Error: ${state.error}", color = MaterialTheme.colorScheme.error)
                }
            } else {
                // Map taking top third
                val userPos = LatLng(state.userLat ?: 51.5, state.userLng ?: -0.13)
                val cameraState = rememberCameraPositionState {
                    position = CameraPosition.fromLatLngZoom(userPos, 12f)
                }

                GoogleMap(
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    cameraPositionState = cameraState,
                ) {
                    state.stations.forEach { station ->
                        val cheapestPrice = station.prices
                            .filter { it.fuelType == state.selectedFuelType }
                            .minByOrNull { it.pricePence }

                        Marker(
                            state = MarkerState(LatLng(station.latitude, station.longitude)),
                            title = station.name,
                            snippet = cheapestPrice?.let { "${it.pricePence}p" } ?: "No price",
                            onClick = { onStationClick(station.id); true },
                        )
                    }
                }

                // Station list
                LazyColumn(Modifier.fillMaxSize()) {
                    // Compliance notice
                    item {
                        Text(
                            "Prices sourced from the UK Government Fuel Finder scheme. Tap ⚠ to report incorrect data.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }

                    items(state.stations, key = { it.id }) { station ->
                        StationRow(station, state.selectedFuelType) {
                            onStationClick(station.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StationRow(station: StationDto, fuelType: String, onClick: () -> Unit) {
    val price = station.prices
        .filter { it.fuelType == fuelType }
        .minByOrNull { it.pricePence }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(Icons.Default.LocalGasStation, contentDescription = null)
        },
        headlineContent = { Text(station.name, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Text(
                listOfNotNull(
                    station.brand,
                    station.distanceMiles?.let { "%.1f mi".format(it) },
                    station.postcode,
                ).joinToString(" · ")
            )
        },
        trailingContent = {
            if (price != null) {
                Text(
                    "%.1fp".format(price.pricePence),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
    HorizontalDivider()
}
