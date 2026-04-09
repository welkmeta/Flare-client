package flare.client.app.ui.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import flare.client.app.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class NotificationType {
    SUCCESS, ERROR, WARNING
}

data class NotificationData(
    val type: NotificationType,
    val text: String,
    val durationSec: Int
)

object AppNotificationManager {
    private val _notifications = MutableSharedFlow<NotificationData>(extraBufferCapacity = 1)
    val notifications: SharedFlow<NotificationData> = _notifications.asSharedFlow()

    private const val BEST_PROFILE_CHANNEL = "best_profile_updates"
    private const val BEST_PROFILE_NOTIF_ID = 1002

    fun showNotification(type: NotificationType, text: String, durationSec: Int) {
        _notifications.tryEmit(NotificationData(type, text, durationSec))
    }

    fun showSystemNotification(context: Context, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(BEST_PROFILE_CHANNEL) == null) {
                val channel = NotificationChannel(
                    BEST_PROFILE_CHANNEL,
                    "Profile Updates",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                manager.createNotificationChannel(channel)
            }
        }

        val notification = NotificationCompat.Builder(context, BEST_PROFILE_CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setAutoCancel(true)
            .build()
        manager.notify(BEST_PROFILE_NOTIF_ID, notification)
    }
}

