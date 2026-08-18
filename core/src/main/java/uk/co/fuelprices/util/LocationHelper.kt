package uk.co.fuelprices.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LocationHelper"

/**
 * Where the map centers when GPS is unavailable — permission denied, disabled, or a fix just
 * hasn't arrived yet — instead of leaving the screen blank or crashing. Shared by every caller
 * that needs a location fallback so there's a single place to change it.
 */
object DefaultLocation {
    const val LAT = 51.75357815837036
    const val LNG = -1.2571484832548643
}

@Singleton
class LocationHelper @Inject constructor(@ApplicationContext private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    // MainActivity's permission-request callback fires this exactly when the runtime dialog is
    // answered — deterministic, unlike relying on Activity/lifecycle resume timing around the
    // dialog (which doesn't reliably re-trigger a fetch on every device/API level).
    private val _permissionGranted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val permissionGranted: SharedFlow<Unit> = _permissionGranted.asSharedFlow()

    fun notifyPermissionResult(granted: Boolean) {
        if (granted) _permissionGranted.tryEmit(Unit)
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    suspend fun getCurrentLocation(): Location? {
        if (!hasPermission()) return null

        // getCurrentLocation() is a live one-shot request — on emulators it frequently returns
        // null or times out even when the emulator's GPS is genuinely working (confirmed via
        // Google Maps), because the simulated GPS doesn't always complete a fresh-fix cycle in
        // time. getLastLocation() just reads the cached fix and is far more reliable there,
        // while still being fine on real devices (the cache is normally seconds old at most).
        val current = try {
            client.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token,
            ).await()
        } catch (e: Exception) {
            Log.w(TAG, "getCurrentLocation() failed, falling back to last known location", e)
            null
        }
        if (current != null) return current

        return try {
            client.lastLocation.await()
        } catch (e: Exception) {
            Log.w(TAG, "lastLocation also failed", e)
            null
        }
    }

    /**
     * A continuous stream of location fixes. Unlike [getCurrentLocation] (a one-shot that can fall
     * back to a stale cached fix), this keeps emitting as the device moves, so the caller's map can
     * follow the user in real time rather than sticking to the first fix until the process is
     * killed. Emits nothing and completes immediately if permission isn't granted; the underlying
     * request is removed when the collector is cancelled (via [awaitClose]).
     */
    @Suppress("MissingPermission")
    fun locationUpdates(): Flow<Location> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
