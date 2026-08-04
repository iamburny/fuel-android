package uk.co.fuelprices.ui.screens.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import uk.co.fuelprices.data.api.FuelTypes
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.ui.components.AnnouncementBanner
import uk.co.fuelprices.ui.components.BrandTitle
import uk.co.fuelprices.ui.components.DataAttributionNotice
import uk.co.fuelprices.ui.components.FuelMapView
import uk.co.fuelprices.ui.components.MapMarker
import uk.co.fuelprices.ui.theme.fuelColor
import uk.co.fuelprices.ui.theme.fuelLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    onStationClick: (Int) -> Unit,
    viewModel: NearbyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    // Plain boolean instead of a draggable BottomSheetScaffold: a real bottom sheet's drag
    // gestures can land in intermediate anchor states (partially expanded at a "peek" height)
    // that don't cleanly map to a simple open/closed toggle button. This panel is fully
    // deterministic — only the top bar button controls it, no drag-to-ambiguous-state.
    var showPanel by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        // No bottomBar here (the tab bar lives in the outer Scaffold in Navigation.kt), but
        // Scaffold reserves bottom system-bar inset space in innerPadding regardless of whether
        // a bottomBar is actually declared — stacking with the outer Scaffold's own bottom
        // padding and leaving a gap. Same root cause as the earlier top-bar gap fix, mirrored.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { BrandTitle() },
                actions = {
                    // One control: the refresh button turns into a spinner while a load is in
                    // flight (and is disabled so it can't fire a duplicate request), then reverts
                    // to the refresh icon.
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.isLoading) {
                        if (state.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                    IconButton(onClick = {
                        // Only clear (and thus re-fetch) if there was actually a search in
                        // progress — closing an empty search panel shouldn't re-fetch anything.
                        if (showPanel && state.searchQuery.isNotEmpty()) viewModel.setSearchQuery("")
                        showPanel = !showPanel
                    }) {
                        Icon(
                            if (showPanel) Icons.Default.Clear else Icons.Default.Search,
                            contentDescription = if (showPanel) "Close" else "Search",
                        )
                    }
                }
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
        AnnouncementBanner()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Falls back to the GPS-anchored station set until the user's first drag produces a
            // viewport load; the bottom list panel below always keeps using state.stations,
            // unaffected by dragging.
            val mapMarkers = if (!state.isLoading) {
                (state.viewportStations ?: state.stations).map { station ->
                    val cheapestPrice = station.prices
                        .filter { it.fuelType == state.selectedFuelType }
                        .minByOrNull { it.pricePence }
                    MapMarker(
                        lat = station.latitude,
                        lng = station.longitude,
                        title = station.name,
                        snippet = cheapestPrice?.let { "%.1fp".format(it.pricePence) } ?: "No price",
                        id = station.id,
                        color = FuelTypes.color(state.selectedFuelType),
                    )
                }
            } else emptyList()

            // Don't render the map until a location is resolved — showing it centered on a
            // hardcoded fallback first, then jumping once the real one arrives, reads as a flash.
            val userLat = state.userLat
            val userLng = state.userLng
            if (userLat != null && userLng != null) {
                FuelMapView(
                    modifier = Modifier.fillMaxSize(),
                    centerLat = userLat,
                    centerLng = userLng,
                    zoomLevel = 12f,
                    markers = mapMarkers,
                    onMarkerClick = { id ->
                        viewModel.trackStationClick(id, "map")
                        onStationClick(id)
                    },
                    recenterKey = state.cameraRecenterToken,
                    onCameraIdle = { bounds -> viewModel.loadStationsInBounds(bounds) },
                    showMyLocation = true,
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            // Thin browser-style progress bar while a drag-triggered viewport reload is in
            // flight — the pins themselves don't disappear (old ones stay until the new response
            // lands), so without this the long pause after a drag reads as the app being stuck.
            if (state.isLoadingViewport) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                )
            }

            // Currently filtered fuel type, always visible regardless of panel state. Tapping it
            // cycles to the next fuel type — a quick way to flip through prices without opening
            // the search panel's chip row.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clickable {
                        val nextIndex = (FuelTypes.ALL.indexOf(state.selectedFuelType) + 1) % FuelTypes.ALL.size
                        viewModel.setFuelType(FuelTypes.ALL[nextIndex])
                    },
                shape = RoundedCornerShape(50),
                color = FuelTypes.color(state.selectedFuelType),
                shadowElevation = 4.dp,
            ) {
                Text(
                    fuelLabel(state.selectedFuelType),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // Shown only once the user has dragged away from their GPS location — auto-recenter
            // on filter/radius/mode changes was removed so it doesn't fight the drag, so a manual
            // way back is needed (standard Google Maps convention). BottomStart (not BottomEnd) —
            // the map's own zoom controls already occupy the bottom-right corner.
            if (state.isOffGpsCenter) {
                SmallFloatingActionButton(
                    onClick = { viewModel.recenterOnGps() },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Recenter on my location")
                }
            }

            // Graceful connectivity notice: after repeated back-to-back connection failures the
            // map has no fresh prices to show, so rather than leave it silently empty we surface a
            // dismissible banner with a Retry. Sits below the fuel-type pill so the two don't
            // overlap. Clears automatically once a fetch succeeds (state.apiUnreachable flips).
            if (state.apiUnreachable) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp, start = 12.dp, end = 12.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                        ) {
                            Text(
                                "Can't reach the fuel price service",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                "Check your connection — showing saved prices where available.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        TextButton(onClick = { viewModel.refresh() }) {
                            Text("Retry")
                        }
                    }
                }
            }

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
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = FuelTypes.color(type),
                                        selectedLabelColor = Color.White,
                                    ),
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
                                    // Compliance: real, tappable link to the official gov.uk
                                    // source (required by the Misleading Claims policy — a
                                    // plain-text mention of "gov.uk/..." is not an accessible
                                    // link), plus the discrepancy-report action it referred to.
                                    DataAttributionNotice()
                                }

                                items(state.stations, key = { it.id }) { station ->
                                    StationRow(station, state.selectedFuelType) {
                                        viewModel.trackStationClick(station.id, "list")
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
                    color = fuelColor(fuelType),
                )
            }
        },
    )
    HorizontalDivider()
}
