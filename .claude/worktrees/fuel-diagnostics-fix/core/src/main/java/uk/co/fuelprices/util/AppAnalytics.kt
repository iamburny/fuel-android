package uk.co.fuelprices.util

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around Firebase Analytics (the Android SDK for GA4 — this project's Firebase
 * project is linked to the same GA4 property fuel-web reports to, so events logged here land
 * alongside the web ones). Event and parameter names deliberately mirror fuel-web's
 * lib/analytics.ts trackEvent() calls, so the same interaction reads as one event across
 * platforms in GA4 rather than two differently-named ones.
 */
@Singleton
class AppAnalytics @Inject constructor(private val firebaseAnalytics: FirebaseAnalytics) {

    fun trackEvent(name: String, params: Map<String, Any> = emptyMap()) {
        val bundle = Bundle()
        params.forEach { (key, value) ->
            when (value) {
                is Int -> bundle.putInt(key, value)
                is Long -> bundle.putLong(key, value)
                is Double -> bundle.putDouble(key, value)
                else -> bundle.putString(key, value.toString())
            }
        }
        firebaseAnalytics.logEvent(name, bundle)
    }
}
