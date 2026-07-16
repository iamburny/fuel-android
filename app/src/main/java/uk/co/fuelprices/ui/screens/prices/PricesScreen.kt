package uk.co.fuelprices.ui.screens.prices

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import uk.co.fuelprices.data.api.FuelTypes
import uk.co.fuelprices.data.api.NationalAverageDto
import uk.co.fuelprices.data.api.TrendPoint
import uk.co.fuelprices.ui.components.LineChart
import uk.co.fuelprices.ui.theme.fuelLabel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PricesScreen(
    viewModel: PricesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

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
                LineChart(
                    values = state.trend.map { it.avgPricePence },
                    lineColor = FuelTypes.color(state.selectedFuelType),
                    minValue = state.trend.minOf { it.minPricePence },
                    maxValue = state.trend.maxOf { it.maxPricePence },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )

                // Date labels
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        state.trend.first().date.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        state.trend.last().date.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                val minTrend = state.trend.minOf { it.minPricePence }
                val maxTrend = state.trend.maxOf { it.maxPricePence }
                Text(
                    "Range: %.1fp – %.1fp".format(minTrend, maxTrend),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()

            // Compliance. Note: the backend's own discrepancyReportUrl value is the same
            // /report-discrepancy path that 404s (confirmed directly) — using the working base
            // domain instead until there's a real report page to link to.
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

            Text(
                state.dataNotice.ifBlank {
                    "Prices sourced from the UK Government Fuel Finder scheme under the Open Government Licence. Data is presented without modification."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 16.dp),
            )
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
    val color = FuelTypes.color(avg.fuelType)
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
                color = color,
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

