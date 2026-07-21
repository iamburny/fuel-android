package uk.co.fuelprices

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

const val PRICE_ALERTS_CHANNEL_ID = "price_alerts"

@HiltAndroidApp
class FuelApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // A channel is mandatory for notifications to display on API 26+ (minSdk is 29, so this is
        // unconditional). Without it, FcmService's price-drop notifications are silently dropped.
        val channel = NotificationChannel(
            PRICE_ALERTS_CHANNEL_ID,
            "Price alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Alerts when a favourite station's fuel price drops"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
