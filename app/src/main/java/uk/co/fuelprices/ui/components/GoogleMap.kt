package uk.co.fuelprices.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
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
    // Bump this (e.g. an incrementing Int) whenever the caller wants a one-off camera jump to
    // centerLat/centerLng — left null (the default), the camera is never forced to move, so it
    // doesn't fight the user dragging the map. Callers that don't need this (e.g. DetailScreen's
    // static single-marker map) can ignore it entirely; a null key only recenters once, on first
    // composition, which is harmless for a map that's never expected to move afterwards.
    recenterKey: Any? = null,
    // Fires once a drag ends, with the map's new visible bounds — used to load pins for whatever
    // area the user panned to. Not called for the initial camera placement, only genuine drags.
    onCameraIdle: ((LatLngBounds) -> Unit)? = null,
    // Enables the Google Maps live "my location" blue dot. Requires location permission to be
    // granted (the caller's responsibility). The SDK's own recenter button is hidden — callers
    // that want one (Nearby) provide their own FAB. Left false for static maps (e.g. Detail).
    showMyLocation: Boolean = false,
) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(centerLat, centerLng), zoomLevel)
    }

    // isMoving starts false at initial composition (before any gesture), so a plain "fire when
    // not moving" check would invoke onCameraIdle immediately on first load. Only fire on a
    // genuine true → false transition, i.e. after the map actually started moving at least once.
    var hasStartedMoving by remember { mutableStateOf(false) }

    // A non-animated `position =` assignment still fires the underlying Maps SDK's
    // move-started/idle listeners (same as a user drag), so a recenter would otherwise
    // immediately re-trigger onCameraIdle for the GPS area and undo itself. Only guard against
    // that for recenters that happen after real dragging has started — the very first recenter
    // (the initial GPS fix, before hasStartedMoving is ever true) doesn't need it, since the
    // hasStartedMoving check below already suppresses idle events before that point anyway; if
    // it were set unconditionally here, that flag would sit unconsumed until the user's first
    // real drag and incorrectly swallow that legitimate onCameraIdle instead.
    var suppressNextIdle by remember { mutableStateOf(false) }
    LaunchedEffect(recenterKey) {
        if (recenterKey != null && hasStartedMoving) suppressNextIdle = true
        cameraPositionState.position = CameraPosition.fromLatLngZoom(LatLng(centerLat, centerLng), zoomLevel)
    }

    LaunchedEffect(cameraPositionState.isMoving) {
        if (cameraPositionState.isMoving) {
            hasStartedMoving = true
        } else if (hasStartedMoving) {
            if (suppressNextIdle) {
                suppressNextIdle = false
            } else {
                val bounds = cameraPositionState.projection?.visibleRegion?.latLngBounds
                if (bounds != null) onCameraIdle?.invoke(bounds)
            }
        }
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        properties = MapProperties(isMyLocationEnabled = showMyLocation),
        uiSettings = MapUiSettings(myLocationButtonEnabled = false),
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
