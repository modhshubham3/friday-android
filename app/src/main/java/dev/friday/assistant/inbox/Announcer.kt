package dev.friday.assistant.inbox

import android.content.Context
import dev.friday.assistant.Settings
import dev.friday.assistant.voice.FridayService
import dev.friday.assistant.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gets an important message spoken. With Hey Friday running, the service does it and can also listen for a reply.
 * Without it, drive mode still reads the message out, but cannot hear an answer.
 */
object Announcer {
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun request(context: Context) {
        val app = context.applicationContext
        if (FridayService.running) FridayService.announce(app) else if (Settings(app).driveMode) readOnly(app)
    }

    private fun readOnly(context: Context) {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            val settings = Settings(context)
            val speaker = withContext(Dispatchers.Main) { Speaker(context, settings) }
            try {
                while (settings.driveMode) {
                    val event = Inbox.nextToAnnounce(context) ?: break
                    Inbox.markAnnounced(context, event.id)
                    Inbox.lastEventId = event.id
                    speaker.speak(Inbox.line(event, Privacy.mayReadContent(context)))
                    delay(500)
                }
            } finally {
                withContext(Dispatchers.Main) { speaker.shutdown() }
                running.set(false)
            }
        }
    }
}
