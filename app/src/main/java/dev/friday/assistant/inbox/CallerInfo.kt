package dev.friday.assistant.inbox

import android.app.Notification
import android.service.notification.StatusBarNotification

/** Remembers who is ringing, read from the dialer's incoming-call notification (the name or number is its title). */
object CallerInfo {
    @Volatile private var caller: String? = null

    fun onPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.category != Notification.CATEGORY_CALL) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if (title.isNotEmpty()) caller = title
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.notification.category == Notification.CATEGORY_CALL) caller = null
    }

    fun now(): String? = caller
}
