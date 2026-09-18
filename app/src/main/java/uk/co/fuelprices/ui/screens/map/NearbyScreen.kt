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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
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
import androidx.compose.ui.res.painterResource
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
import uk.co.fuelprices.R
import uk.co.fuelprices.data.api.FuelTypes
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.ui.components.AnnouncementBanner
import uk.co.fuelprices.ui.components.BrandTitle
import uk.co.fuelprices.ui.components.DataAttributionNotice
import uk.co.fuelprices.ui.components.FuelMapView
import uk.co.fuelprices.ui.components.MapMarker
import uk.co.fuelprices.ui.theme.fuelColor
import uk.co.fuelprices.ui.theme.fuelLabel
import uk.co.fuelprices.util.approximateDistanceMiles

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    onStationClick: (Int) -> Unit,
    onSignIn: () -> Unit,
    viewModel: NearbyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    // Plain boolean instead of a draggable BottomSheetScaffold: a real bottom sheet's drag
    // gestures can land in intermediate anchor states (partially expanded at a "peek" height)
    // that don't cleanly map to a simple open/closed toggle button. This panel is fully
    // deterministic — only the top bar button controls it, no drag-to-ambiguous-state.
    var showPanel by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Re-fires on every reappearance (not just first composition ever) since NavHost disposes and
    // recomposes this screen's content across a Detail push/pop even though the Hilt-scoped
    // NearbyViewModel survives — a station favourited/unfavourited from Detail needs to be
    // reflected here on return.
    LaunchedEffect(Unit) { viewModel.refreshFavourites() }

    LaunchedEffect(state.favouriteEvent) {
        when (val event = state.favouriteEvent) {
            is NearbyFavouriteEvent.SignInRequired -> {
                val result = snackbarHostState.showSnackbar(
                    message = "Sign in to save favourites",
                    actionLabel = "Sign in",
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) onSignIn()
                viewModel.consumeFavouriteEvent()
            }
            is NearbyFavouriteEvent.ActionFailed -> {
                snackbarHostState.showSnackbar(event.message, duration = SnackbarDuration.Short)
                viewModel.consumeFavouriteEvent()
            }
            null -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    // SpeechBubbleTooltip below adds its own clickable-to-dismiss.
                    val tooltipState = rememberTooltipState(isPersistent = true)
                    // One-time coach-mark pointing at the toggle, shown once ever (see
                    // NearbyUiState.showCheapestTooltip) — only ever while the panel is closed.
                    LaunchedEffect(state.showCheapestTooltip, showPanel) {
                        if (state.showCheapestTooltip && !showPanel) {
                            tooltipState.show()
                        }
                    }
                    // Marks the coach-mark seen exactly once, on a genuine true->false transition
                    // of tooltipState.isVisible — NOT after tooltipState.show() returns. Verified
                    // against the real androidx.compose.material3 1.3.1 sources
                    // (material3-android-1.3.1-sources.jar, Tooltip.kt's TooltipStateImpl /
                    // internal/BasicTooltip.android.kt's TooltipPopup): with isPersistent = true,
                    // show() suspends via suspendCancellableCoroutine, stashing the continuation in
                    // a private `job`. dismiss() (called from SpeechBubbleTooltip's own
                    // clickable, or from TooltipPopup's onDismissRequest on an outside tap) only
                    // sets `transition.targetState = false` — it never resumes or cancels that
                    // continuation. The *only* thing that does is BasicTooltipBox's
                    // `DisposableEffect(state) { onDispose { state.onDispose() } }`, which cancels
                    // `job` with a CancellationException when the whole TooltipBox leaves
                    // composition — so on an ordinary dismiss, show() simply never returns, and
                    // code placed after it (like the old direct markCheapestTooltipSeen() call)
                    // never runs. isVisible itself (`transition.currentState ||
                    // transition.targetState`) IS reliably observable, though: both fields are
                    // documented as ("Both currentState and targetState are backed by a State
                    // object", MutableTransitionState's KDoc in animation-core 1.7.5) `by
                    // mutableStateOf(...)`, so snapshotFlow correctly reacts once the popup has
                    // actually finished its fade-out and left composition — for every dismiss path
                    // (bubble tap, outside tap, or the panel opening) without wiring each one
                    // individually.
                    LaunchedEffect(tooltipState) {
                        var wasVisible = false
                        snapshotFlow { tooltipState.isVisible }.collect { visible ->
                            if (wasVisible && !visible) {
                                viewModel.markCheapestTooltipSeen()
                            }
                            wasVisible = visible
                        }
                    }
                    val tooltipTail = remember { SpeechBubbleTailState() }
                    TooltipBox(
                        // Custom below-anchor placement — Material3 only ships an
                        // above-anchor-with-below-fallback provider
                        // (TooltipDefaults.rememberPlainTooltipPositionProvider()).
                        positionProvider = rememberBelowAnchorTooltipPositionProvider(tooltipTail),
                        tooltip = {
                            SpeechBubbleTooltip(
                                state = tooltipState,
                                text = "See the cheapest fuel prices near you",
                                tail = tooltipTail,
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
                            // Closed state is a pound sign in a circle rather than
                            // Material's MonetizationOn (a *dollar* in a circle), to match the
                            // iOS app's "sterlingsign.circle" SF Symbol on this same control.
                            if (showPanel) {
                                Icon(Icons.Default.Clear, contentDescription = "Close")
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_sterling_circle),
                                    contentDescription = "Cheapest prices",
                                )
                            }
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
                    bearing = state.mapBearing,
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
            //
            // Second, chained one-time coach-mark: shares the same below-anchor speech-bubble
            // machinery as the cheapest-toggle tooltip above (rememberBelowAnchorTooltipPositionProvider,
            // SpeechBubbleTooltip) and the exact same isVisible-transition dismiss-detection
            // pattern — see the top-bar tooltip's comments for why that (not show() returning)
            // is what makes "shown once ever" actually work. Unlike the top-bar tooltip, this one
            // is NOT gated on showPanel — the pill lives on the map itself and stays visible
            // regardless of the search panel's open/closed state.
            val fuelPillTooltipState = rememberTooltipState(isPersistent = true)
            LaunchedEffect(state.showFuelTypePillTooltip) {
                if (state.showFuelTypePillTooltip) {
                    fuelPillTooltipState.show()
                }
            }
            LaunchedEffect(fuelPillTooltipState) {
                var wasVisible = false
                snapshotFlow { fuelPillTooltipState.isVisible }.collect { visible ->
                    if (wasVisible && !visible) {
                        viewModel.markFuelTypePillTooltipSeen()
                    }
                    wasVisible = visible
                }
            }
            // Wrapped in an aligning Box rather than passing Modifier.align to the
            // TooltipBox: TooltipBox hands its `modifier` to the *inner* anchor wrapper
            // (material3 1.3.1 -> BasicTooltipBox's `WrappedAnchor(modifier = modifier)`,
            // sitting inside a bare `Box {}` of its own), so a BoxScope.align passed there
            // resolves against that private inner Box instead of this screen's map Box and
            // is silently dropped — leaving the pill at the map Box's default TopStart.
            Box(Modifier.align(Alignment.TopEnd)) {
                val fuelPillTooltipTail = remember { SpeechBubbleTailState() }
                TooltipBox(
                    positionProvider = rememberBelowAnchorTooltipPositionProvider(fuelPillTooltipTail),
                    tooltip = {
                        SpeechBubbleTooltip(
                            state = fuelPillTooltipState,
                            text = "Tap to cycle between petrol, diesel, and other fuel types.",
                            tail = fuelPillTooltipTail,
                        )
                    },
                    state = fuelPillTooltipState,
                ) {
                    Surface(
                        modifier = Modifier
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
                }
            }

            // Stacked bottom-left FABs: the recenter button (only shown once the user has dragged
            // away from GPS-center — standard Google Maps convention) above the always-visible
            // orientation toggle. BottomStart (not BottomEnd) — the map's own zoom controls
            // already occupy the bottom-right corner. A shared Column keeps the toggle's anchor
            // position fixed regardless of whether the recenter button happens to be showing.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.isOffGpsCenter) {
                    SmallFloatingActionButton(onClick = { viewModel.recenterOnGps() }) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Recenter on my location")
                    }
                }
                SmallFloatingActionButton(onClick = { viewModel.toggleMapOrientation() }) {
                    val isNorthUp = state.mapOrientationMode == MapOrientationMode.NORTH_UP
                    Icon(
                        if (isNorthUp) Icons.Default.Explore else Icons.Default.Navigation,
                        contentDescription = if (isNorthUp) {
                            "North up — tap to follow direction of travel"
                        } else {
                            "Following direction of travel — tap to switch to north up"
                        },
                    )
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
                        .fillMaxHeight(0.67f),
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
                                Column(Modifier.fillMaxWidth().weight(1f)) {
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
                                    // Compliance: this empty-results branch is still a price view
                                    // (Fair Use Policy Compliance, CLAUDE.md) — the populated
                                    // LazyColumn below already carries this notice as its trailing
                                    // item, this branch was missing it entirely.
                                    DataAttributionNotice()
                                }
                            } else {
                                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                                    items(listStations, key = { it.id }) { station ->
                                        StationRow(
                                            station = station,
                                            fuelType = state.selectedFuelType,
                                            userLat = state.userLat,
                                            userLng = state.userLng,
                                            // null (not-yet-loaded) is preserved distinctly from
                                            // true/false so the heart shows disabled rather than a
                                            // possibly-wrong unfavourited state.
                                            isFavourite = state.favouriteStationIds?.let { station.id in it },
                                            onToggleFavourite = { viewModel.toggleFavourite(station) },
                                            onClick = {
                                                viewModel.trackStationClick(station.id, "list")
                                                onStationClick(station.id)
                                            },
                                        )
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
private fun StationRow(
    station: StationDto,
    fuelType: String,
    userLat: Double?,
    userLng: Double?,
    isFavourite: Boolean?,
    onToggleFavourite: () -> Unit,
    onClick: () -> Unit,
) {
    val price = station.prices
        .filter { it.fuelType == fuelType }
        .minByOrNull { it.pricePence }
    val distance = station.approximateDistanceMiles(userLat, userLng)

    ListItem(
        // The IconButton in trailingContent below gets its own independent tap handling despite
        // being nested inside this clickable: Compose's clickable modifier detects taps via
        // detectTapGestures, which consumes the initial pointer-down during the Main pass — and
        // that pass runs innermost-node-first (child before parent) up the tree. So a tap that
        // lands within the IconButton's bounds is consumed by its own (inner) clickable before the
        // ListItem's (outer) one ever sees an unconsumed down event, and the outer gesture detector
        // — which by default requires an unconsumed down — never recognizes it as a click. A tap
        // anywhere else on the row simply never hits the IconButton's hit-test bounds, so only the
        // outer clickable sees it. No requestDisallowInterceptTouchEvent-style plumbing needed —
        // this falls out of the pointer input pass order for free, matching FavouritesScreen's
        // sibling (non-nested) IconButton-in-trailingContent pattern in spirit, just nested here.
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(Icons.Default.LocalGasStation, contentDescription = null)
        },
        headlineContent = { Text(station.name, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Text(
                listOfNotNull(
                    station.brand,
                    distance?.let { (miles, isApproximate) ->
                        (if (isApproximate) "~" else "") + "%.1f mi".format(miles)
                    },
                    station.postcode,
                ).joinToString(" · ")
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (price != null) {
                    Text(
                        "%.1fp".format(price.pricePence),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = fuelColor(fuelType),
                    )
                }
                IconButton(onClick = onToggleFavourite, enabled = isFavourite != null) {
                    Icon(
                        if (isFavourite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavourite == true) "Remove favourite" else "Add favourite",
                    )
                }
            }
        },
    )
    HorizontalDivider()
}

// --- Shared coach-mark tooltip machinery: below-anchor speech bubble -----------------------
// Used by both the cheapest-toggle tooltip (top bar) and the fuel-type pill tooltip (map) —
// extracted here rather than duplicated so the position-provider/shape/dismiss-detection logic
// (in particular the isVisible-transition-based seen-marking, see NearbyScreen's two
// snapshotFlow { tooltipState.isVisible } blocks) only exists once.

private val SpeechBubbleTailWidth = 16.dp
private val SpeechBubbleTailHeight = 8.dp
private val SpeechBubbleCornerRadius = 8.dp
private val SpeechBubbleAnchorGap = 4.dp

/**
 * One-field handoff from [rememberBelowAnchorTooltipPositionProvider] to
 * [rememberSpeechBubbleShape]. The position provider is the only place that knows both the
 * anchor's bounds and where the popup actually ended up after horizontal clamping, whereas the
 * tail is drawn by the shape from inside the popup's own content — so the anchor's centre,
 * expressed relative to the popup's left edge, has to travel between the two.
 *
 * Backed by snapshot state: calculatePosition runs in the layout pass, and the write there
 * invalidates the popup content (which reads it), so the shape carrying the tail's new position
 * is rebuilt on the NEXT recomposition rather than within the same frame. That gap isn't visible
 * on a first show: Popup holds its content at alpha 0 until it has both a parent position and a
 * measured content size (androidx.compose.ui:ui-android 1.7.5, AndroidPopup.android.kt's
 * `canCalculatePosition` gate), and the same layout pass that first reports that size is the one
 * that writes here — so both invalidations land in one recomposition, and the still-centred
 * frame is drawn transparent. Only a re-position while the tooltip is already on screen (the
 * anchor itself moving) would show one stale frame, which these two static coach-marks never do.
 *
 * Nor can it loop: a snapshot write doesn't notify read observers, so the position pass that
 * writes here never observes its own value, and the tail's horizontal position feeds only the
 * clip outline — never the popup's measured size, which is the sole input to what's written.
 */
private class SpeechBubbleTailState {
    /** Anchor centre X in popup-content coordinates; null until the first positioning pass. */
    var anchorCenterXPx: Float? by mutableStateOf(null)
}

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
 * occupying the content's own top [SpeechBubbleTailHeight] — so a small [gap] (not
 * [SpeechBubbleTailHeight] itself) is enough for the tail to read as touching the anchor
 * without overlapping it.
 */
@Composable
private fun rememberBelowAnchorTooltipPositionProvider(
    tail: SpeechBubbleTailState,
    gap: Dp = SpeechBubbleAnchorGap,
): PopupPositionProvider {
    val gapPx = with(LocalDensity.current) { gap.roundToPx() }
    return remember(tail, gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
                val clampedX = x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = anchorBounds.bottom + gapPx
                // Whenever clampedX != x — i.e. the anchor sits too close to a screen edge for
                // the bubble to be centred under it — the bubble's own centre is no longer the
                // anchor's centre, so a tail drawn at the bubble's centre points at empty space.
                // Hand the anchor's centre down in popup coordinates so the tail tracks the
                // anchor instead. See [SpeechBubbleTailState].
                tail.anchorCenterXPx = anchorBounds.left + anchorBounds.width / 2f - clampedX
                return IntOffset(clampedX, y)
            }
        }
    }
}

/**
 * Speech-bubble [Shape]: a rounded-rectangle body with a small triangular tail on its top edge,
 * pointing straight up at [tailCenterXPx] — the anchor's centre in the popup's own coordinates,
 * as published by [rememberBelowAnchorTooltipPositionProvider]. Null (before the popup has been
 * positioned) centres the tail on the bubble. Built
 * with [GenericShape] (`Path.(size: Size, layoutDirection: LayoutDirection) -> Unit`, per
 * androidx.compose.foundation:foundation-android 1.7.5 sources) by tracing the outline clockwise
 * from just right of the top-left corner: across the top edge, detouring up-and-back-down to
 * form the tail, on to the top-right corner, then standard quarter-circle [arcTo] calls for each
 * rounded corner.
 *
 * The tail occupies the shape's own top [SpeechBubbleTailHeight] (the rounded body starts
 * there, not at y = 0) — callers must pad their content below that so text doesn't render into
 * the notch.
 */
@Composable
private fun rememberSpeechBubbleShape(tailCenterXPx: Float?): Shape {
    val density = LocalDensity.current
    val tailWidthPx = with(density) { SpeechBubbleTailWidth.toPx() }
    val tailHeightPx = with(density) { SpeechBubbleTailHeight.toPx() }
    val cornerRadiusPx = with(density) { SpeechBubbleCornerRadius.toPx() }
    // Keyed on tailCenterXPx so a moved tail yields a NEW Shape instance: graphicsLayer caches
    // the outline it clips against per (size, shape, layoutDirection), so mutating the tail
    // position behind an unchanged shape instance would leave the stale outline on screen.
    return remember(tailWidthPx, tailHeightPx, cornerRadiusPx, tailCenterXPx) {
        GenericShape { size, _ ->
            val tailHalfWidth = tailWidthPx / 2f
            val bodyTop = tailHeightPx
            val bodyBottom = size.height
            // Guard against a content box too small for the requested radius (e.g. very short
            // text), which would otherwise produce overlapping/self-intersecting arcs.
            val r = cornerRadiusPx.coerceAtMost(minOf(size.width, bodyBottom - bodyTop) / 2f)
            // Keep the tail on the flat part of the top edge, clear of both rounded corners, so
            // it can never degenerate into a notch bitten out of a corner arc. tailMin + tailMax
            // == size.width, so the bubble's own centre — the fallback until the popup has been
            // positioned — is by construction already inside that range and needs no case of its
            // own; only a bubble too narrow for two corners plus a tail (under 32dp at the
            // current constants, so an all but empty string given the text's 24dp of horizontal
            // padding) empties the range, and there the tail has nowhere legal to go anyway.
            val tailMin = r + tailHalfWidth
            val tailMax = size.width - r - tailHalfWidth
            val desiredCenterX = tailCenterXPx ?: size.width / 2f
            val centerX =
                if (tailMin <= tailMax) desiredCenterX.coerceIn(tailMin, tailMax) else size.width / 2f

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
private fun SpeechBubbleTooltip(state: TooltipState, text: String, tail: SpeechBubbleTailState) {
    Surface(
        shape = rememberSpeechBubbleShape(tailCenterXPx = tail.anchorCenterXPx),
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
                top = SpeechBubbleTailHeight + 8.dp,
            ),
        )
    }
}
