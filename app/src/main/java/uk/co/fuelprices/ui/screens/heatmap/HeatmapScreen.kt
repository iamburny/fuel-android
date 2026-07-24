package uk.co.fuelprices.ui.screens.heatmap

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import uk.co.fuelprices.data.api.FuelTypes
import uk.co.fuelprices.data.api.HeatmapCell
import uk.co.fuelprices.ui.theme.fuelColor
import uk.co.fuelprices.ui.theme.fuelLabel
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeatmapScreen(viewModel: HeatmapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var selected by remember { mutableStateOf<HeatmapCell?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Price Heat Map") }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Fuel-type selector
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FuelTypes.ALL.forEach { code ->
                    val isSelected = code == state.fuelType
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selected = null
                            viewModel.setFuelType(code)
                        },
                        label = { Text(fuelLabel(code)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = fuelColor(code).copy(alpha = 0.20f),
                        ),
                    )
                }
            }

            Text(
                buildString {
                    append("National average: ")
                    append("%.1fp".format(state.nationalAvg))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val cameraPositionState = rememberCameraPositionState {
                    position = CameraPosition.fromLatLngZoom(LatLng(54.5, -2.5), 5.2f)
                }
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(isMyLocationEnabled = false),
                    uiSettings = MapUiSettings(zoomControlsEnabled = true, myLocationButtonEnabled = false),
                ) {
                    state.cells.forEach { cell ->
                        val color = heatColor(cell.deltaPence, state.maxAbs)
                        Circle(
                            center = LatLng(cell.latitude, cell.longitude),
                            radius = radiusMetres(cell.stationCount),
                            fillColor = color.copy(alpha = 0.5f),
                            strokeColor = color.copy(alpha = 0.85f),
                            strokeWidth = 1.5f,
                            clickable = true,
                            onClick = { selected = cell },
                        )
                    }
                }

                if (state.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                if (state.error != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            state.error!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                // Tapped-cell detail
                selected?.let { cell ->
                    val sign = if (cell.deltaPence > 0) "+" else ""
                    Surface(
                        tonalElevation = 3.dp,
                        shadowElevation = 3.dp,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(12.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(
                                "%.1fp avg".format(cell.avgPricePence),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "$sign%.1fp vs national ($sign%.1f%%)".format(cell.deltaPence, cell.deltaPercent),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Text(
                                "${cell.stationCount} station${if (cell.stationCount == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }

                // Legend
                if (!state.loading && state.error == null) {
                    HeatLegend(
                        maxAbs = state.maxAbs,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp),
                    )
                }
            }
        }
    }
}

/** Circle radius in metres scales with the cell's station count so busier areas read as larger. */
private fun radiusMetres(count: Int): Double =
    (sqrt(count.toDouble()) * 3000).coerceIn(6000.0, 45000.0)

@Composable
private fun HeatLegend(maxAbs: Double, modifier: Modifier = Modifier) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "Price vs national",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(4.dp))
            // Continuous green→amber→red gradient bar.
            Box(
                Modifier
                    .width(140.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                heatColor(-maxAbs, maxAbs),
                                heatColor(0.0, maxAbs),
                                heatColor(maxAbs, maxAbs),
                            )
                        )
                    )
            )
            Spacer(Modifier.height(2.dp))
            Row(
                Modifier.width(140.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("−%.1fp".format(maxAbs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text("+%.1fp".format(maxAbs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
