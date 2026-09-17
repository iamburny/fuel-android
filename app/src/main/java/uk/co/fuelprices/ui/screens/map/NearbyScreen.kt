package uk.co.fuelprices.ui.screens.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
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
                    // isPersistent = true: per Material3's TooltipState (see
                    // rememberTooltipState()'s KDoc, androidx.compose.material3 1.3.1) a
                    // non-persistent tooltip auto-dismisses after a short (1.5s) timeout, while a
                    // persistent one "will only be dismissed when the user clicks outside the
                    // bounds of the tooltip or if TooltipState.dismiss() is called". TooltipBox's
                    // underlying Popup defaults dismissOnClickOutside = true and is focusable, so
                    // an outside tap (including on the anchor IconButton itself, or anywhere else
                    // on screen) is consumed to dismiss it rather than reaching whatever's
                    // underneath — that's what makes tapping the button or tapping elsewhere both
                    // count as "dismiss" without any extra wiring here. A tap *inside* the
                    // tooltip's own bounds is not treated as "outside" by Popup, so
                    // CheapestTooltipBubble below adds its own clickable-to-dismiss.
                    val tooltipState = rememberTooltipState(isPersistent = true)
                    // One-time coach-mark pointing at the toggle, shown once ever (see
                    // NearbyUiState.showCheapestTooltip) — only ever while the panel is closed,
                    // and marked seen only once show() returns (i.e. only after the user actually
                    // dismisses it, since it's now persistent), so an interruption mid-first show
                    // doesn't burn the user's only chance to see it.
                    LaunchedEffect(state.showCheapestTooltip, showPanel) {
                        if (state.showCheapestTooltip && !showPanel) {
                            tooltipState.show()
                            viewModel.markCheapestTooltipSeen()
                        }
                    }
                    TooltipBox(
                        // Custom below-anchor placement — Material3 only ships an
                        // above-anchor-with-below-fallback provider
                        // (TooltipDefaults.rememberPlainTooltipPositionProvider()).
                        positionProvider = rememberBelowAnchorTooltipPositionProvider(),
                        tooltip = {
                            CheapestTooltipBubble(
                                state = tooltipState,
                                text = "See the cheapest fuel prices near you",
                            )
                        },
                        state = tooltipState,
                    ) {
                        IconButton(onClick = {
                            // Only clear (and thus re-fetch) if there was actually a search in
                            // progress — closing an empty search panel shouldn't re-fetch anything.
                            if (showPanel && state.searchQuery.isNotEmpty()) viewModel.setSearchQuery("")
                            showPanel = !showPanel
                        }) {
                            Icon(
                                if (showPanel) Icons.Default.Clear else Icons.Default.MonetizationOn,
                                contentDescription = if (showPanel) "Close" else "Cheapest prices",
                            )
                        }
                    }
                }
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
        AnnouncementBanner()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Falls back to the GPS-anchored station set until the user's first drag produces a
            // viewport load; the search panel's default (non-search) list below tracks the same
            // set via state.cheapestSortedStations(), so it always matches what's pinned here.
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
                    // Falls back to the GPS fix only when the map hasn't been dragged (or has
                    // just been recentered) — otherwise this restores wherever the user last left
                    // the camera, including after navigating to Detail and back.
                    centerLat = state.cameraLat ?: userLat,
                    centerLng = state.cameraLng ?: userLng,
                    zoomLevel = state.cameraZoom,
                    markers = mapMarkers,
                    onMarkerClick = { id ->
                        viewModel.trackStationClick(id, "map")
                        onStationClick(id)
                    },
                    recenterKey = state.cameraRecenterToken,
                    onCameraIdle = { position, bounds -> viewModel.loadStationsInBounds(position, bounds) },
                    // Enabling the SDK's "my location" layer without the permission actually
                    // granted throws a SecurityException, so this must track the real permission
                    // state rather than being assumed true.
                    showMyLocation = state.hasLocationPermission,
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
                            // Search results (searchQuery.length >= 2) use state.stations as
                            // returned by the search API, untouched by any of the below. The
                            // default (non-search) list instead tracks whatever's currently
                            // pinned on the map, cheapest-first, via cheapestSortedStations() —
                            // which drops stations with no price for selectedFuelType, so an
                            // explicit empty state is needed for that case.
                            val isSearching = state.searchQuery.length >= 2
                            val listStations = if (isSearching) state.stations else state.cheapestSortedStations()
                            if (!isSearching && !state.isLoading && listStations.isEmpty()) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "No nearby stations currently report a ${fuelLabel(state.selectedFuelType)} price.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            } else {
                                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                                    items(listStations, key = { it.id }) { station ->
                                        StationRow(station, state.selectedFuelType) {
                                            viewModel.trackStationClick(station.id, "list")
                                            onStationClick(station.id)
                                        }
                                    }

                                    item {
                                        // Compliance: real, tappable link to the official gov.uk
                                        // source (required by the Misleading Claims policy — a
                                        // plain-text mention of "gov.uk/..." is not an accessible
                                        // link), plus the discrepancy-report action it referred
                                        // to. Last row, after all stations.
                                        DataAttributionNotice()
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

// --- Cheapest-toggle coach-mark tooltip: below-anchor speech bubble ------------------------

private val CheapestTooltipTailWidth = 16.dp
private val CheapestTooltipTailHeight = 8.dp
private val CheapestTooltipCornerRadius = 8.dp
private val CheapestTooltipAnchorGap = 4.dp

/**
 * [PopupPositionProvider] that places the tooltip BELOW its anchor, horizontally centered under
 * it — unlike [TooltipDefaults.rememberPlainTooltipPositionProvider], which prefers above the
 * anchor (falling back to below only if there's no room above). Verified against the real
 * [PopupPositionProvider] interface (androidx.compose.ui:ui-android 1.7.5 sources):
 * `calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
 * popupContentSize: IntSize): IntOffset`, both bounds/offset window-relative.
 *
 * [gap] is the vertical space left between the anchor's bottom edge and the tip of the bubble's
 * tail. The tail itself is drawn as part of the popup content (see [rememberSpeechBubbleShape]),
 * occupying the content's own top [CheapestTooltipTailHeight] — so a small [gap] (not
 * [CheapestTooltipTailHeight] itself) is enough for the tail to read as touching the anchor
 * without overlapping it.
 */
@Composable
private fun rememberBelowAnchorTooltipPositionProvider(
    gap: Dp = CheapestTooltipAnchorGap,
): PopupPositionProvider {
    val gapPx = with(LocalDensity.current) { gap.roundToPx() }
    return remember(gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
                val y = anchorBounds.bottom + gapPx
                return IntOffset(x, y)
            }
        }
    }
}

/**
 * Speech-bubble [Shape]: a rounded-rectangle body with a small triangular tail centered on its
 * top edge, pointing straight up — towards the anchor button the tooltip now sits below. Built
 * with [GenericShape] (`Path.(size: Size, layoutDirection: LayoutDirection) -> Unit`, per
 * androidx.compose.foundation:foundation-android 1.7.5 sources) by tracing the outline clockwise
 * from just right of the top-left corner: across the top edge, detouring up-and-back-down to
 * form the tail, on to the top-right corner, then standard quarter-circle [arcTo] calls for each
 * rounded corner.
 *
 * The tail occupies the shape's own top [CheapestTooltipTailHeight] (the rounded body starts
 * there, not at y = 0) — callers must pad their content below that so text doesn't render into
 * the notch.
 */
@Composable
private fun rememberSpeechBubbleShape(): Shape {
    val density = LocalDensity.current
    val tailWidthPx = with(density) { CheapestTooltipTailWidth.toPx() }
    val tailHeightPx = with(density) { CheapestTooltipTailHeight.toPx() }
    val cornerRadiusPx = with(density) { CheapestTooltipCornerRadius.toPx() }
    return remember(tailWidthPx, tailHeightPx, cornerRadiusPx) {
        GenericShape { size, _ ->
            val tailHalfWidth = tailWidthPx / 2f
            val centerX = size.width / 2f
            val bodyTop = tailHeightPx
            val bodyBottom = size.height
            // Guard against a content box too small for the requested radius (e.g. very short
            // text), which would otherwise produce overlapping/self-intersecting arcs.
            val r = cornerRadiusPx.coerceAtMost(minOf(size.width, bodyBottom - bodyTop) / 2f)

            moveTo(r, bodyTop)
            lineTo(centerX - tailHalfWidth, bodyTop)
            lineTo(centerX, 0f) // tail apex, pointing up at the anchor
            lineTo(centerX + tailHalfWidth, bodyTop)
            lineTo(size.width - r, bodyTop)
            arcTo(
                rect = Rect(size.width - 2 * r, bodyTop, size.width, bodyTop + 2 * r),
                startAngleDegrees = -90f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            ) // top-right corner
            lineTo(size.width, bodyBottom - r)
            arcTo(
                rect = Rect(size.width - 2 * r, bodyBottom - 2 * r, size.width, bodyBottom),
                startAngleDegrees = 0f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            ) // bottom-right corner
            lineTo(r, bodyBottom)
            arcTo(
                rect = Rect(0f, bodyBottom - 2 * r, 2 * r, bodyBottom),
                startAngleDegrees = 90f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            ) // bottom-left corner
            lineTo(0f, bodyTop + r)
            arcTo(
                rect = Rect(0f, bodyTop, 2 * r, bodyTop + 2 * r),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            ) // top-left corner
            close()
        }
    }
}

/**
 * Custom tooltip content standing in for [PlainTooltip]: a [Surface] clipped to
 * [rememberSpeechBubbleShape] instead of a plain rounded rect. Also adds its own tap-to-dismiss —
 * [TooltipBox]'s underlying Popup (androidx.compose.ui:ui-android 1.7.5,
 * `AndroidPopup.android.kt`'s `onTouchEvent`) only calls `onDismissRequest` for a touch *outside*
 * the popup's bounds (or `ACTION_OUTSIDE`); a tap landing inside the bubble itself is ordinary
 * in-bounds input and is otherwise ignored, so without this a tap directly on the tooltip
 * wouldn't dismiss it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheapestTooltipBubble(state: TooltipState, text: String) {
    Surface(
        shape = rememberSpeechBubbleShape(),
        color = TooltipDefaults.plainTooltipContainerColor,
        contentColor = TooltipDefaults.plainTooltipContentColor,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = state::dismiss,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(
                start = 12.dp,
                end = 12.dp,
                bottom = 8.dp,
                top = CheapestTooltipTailHeight + 8.dp,
            ),
        )
    }
}
