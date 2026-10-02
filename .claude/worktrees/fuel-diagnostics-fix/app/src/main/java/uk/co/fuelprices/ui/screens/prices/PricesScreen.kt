package uk.co.fuelprices.ui.screens.prices

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import uk.co.fuelprices.data.api.FuelTypes
import uk.co.fuelprices.data.api.NationalAverageDto
import uk.co.fuelprices.data.api.TrendPoint
import uk.co.fuelprices.ui.components.DEFAULT_DATA_NOTICE
import uk.co.fuelprices.ui.components.DataAttributionNotice
import uk.co.fuelprices.ui.components.PriceLineChart
import uk.co.fuelprices.ui.theme.fuelColor
import uk.co.fuelprices.ui.theme.fuelLabel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PricesScreen(
    onOpenHeatmap: () -> Unit = {},
    viewModel: PricesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Prices & Trends") })
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (state.error != null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Error: ${state.error}", color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }

        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            HeatmapLinkCard(
                onClick = onOpenHeatmap,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp, 12.dp, 16.dp, 4.dp),
            )

            // Selected fuel type stats
            val selected = state.averages.find { it.fuelType == state.selectedFuelType }
            if (selected != null) {
                Text(
                    fuelLabel(state.selectedFuelType),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard("Average", "%.1fp".format(selected.avgPricePence), Modifier.weight(1f))
                    StatCard("Cheapest", "%.1fp".format(selected.minPricePence), Modifier.weight(1f))
                    StatCard("Highest", "%.1fp".format(selected.maxPricePence), Modifier.weight(1f))
                    StatCard("Stations", "${selected.stationCount}", Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()

            // All fuel types comparison
            Text(
                "All Fuel Types",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
            )

            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.averages.forEach { avg ->
                    FuelTypeCard(
                        avg = avg,
                        isSelected = avg.fuelType == state.selectedFuelType,
                        onClick = { viewModel.setFuelType(avg.fuelType) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()

            // Trend chart
            Text(
                "Price Trend",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
            )

            // Day selector
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(7, 30, 90).forEach { days ->
                    FilterChip(
                        selected = state.selectedDays == days,
                        onClick = { viewModel.setDays(days) },
                        label = { Text("${days}d") },
                    )
                }
            }

            if (state.trend.isNotEmpty()) {
                // Scale to the plotted average line's own range (not the daily all-station
                // min/max, which squashed the line flat near the bottom).
                PriceLineChart(
                    values = state.trend.map { it.avgPricePence },
                    dates = state.trend.map { it.date },
                    lineColor = fuelColor(state.selectedFuelType),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()

            // Compliance: discrepancy report link, plus a real, tappable link to the official
            // gov.uk source (required by the Misleading Claims policy — a plain-text mention of
            // "gov.uk/..." is not an accessible link). The notice text normally comes from the
            // API's data_notice field (src/services/compliance.ts) — this fallback matches it
            // in case that field is ever blank.
            DataAttributionNotice(
                modifier = Modifier.padding(top = 8.dp),
                noticeText = state.dataNotice.ifBlank { DEFAULT_DATA_NOTICE },
            )
        }
    }
}

@Composable
private fun HeatmapLinkCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Row(
            Modifier
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF16A34A), Color(0xFFEAB308), Color(0xFFDC2626))
                    )
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.LocalFireDepartment, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "UK Price Heat Map",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    "See how prices compare to the national average, by area",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(
            Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun FuelTypeCard(avg: NationalAverageDto, isSelected: Boolean, onClick: () -> Unit) {
    // Raw palette color for the subtle selected-card tint; theme-aware color for the label text
    // so Diesel/Super Diesel labels stay legible in dark mode.
    val color = FuelTypes.color(avg.fuelType)
    val labelColor = fuelColor(avg.fuelType)
    Card(
        modifier = Modifier
            .width(150.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) color.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                fuelLabel(avg.fuelType),
                style = MaterialTheme.typography.labelLarge,
                color = labelColor,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "%.1fp".format(avg.avgPricePence),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "%.1f – %.1f".format(avg.minPricePence, avg.maxPricePence),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

