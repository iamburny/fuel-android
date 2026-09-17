package uk.co.fuelprices.ui.screens.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.ui.theme.fuelLabel

/**
 * Modal overlay listing whatever's currently pinned on the map (passed in already sorted
 * cheapest-first for [fuelType] by [NearbyUiState.cheapestSortedStations]), re-using [StationRow]
 * from NearbyScreen.kt. Purely a client-side re-sort of already-loaded data — no network call, and
 * no separate dataset from the map's own pins, so the list can never show a station that isn't
 * actually pinned right now.
 *
 * Tapping a row calls [onStationSelected] (dismiss + pan/zoom the map camera), never navigates to
 * Detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheapestSheet(
    stations: List<StationDto>,
    fuelType: String,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onStationSelected: (StationDto) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Default drag handle left in place — no override needed.
        sheetState = rememberModalBottomSheetState(),
    ) {
        Text(
            "Cheapest ${fuelLabel(fuelType)}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { heading() },
        )

        when {
            // Nothing pinned on the map yet (first launch, still fetching) — distinct from the
            // "loaded but none match" state below so the user isn't told "no stations" when the
            // real reason is just that the initial fetch hasn't landed.
            isLoading && stations.isEmpty() -> {
                Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            stations.isEmpty() -> {
                Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No nearby stations currently report a ${fuelLabel(fuelType)} price.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            else -> {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(stations, key = { it.id }) { station ->
                        StationRow(station, fuelType) {
                            onStationSelected(station)
                        }
                    }
                }
            }
        }
    }
}
