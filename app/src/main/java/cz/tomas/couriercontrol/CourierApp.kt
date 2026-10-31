package cz.tomas.couriercontrol

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager

class CourierApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.load(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Stav vysílání", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Trvalá notifikace s režimem JÍZDA / PRIVACY"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Upozornění", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Výpadek internetu, ztráta spojení s OBS, privacy zóny"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
                )
            }
        )
    }

    companion object {
        const val CH_STATUS = "status"
        const val CH_ALERTS = "alerts"
    }
}
