package uk.co.fuelprices.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

data class MapMarker(
    val lat: Double,
    val lng: Double,
    val title: String,
    val snippet: String? = null,
    val id: Int? = null,
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
            Marker(
                state = MarkerState(position = LatLng(m.lat, m.lng)),
                title = m.title,
                snippet = m.snippet,
                onClick = {
                    if (onMarkerClick != null && m.id != null) {
                        onMarkerClick(m.id)
                        true
                    } else {
                        false
                    }
                },
            )
        }
    }
}
