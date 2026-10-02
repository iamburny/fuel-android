package uk.co.fuelprices.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.repository.UserPreferencesStore
import uk.co.fuelprices.util.FeatureFlags
import javax.inject.Inject

private const val FLAG_NAME = "shared.announcement-banner"

@HiltViewModel
class AnnouncementBannerViewModel @Inject constructor(
    private val featureFlags: FeatureFlags,
    private val prefsStore: UserPreferencesStore,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            refresh()
            featureFlags.version.collect { refresh() }
        }
    }

    private suspend fun refresh() {
        val text = if (featureFlags.isEnabled(FLAG_NAME)) featureFlags.getVariantText(FLAG_NAME) else null
        val dismissed = prefsStore.get().dismissedAnnouncementMessage
        _message.value = if (text != null && text != dismissed) text else null
    }

    fun dismiss() {
        val current = _message.value ?: return
        viewModelScope.launch {
            prefsStore.dismissAnnouncement(current)
            _message.value = null
        }
    }
}

/** Demo of the shared.announcement-banner feature flag — proves the Unleash wiring end-to-end
 *  (mirrors the web/admin AnnouncementBanner of the same name). Renders nothing when the flag's
 *  off, unconfigured, or its current message was already dismissed. */
@Composable
fun AnnouncementBanner(viewModel: AnnouncementBannerViewModel = hiltViewModel()) {
    val message by viewModel.message.collectAsState()
    val text = message ?: return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.dismiss() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
            }
        }
    }
}
