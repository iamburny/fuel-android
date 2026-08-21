package uk.co.fuelprices.ui.screens.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
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
        addAll(signingCertificateLines())
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

    /**
     * SHA-1(s) of the certificate(s) this running build is signed with — compare against the
     * Android-app restriction registered on the Maps API key in Cloud Console.
     *
     * With **Play App Signing + signing-key rotation** the installed APK carries a *lineage* of
     * certificates, not one. `apkContentsSigners.firstOrNull()` (what this screen used to report)
     * returns only a single member of that lineage, and on a rotated app that is often *not* the
     * cert Google actually validates API calls against — so the value shown here could disagree
     * with the SHA-1 the Maps SDK demands, sending support down the wrong path. We therefore
     * surface the current signer *and* the full rotation lineage, so whichever cert a given Google
     * service checks is always visible. When a Maps auth failure is in logcat, the fingerprint it
     * prints (`Google Android Maps SDK: Authorization failure … Android Application (…): <SHA-1>`)
     * is the authoritative one to register.
     */
    private fun signingCertificateLines(): List<Pair<String, String>> = try {
        val signingInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ).signingInfo ?: return listOf("App signing certificate (SHA-1)" to "unavailable")

        // For a rotated single-signer app the lineage lives in signingCertificateHistory (ordered
        // oldest → current); apkContentsSigners holds the concurrent signer(s). Union both so no
        // lineage member is dropped, regardless of OS-version quirks in which accessor is populated.
        val current = signingInfo.apkContentsSigners?.toList().orEmpty()
        val history = signingInfo.signingCertificateHistory?.toList().orEmpty()
        val currentSha1 = (history.lastOrNull() ?: current.firstOrNull())?.let(::sha1Hex)
            ?: return listOf("App signing certificate (SHA-1)" to "unavailable")

        val lineage = (history + current).map(::sha1Hex).distinct()
        buildList {
            add("App signing certificate (SHA-1)" to currentSha1)
            // Only meaningful once the key has been rotated; a single-cert app adds no noise.
            if (lineage.size > 1) {
                add("Signing cert lineage (SHA-1)" to lineage.joinToString("  |  "))
            }
        }
    } catch (_: Exception) {
        listOf("App signing certificate (SHA-1)" to "unavailable")
    }

    private fun sha1Hex(signature: Signature): String =
        MessageDigest.getInstance("SHA-1")
            .digest(signature.toByteArray())
            .joinToString(":") { "%02X".format(it) }
}
