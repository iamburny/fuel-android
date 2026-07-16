package uk.co.fuelprices.ui.screens.map

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import uk.co.fuelprices.data.api.FuelTypes
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.ui.components.FuelMapView
import uk.co.fuelprices.ui.components.MapMarker
import uk.co.fuelprices.ui.theme.fuelLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    onStationClick: (Int) -> Unit,
    viewModel: NearbyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    // Plain boolean instead of a draggable BottomSheetScaffold: a real bottom sheet's drag
    // gestures can land in intermediate anchor states (partially expanded at a "peek" height)
    // that don't cleanly map to a simple open/closed toggle button. This panel is fully
    // deterministic — only the top bar button controls it, no drag-to-ambiguous-state.
    var showPanel by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fuel Prices") },
                actions = {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .padding(horizontal = 4.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    IconButton(onClick = {
                        if (showPanel) viewModel.setSearchQuery("")
                        showPanel = !showPanel
                    }) {
                        Icon(
                            if (showPanel) Icons.Default.Clear else Icons.Default.Search,
                            contentDescription = if (showPanel) "Close" else "Search",
                        )
                    }
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
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val mapMarkers = if (!state.isLoading) {
                state.stations.map { station ->
                    val cheapestPrice = station.prices
                        .filter { it.fuelType == state.selectedFuelType }
                        .minByOrNull { it.pricePence }
                    MapMarker(
                        lat = station.latitude,
                        lng = station.longitude,
                        title = station.name,
                        snippet = cheapestPrice?.let { "%.1fp".format(it.pricePence) } ?: "No price",
                        id = station.id,
                    )
                }
            } else emptyList()

            FuelMapView(
                modifier = Modifier.fillMaxSize(),
                centerLat = state.userLat ?: 51.5,
                centerLng = state.userLng ?: -0.13,
                zoomLevel = 12f,
                markers = mapMarkers,
                onMarkerClick = onStationClick,
            )

            if (showPanel) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.8f),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    tonalElevation = 4.dp,
                ) {
                    Column(Modifier.fillMaxSize()) {
                        // Search field is a fixed, always-visible first item — never scrolled
                        // out of view, regardless of how long the station list below gets.
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            placeholder = { Text("Search by name, postcode, or brand") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            trailingIcon = {
                                if (state.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                        Icon(Icons.Default.Clear, "Clear search")
                                    }
                                }
                            },
                            singleLine = true,
                        )

                        // Mode toggle (Nearby / Cheapest)
                        if (state.searchQuery.length < 2) {
                            SingleChoiceSegmentedButtonRow(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                            ) {
                                SegmentedButton(
                                    selected = state.mode == ListMode.NEARBY,
                                    onClick = { viewModel.setMode(ListMode.NEARBY) },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                ) { Text("Nearby") }
                                SegmentedButton(
                                    selected = state.mode == ListMode.CHEAPEST,
                                    onClick = { viewModel.setMode(ListMode.CHEAPEST) },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                ) { Text("Cheapest") }
                            }

                            Spacer(Modifier.height(4.dp))
                        }

                        // Fuel type chips
                        Row(
                            Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FuelTypes.ALL.forEach { type ->
                                FilterChip(
                                    selected = state.selectedFuelType == type,
                                    onClick = { viewModel.setFuelType(type) },
                                    label = { Text(fuelLabel(type)) },
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Station list fills remaining space and scrolls on its own — the
                        // loading state is a small spinner in the top bar, not shown here, so
                        // a refresh doesn't hide the existing list.
                        if (state.error != null) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Error: ${state.error}", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
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
