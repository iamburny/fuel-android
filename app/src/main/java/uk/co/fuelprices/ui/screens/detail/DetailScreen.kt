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
import uk.co.fuelprices.data.api.*
import uk.co.fuelprices.ui.components.BarChart
import uk.co.fuelprices.ui.components.FuelMapView
import uk.co.fuelprices.ui.components.MapMarker
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

                station.distanceMiles?.let {
                    Text("%.1f miles away".format(it), style = MaterialTheme.typography.bodySmall)
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
                ListItem(
                    headlineContent = {
                        Text(fuelLabel(price.fuelType), fontWeight = FontWeight.Medium)
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
                            color = FuelTypes.color(price.fuelType),
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
                                bh.openTime != null && bh.closeTime != null -> "${bh.openTime} – ${bh.closeTime}"
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

            // Price history bar chart
            if (state.priceHistory.isNotEmpty()) {
                Text(
                    "Price History (30 days)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                )
                BarChart(
                    values = state.priceHistory.map { it.pricePence },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                // Date range + price range labels
                val minPrice = state.priceHistory.minOf { it.pricePence }
                val maxPrice = state.priceHistory.maxOf { it.pricePence }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        state.priceHistory.first().reportedAt.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        state.priceHistory.last().reportedAt.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    "%.1fp – %.1fp".format(minPrice, maxPrice),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            HorizontalDivider()

            // Compliance: discrepancy report link (required by Fair Use Policy).
            // The specific /report-discrepancy path 404s (confirmed both here and as the
            // backend's own configured default) — points at the working base domain until
            // there's a real report page to link to.
            TextButton(
                onClick = {
                    val url = "https://www.fuel-finder.service.gov.uk/"
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
                    hours.open != null && hours.close != null -> "${hours.open} – ${hours.close}"
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
