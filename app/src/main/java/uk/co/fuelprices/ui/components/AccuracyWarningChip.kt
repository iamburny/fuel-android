package uk.co.fuelprices.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.fuelprices.ui.screens.detail.STATION_RATINGS_FLAG
import uk.co.fuelprices.util.FeatureFlags
import javax.inject.Inject

const val ACCURACY_WARNING_LABEL = "Drivers report price differences"
const val ACCURACY_WARNING_DETAIL =
    "Drivers who rated this station often found the pump price didn't match the published price. " +
        "Driver reports, not Fuel Finder data."

/** Amber used for the warning on both the list chip and the map pin badge. */
val AccuracyWarningAmber = Color(0xFFF59E0B)

/**
 * Marks a station whose drivers often found the pump price didn't match the published one. It is
 * driver-reported, so it sits beside the price and says so; it never changes sorting, filtering or
 * the price itself.
 */
@Composable
fun AccuracyWarningChip(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "$ACCURACY_WARNING_LABEL. $ACCURACY_WARNING_DETAIL"
        },
        shape = RoundedCornerShape(50),
        color = AccuracyWarningAmber.copy(alpha = 0.18f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            ACCURACY_WARNING_LABEL,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Whether driver ratings (and so the accuracy warning) are switched on, re-evaluated on every
 *  flag poll so the feature can be switched off remotely. */
@HiltViewModel
class StationRatingsFlagViewModel @Inject constructor(
    private val featureFlags: FeatureFlags,
) : ViewModel() {

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        viewModelScope.launch {
            featureFlags.version.collect {
                _enabled.value = featureFlags.isEnabled(STATION_RATINGS_FLAG, default = false)
            }
        }
    }
}
