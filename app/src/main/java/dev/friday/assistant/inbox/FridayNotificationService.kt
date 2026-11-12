package dev.friday.assistant.inbox

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.friday.assistant.voice.VoiceLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Android hands every notification to this once the user has switched on Notification access for Friday. */
class FridayNotificationService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val context = applicationContext
        runCatching { CallerInfo.onPosted(sbn) }
        scope.launch {
            runCatching { NotificationParser.parse(context, sbn).forEach { Inbox.ingest(context, it) } }
                .onFailure { VoiceLog.add(context, "Inbox error: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        runCatching { CallerInfo.onRemoved(sbn) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
