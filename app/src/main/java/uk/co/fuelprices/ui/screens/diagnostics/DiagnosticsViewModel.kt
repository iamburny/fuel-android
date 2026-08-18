package uk.co.fuelprices.ui.screens.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.common.GoogleApiAvailability
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.fuelprices.BuildConfig
import uk.co.fuelprices.util.LocationHelper
import java.security.MessageDigest
import javax.inject.Inject

data class DiagnosticsUiState(
    val lines: List<Pair<String, String>> = emptyList(),
    val isLoading: Boolean = true,
) {
    val reportText: String
        get() = lines.joinToString("\n") { (label, value) -> "$label: $value" }
}

/**
 * Read-only device/environment snapshot for support triage — reachable via a hidden gesture on
 * Preferences (see `PreferencesScreen`'s version-tap footer) rather than a normal nav destination,
 * since it's a troubleshooting tool for talking a reporting user through a bug, not a feature.
 * Deliberately contains no personal data — the Maps API key and signing certificate are reported
 * as non-reversible fingerprints, never the raw values, so this is safe to screenshot or paste
 * into a support channel.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationHelper: LocationHelper,
) : ViewModel() {

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init {
        _state.value = DiagnosticsUiState(lines = staticLines(), isLoading = true)
        viewModelScope.launch {
            // Best-effort — reuses the same helper the Nearby map does, so this reports whatever
            // that screen would actually see, not an idealized fresh fix.
            val location = try { locationHelper.getCurrentLocation() } catch (_: Exception) { null }
            val locationLine = location?.let { "%.5f, %.5f".format(it.latitude, it.longitude) }
                ?: "unavailable"
            _state.value = _state.value.copy(
                lines = _state.value.lines + ("Last known location" to locationLine),
                isLoading = false,
            )
        }
    }

    private fun staticLines(): List<Pair<String, String>> = buildList {
        add("App version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        add("Build type" to BuildConfig.BUILD_TYPE)
        add("Package" to context.packageName)
        add("Device" to "${Build.MANUFACTURER} ${Build.MODEL}")
        add("Android version" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        add("Google Play services" to playServicesStatus())
        add("Location permission" to locationPermissionStatus())
        add("Device location services" to if (isSystemLocationEnabled()) "on" else "off")
        add("Network" to networkStatus())
        add("Maps API key configured" to mapsApiKeyStatus())
        add("App signing certificate (SHA-1)" to signingCertificateSha1())
    }

    private fun playServicesStatus(): String {
        val availability = GoogleApiAvailability.getInstance()
        val code = availability.isGooglePlayServicesAvailable(context)
        val versionName = try {
            context.packageManager.getPackageInfo("com.google.android.gms", 0).versionName
        } catch (_: Exception) {
            "unknown"
        }
        return "${availability.getErrorString(code)} (v$versionName)"
    }

    private fun locationPermissionStatus(): String {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return when {
            fine -> "fine granted"
            coarse -> "coarse granted"
            else -> "denied"
        }
    }

    private fun isSystemLocationEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    private fun networkStatus(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"
        val network = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return "$transport${if (validated) "" else " (not validated)"}"
    }

    /** Never surfaces the actual key — just whether one resolved and a short, non-reversible
     *  fingerprint so support can confirm which key variant is loaded without the secret itself. */
    private fun mapsApiKeyStatus(): String {
        val key = try {
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString("com.google.android.geo.API_KEY")
        } catch (_: Exception) {
            null
        }
        if (key.isNullOrBlank()) return "missing"
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(8)
        return "present (fingerprint $fingerprint)"
    }

    /** The certificate this exact running build was signed with — compare against what's
     *  registered as an Android app restriction on the Maps API key in Cloud Console. */
    private fun signingCertificateSha1(): String = try {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signature = packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
        signature?.let {
            MessageDigest.getInstance("SHA-1")
                .digest(it.toByteArray())
                .joinToString(":") { byte -> "%02X".format(byte) }
        } ?: "unavailable"
    } catch (_: Exception) {
        "unavailable"
    }
}
