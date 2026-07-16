package uk.co.fuelprices.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

data class MapMarker(
    val lat: Double,
    val lng: Double,
    val title: String,
    val snippet: String? = null,
    val id: Int? = null,
    val color: Color? = null,
)

/** Google Maps Compose wrapper. Requires MAPS_API_KEY set in local.properties. */
@Composable
fun FuelMapView(
    modifier: Modifier = Modifier,
    centerLat: Double = 51.5074,
    centerLng: Double = -0.1278,
    zoomLevel: Float = 12f,
    markers: List<MapMarker> = emptyList(),
    onMarkerClick: ((Int) -> Unit)? = null,
) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(centerLat, centerLng), zoomLevel)
    }

    LaunchedEffect(centerLat, centerLng, zoomLevel) {
        cameraPositionState.position = CameraPosition.fromLatLngZoom(LatLng(centerLat, centerLng), zoomLevel)
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
    ) {
        markers.forEach { m ->
            // Rendered as a small price chip instead of a default pin, so the price is visible
            // directly on the map without needing to tap through to an info window.
            MarkerComposable(
                m.id ?: -1,
                m.snippet ?: "",
                state = MarkerState(position = LatLng(m.lat, m.lng)),
                title = m.title,
                onClick = {
                    if (onMarkerClick != null && m.id != null) {
                        onMarkerClick(m.id)
                        true
                    } else {
                        false
                    }
                },
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = m.color ?: MaterialTheme.colorScheme.primary,
                    border = BorderStroke(1.dp, Color.White),
                    shadowElevation = 3.dp,
                ) {
                    Text(
                        m.snippet ?: "?",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}
