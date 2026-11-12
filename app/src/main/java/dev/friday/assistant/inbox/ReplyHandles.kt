package dev.friday.assistant.inbox

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the reply button of each notification, so a message can be answered from here without opening the app.
 * Only while Friday's process is alive and the notification is still showing, which is why a reply can fail.
 */
object ReplyHandles {
    private val handles = ConcurrentHashMap<Long, ReplyHandle>()

    fun put(eventId: Long, handle: ReplyHandle) {
        handles[eventId] = handle
        if (handles.size > 300) handles.keys.sorted().take(100).forEach { handles.remove(it) }
    }

    fun has(eventId: Long) = handles.containsKey(eventId)

    /** Sends [text] as the reply. True means Android accepted the request, not that the other app delivered it. */
    fun send(context: Context, eventId: Long, text: String): Boolean {
        val handle = handles[eventId] ?: return false
        return try {
            val fill = Intent()
            RemoteInput.addResultsToIntent(arrayOf(handle.input), fill, Bundle().apply { putCharSequence(handle.input.resultKey, text) })
            handle.action.send(context, 0, fill)
            true
        } catch (e: PendingIntent.CanceledException) {
            handles.remove(eventId)
            false
        }
    }
}
