package uk.co.fuelprices.util

import io.getunleash.android.Unleash
import io.getunleash.android.events.UnleashStateListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin reactive wrapper around the Unleash Android SDK (self-hosted, shared across all
 * fuel-tracker apps — see the `unleash` deploy repo). The SDK itself exposes plain synchronous
 * `isEnabled()`/`getVariant()` with no Flow, so this bumps [version] on every state change
 * (fires after each background poll/refresh) — call sites observe [version] to know when to
 * re-evaluate a flag, rather than only reading it once at first composition.
 */
@Singleton
class FeatureFlags @Inject constructor(private val client: Unleash) {

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    init {
        client.addUnleashEventListener(object : UnleashStateListener {
            override fun onStateChanged() {
                _version.value++
            }
        })
    }

    fun isEnabled(name: String, default: Boolean = false): Boolean = client.isEnabled(name, default)

    /** The active variant's text payload for a flag, or null when inactive/no payload. */
    fun getVariantText(name: String): String? {
        val variant = client.getVariant(name)
        if (!variant.enabled) return null
        return variant.payload?.value
    }
}
