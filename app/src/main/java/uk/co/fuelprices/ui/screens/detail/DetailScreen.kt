package uk.co.fuelprices.ui.screens.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.horizontalScroll
import uk.co.fuelprices.data.api.*
import uk.co.fuelprices.ui.components.DISCREPANCY_REPORT_URL
import uk.co.fuelprices.ui.components.DataAttributionNotice
import uk.co.fuelprices.ui.components.PriceLineChart
import uk.co.fuelprices.ui.components.FuelMapView
import uk.co.fuelprices.ui.components.MapMarker
import uk.co.fuelprices.ui.theme.fuelColor
import uk.co.fuelprices.ui.theme.fuelLabel
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import uk.co.fuelprices.data.api.RatingSummaryDto
import uk.co.fuelprices.ui.components.AccuracyWarningAmber
import java.util.Locale
import uk.co.fuelprices.ui.theme.LocalIsDarkTheme
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.time.DayOfWeek
import java.time.LocalDate

/** How much of the map shows below the floating top bar. */
private val VISIBLE_MAP_HEIGHT = 220.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
    // The same instance StationRatingsSection resolves: both are scoped to this nav entry.
    ratingsViewModel: StationRatingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val ratingsState by ratingsViewModel.state.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    // The bar floats over the map on a faint fade, and turns solid once the map has scrolled up
    // under it, so its buttons never sit bare over the text below.
    val overMap by remember {
        derivedStateOf { scrollState.value < with(density) { VISIBLE_MAP_HEIGHT.toPx() } }
    }
    val barColor by animateColorAsState(
        if (overMap) Color.Transparent else MaterialTheme.colorScheme.surface,
        label = "detailBarColor",
    )
    // A very faint fade gives the floating bar some depth; legibility comes from each button's own
    // round backing, which reads over any map tile where a fade alone can't.
    val fade = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent))
    val barIconColor = MaterialTheme.colorScheme.onSurface
    // Always the same padding and shape, so the buttons don't move: only the backing's opacity
    // changes as the bar turns solid.
    val backingAlpha by animateFloatAsState(if (overMap) 0.92f else 0f, label = "detailBackingAlpha")
    val backing = Modifier
        .padding(4.dp)
        .background(MaterialTheme.colorScheme.surface.copy(alpha = backingAlpha), CircleShape)

    LaunchedEffect(state.signInRequested) {
        if (state.signInRequested) {
            viewModel.consumeSignInRequest()
            onSignIn()
        }
    }
    LifecycleResumeEffect(Unit) {
        viewModel.onResumed()
        onPauseOrDispose {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // No title: the bar has no room for a forecourt name beside its actions, so the name
                // is the heading under the map instead.
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = barColor,
                    navigationIconContentColor = barIconColor,
                    actionIconContentColor = barIconColor,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = backing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (ratingsState.enabled && state.station != null) {
                        RatingBadgeButton(
                            summary = state.station?.ratingSummary,
                            contentColor = barIconColor,
                            backing = backing,
                            onClick = { ratingsViewModel.onRateClicked() },
                        )
                    }
                    if (state.isFavourite) {
                        IconButton(
                            onClick = { viewModel.toggleNotify() },
                            modifier = backing,
                            enabled = !state.pendingFavouriteToggle,
                        ) {
                            Icon(
                                if (state.notifyOnDrop) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                                if (state.notifyOnDrop) "Mute price-drop alerts" else "Enable price-drop alerts",
                            )
                        }
                    }
                    IconButton(
                        onClick = { viewModel.toggleFavourite() },
                        modifier = backing,
                        // Signed out, this routes to sign-in and saves the favourite on return.
                        // Also gated on !isLoading: selectedFuelType is null until load()
                        // completes, so a favourite tapped before then would fall back to the
                        // "E10" default in toggleFavourite() rather than the real active filter.
                        enabled = !state.pendingFavouriteToggle && !state.isLoading,
                    ) {
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

        // No top padding: the map runs up behind the status bar and the floating top bar, and is
        // taller by exactly the space they cover.
        Column(
            Modifier
                .padding(bottom = padding.calculateBottomPadding())
                .verticalScroll(scrollState)
        ) {
            // Mini map
            Box {
                FuelMapView(
                    modifier = Modifier.fillMaxWidth().height(padding.calculateTopPadding() + VISIBLE_MAP_HEIGHT),
                    centerLat = station.latitude,
                    centerLng = station.longitude,
                    zoomLevel = 15f,
                    markers = listOf(MapMarker(station.latitude, station.longitude, station.name)),
                )
                // A faint fade behind the floating bar, so its buttons stay legible over busy map
                // detail.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(padding.calculateTopPadding() + 16.dp)
                        .background(fade),
                )
            }

            // Station info
            Column(Modifier.padding(16.dp)) {
                Text(
                    station.name,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(4.dp))
                station.brand?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(2.dp))
                }

                // Status badges
                val badges = buildList {
                    if (station.temporaryClosure) add("Temporarily Closed" to MaterialTheme.colorScheme.error)
                    if (station.isMotorway) add("Motorway Services" to Color(0xFF3B82F6))
                    if (station.isSupermarket) add("Supermarket" to Color(0xFF22C55E))
                }
                if (badges.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        badges.forEach { (label, color) ->
                            SuggestionChip(
                                onClick = {},
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = color.copy(alpha = 0.15f),
                                    labelColor = color,
                                ),
                            )
                        }
                    }
                }

                val address = listOfNotNull(station.addressLine1, station.addressLine2, station.town, station.postcode)
                    .joinToString(", ")
                if (address.isNotBlank()) {
                    Text(address, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                }

                // station.distanceMiles is always null here — the station-by-id endpoint doesn't
                // return it (only /nearby and /cheapest do). state.distanceMiles is computed
                // client-side against the current location instead (see DetailViewModel).
                state.distanceMiles?.let {
                    Text("%.1f miles away".format(it), style = MaterialTheme.typography.bodySmall)
                }
                state.driveCostPounds?.let {
                    Text(
                        "Est. £%.2f in fuel to get here".format(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // Phone
                station.phone?.let { phone ->
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(Icons.Default.Phone, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(phone)
                    }
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

            // Flagged prices go last so the top row is always a usable price.
            station.prices.sortedWith(compareBy({ it.isFlagged }, { it.pricePence })).forEach { price ->
                val warning = price.priceWarning
                // A caveated price is excluded from the national figures, so a delta against
                // them would be meaningless.
                val nationalAvgPence = if (warning != null) null else state.nationalAverages
                    .firstOrNull { it.fuelType == price.fuelType }?.avgPricePence
                ListItem(
                    headlineContent = {
                        Text(fuelLabel(price.fuelType), fontWeight = FontWeight.Medium)
                    },
                    supportingContent = {
                        Column {
                            // Compliance: show original timestamp unmodified
                            Text("Reported: ${price.reportedAt}")
                            if (warning != null) {
                                PriceWarningNotice(warning)
                            }
                            if (nationalAvgPence != null) {
                                val delta = price.pricePence - nationalAvgPence
                                Text(
                                    "%+.1fp vs national avg".format(delta),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (delta <= 0) Color(0xFF22C55E) else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                    trailingContent = {
                        Text(
                            "%.1fp".format(price.pricePence),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = fuelColor(price.fuelType),
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

            // Amenities
            val amenityItems = station.amenities.toAmenitiesDisplayList()
            if (amenityItems.isNotEmpty()) {
                Text(
                    "Amenities",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                )
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    amenityItems.forEach { label ->
                        AssistChip(
                            onClick = {},
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
                HorizontalDivider()
            }

            // Opening hours
            station.openingHours?.usualDays?.let { days ->
                Text(
                    "Opening Hours",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                )
                OpeningHoursTable(days)

                station.openingHours?.bankHolidays?.let { holidays ->
                    if (holidays.isNotEmpty()) {
                        Text(
                            "Bank Holidays",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 4.dp),
                        )
                        holidays.forEach { bh ->
                            val hours = when {
                                bh.is24Hours == true -> "24 hours"
                                bh.openTime != null && bh.closeTime != null ->
                                    "${formatOpeningTime(bh.openTime)} – ${formatOpeningTime(bh.closeTime)}"
                                else -> "Closed"
                            }
                            ListItem(
                                headlineContent = { Text(bh.type ?: "Bank Holiday") },
                                trailingContent = { Text(hours) },
                            )
                        }
                    }
                }

                HorizontalDivider()
            }

            // Price history line chart
            if (station.prices.isNotEmpty()) {
                Text(
                    "Price History (30 days)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                )

                // Scoped to what this station actually sells — showing all 6 unconditionally let
                // you select a fuel type with no data at all, landing on the chart's empty state.
                val availableFuelTypes = FuelTypes.ALL.filter { type -> station.prices.any { it.fuelType == type } }
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    availableFuelTypes.forEach { type ->
                        FilterChip(
                            selected = state.selectedFuelType == type,
                            onClick = { viewModel.setFuelType(type) },
                            label = { Text(fuelLabel(type)) },
                            colors = FilterChipDefaults.filterChipColors(
                                // Raw palette colour, not the theme-aware fuelColor() used for the
                                // line chart below — this fill always has a fixed white label on
                                // top, and fuelColor()'s dark-mode-lightened diesel values would
                                // wash out against that fixed white text instead of the near-black
                                // pump colour they're meant to replace only when used as text.
                                selectedContainerColor = FuelTypes.color(type),
                                selectedLabelColor = Color.White,
                            ),
                        )
                    }
                }

                if (state.priceHistory.isNotEmpty()) {
                    PriceLineChart(
                        values = state.priceHistory.map { it.pricePence },
                        dates = state.priceHistory.map { it.reportedAt },
                        lineColor = fuelColor(state.selectedFuelType ?: "E10"),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    Text(
                        "No price history available for this fuel type.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            HorizontalDivider()

            // Driver ratings sit in their own section after all the Fuel Finder data, so they're
            // never read as part of the published prices.
            StationRatingsSection(station = station, onSignIn = onSignIn, viewModel = ratingsViewModel)

            // Compliance: discrepancy report link (required by Fair Use Policy) plus a real,
            // tappable link to the official gov.uk source (required by the Misleading Claims
            // policy — a plain-text mention of "gov.uk/..." is not an accessible link).
            DataAttributionNotice(modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Strips a trailing :SS from an "HH:MM:SS" opening-hours time for display; free text some
 *  stations report instead of a real time (e.g. "24 hrs") is returned unchanged. */
private fun formatOpeningTime(value: String?): String {
    if (value == null) return ""
    return if (Regex("""^\d{1,2}:\d{2}:\d{2}$""").matches(value)) value.dropLast(3) else value
}

/** Amber, in the same chip style as the station status badges above the address. */
private val PriceWarningAmber = Color(0xFFF59E0B)

/** Caveat for a price the backend has flagged: a badge, why it was flagged, and a shortcut to
 *  the official discrepancy report. The price itself is still shown unmodified alongside. */
@Composable
private fun PriceWarningNotice(warning: PriceWarning) {
    val context = LocalContext.current
    Column(Modifier.padding(top = 4.dp)) {
        SuggestionChip(
            onClick = {},
            icon = { Icon(Icons.Default.Warning, null, Modifier.size(14.dp)) },
            label = { Text(warning.badgeLabel, style = MaterialTheme.typography.labelSmall) },
            colors = SuggestionChipDefaults.suggestionChipColors(
                containerColor = PriceWarningAmber.copy(alpha = 0.15f),
                // Amber text on its own tint is too faint to read; the icon carries the colour.
                labelColor = MaterialTheme.colorScheme.onSurface,
                iconContentColor = PriceWarningAmber,
            ),
        )
        Text(warning.explanation, style = MaterialTheme.typography.bodySmall)
        TextButton(
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DISCREPANCY_REPORT_URL)))
            },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text("Report a price discrepancy", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun OpeningHoursTable(days: UsualDaysDto) {
    val today = LocalDate.now().dayOfWeek
    val dayMap = mapOf(
        "Monday" to DayOfWeek.MONDAY, "Tuesday" to DayOfWeek.TUESDAY,
        "Wednesday" to DayOfWeek.WEDNESDAY, "Thursday" to DayOfWeek.THURSDAY,
        "Friday" to DayOfWeek.FRIDAY, "Saturday" to DayOfWeek.SATURDAY,
        "Sunday" to DayOfWeek.SUNDAY,
    )

    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        days.asList().forEach { (dayName, hours) ->
            val isToday = dayMap[dayName] == today
            val bg = if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(vertical = 6.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    dayName,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium,
                )
                val hoursText = when {
                    hours == null -> "—"
                    hours.is24Hours == true -> "24 hours"
                    hours.open != null && hours.close != null ->
                        "${formatOpeningTime(hours.open)} – ${formatOpeningTime(hours.close)}"
                    else -> "—"
                }
                Text(
                    hoursText,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/**
 * The station's driver score beside the favourite heart, and the quickest way to rate it: the
 * average with a filled star once enough drivers have rated it, an outlined star until then.
 */
@Composable
private fun RatingBadgeButton(
    summary: RatingSummaryDto?,
    contentColor: Color,
    backing: Modifier,
    onClick: () -> Unit,
) {
    val label = if (summary != null) {
        String.format(Locale.UK, "Rated %.1f out of 5 by %d drivers. Rate this station", summary.avgStars, summary.raterCount)
    } else {
        "Rate this station"
    }
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp),
        // widthIn replaces TextButton's own wider minimum, so the outlined star sits like the icons.
        modifier = Modifier
            .then(backing)
            .widthIn(min = 48.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Icon(
            if (summary != null) Icons.Default.Star else Icons.Default.StarBorder,
            contentDescription = null,
            // A darker amber on light bars, where the warning amber is too faint for an icon.
            tint = when {
                summary == null -> contentColor
                LocalIsDarkTheme.current -> AccuracyWarningAmber
                else -> Color(0xFFB45309)
            },
        )
        if (summary != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                String.format(Locale.UK, "%.1f", summary.avgStars),
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
            )
        }
    }
}
