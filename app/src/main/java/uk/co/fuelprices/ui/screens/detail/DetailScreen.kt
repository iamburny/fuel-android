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
import uk.co.fuelprices.ui.components.DataAttributionNotice
import uk.co.fuelprices.ui.components.PriceLineChart
import uk.co.fuelprices.ui.components.FuelMapView
import uk.co.fuelprices.ui.components.MapMarker
import uk.co.fuelprices.ui.theme.fuelColor
import uk.co.fuelprices.ui.theme.fuelLabel
import java.time.DayOfWeek
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
                    if (state.isFavourite) {
                        IconButton(
                            onClick = { viewModel.toggleNotify() },
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

        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Mini map
            FuelMapView(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                centerLat = station.latitude,
                centerLng = station.longitude,
                zoomLevel = 15f,
                markers = listOf(MapMarker(station.latitude, station.longitude, station.name)),
            )

            // Station info
            Column(Modifier.padding(16.dp)) {
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

            station.prices.sortedBy { it.pricePence }.forEach { price ->
                val nationalAvgPence = state.nationalAverages
                    .firstOrNull { it.fuelType == price.fuelType }?.avgPricePence
                ListItem(
                    headlineContent = {
                        Text(fuelLabel(price.fuelType), fontWeight = FontWeight.Medium)
                    },
                    supportingContent = {
                        Column {
                            // Compliance: show original timestamp unmodified
                            Text("Reported: ${price.reportedAt}")
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
