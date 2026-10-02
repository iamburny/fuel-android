package uk.co.fuelprices

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.api.FuelPricesApi
import uk.co.fuelprices.data.api.FuelTypes
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

        // Price-drop payloads are expected as a snake_case data map (station_id, fuel_type,
        // price_pence, station_name). Fall back to the notification block if the backend sends a
        // notification-style message instead, so we still surface *something* either way.
        val data = message.data
        val stationId = data["station_id"]?.toIntOrNull()
        val stationName = data["station_name"] ?: message.notification?.title ?: "A favourite station"
        val fuelType = data["fuel_type"]
        val pricePence = data["price_pence"]?.toDoubleOrNull()

        val title = message.notification?.title ?: "Price drop at $stationName"
        val body = message.notification?.body ?: buildString {
            fuelType?.let { append(FuelTypes.shortLabel(it)) }
            if (fuelType != null && pricePence != null) append(" now ")
            pricePence?.let { append("%.1fp".format(it)) }
        }.ifBlank { "A favourite station's price has dropped." }

        showNotification(title, body, stationId)
    }

    private fun showNotification(title: String, body: String, stationId: Int?) {
        // Android 13+ requires the runtime POST_NOTIFICATIONS grant; bail quietly if not held.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (stationId != null) putExtra(EXTRA_STATION_ID, stationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            stationId ?: 0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, PRICE_ALERTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_fuel)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        // Distinct id per station so multiple stations' alerts don't overwrite each other.
        NotificationManagerCompat.from(this).notify(stationId ?: 0, notification)
    }
}
