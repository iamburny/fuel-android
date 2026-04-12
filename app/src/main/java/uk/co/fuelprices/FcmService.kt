package uk.co.fuelprices

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.api.FuelPricesApi
import javax.inject.Inject

@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {

    @Inject lateinit var api: FuelPricesApi

    override fun onNewToken(token: String) {
        Log.d("FCM", "New token: $token")
        CoroutineScope(Dispatchers.IO).launch {
            try { api.updateFcmToken(token) } catch (_: Exception) { }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d("FCM", "Message: ${message.data}")
        // TODO: show notification for price drop alerts
    }
}
