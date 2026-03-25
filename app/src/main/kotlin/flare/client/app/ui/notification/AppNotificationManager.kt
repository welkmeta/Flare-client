package flare.client.app.ui.notification

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

    fun showNotification(type: NotificationType, text: String, durationSec: Int) {
        _notifications.tryEmit(NotificationData(type, text, durationSec))
    }
}
