package dev.friday.assistant.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import dev.friday.assistant.Assistant
import dev.friday.assistant.MainActivity
import dev.friday.assistant.R
import dev.friday.assistant.Settings
import dev.friday.assistant.llm.ChatMessage
import dev.friday.assistant.tools.PhoneTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Hey Friday". A foreground service (Android requires the always-visible notification) that
 * waits for the wake word, then runs one voice conversation: listen, think, act, speak.
 *
 * Only one thing may hold the microphone at a time, so the wake word listener lets go of it
 * for the whole conversation and takes it back afterwards.
 */
class FridayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var settings: Settings
    private lateinit var wake: WakeWord
    private lateinit var speaker: Speaker
    private lateinit var listener: Listener
    private var wakeLock: PowerManager.WakeLock? = null

    private var busy = false   // a conversation is in progress
    private var paused = false // the app's own mic button or voice is using the microphone

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        wake = WakeWord(this, settings)
        speaker = Speaker(this)
        listener = Listener(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Hey Friday", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Dikhata hai ki Friday wake word sun raha hai"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE, ACTION_RESUME -> {
                if (!running) { // nothing to pause: this call only just started the service
                    stopSelf()
                    return START_NOT_STICKY
                }
                paused = intent?.action == ACTION_PAUSE
                if (paused) wake.pause() else if (!busy) wake.resume()
                return START_STICKY
            }
        }

        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification("Shuru ho raha hai..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // Android only lets a microphone service start while the app is on screen.
            VoiceLog.add(this, "Service start nahi hui: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            // Without this the processor may sleep with the screen off and stop hearing the wake word.
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "friday:listening").apply { acquire() }
        }
        val problem = wake.start { main.post { onWake() } }
        if (problem != null) {
            VoiceLog.add(this, "Wake word start nahi hua: $problem")
            update("Chalu nahi hua: $problem")
            stopSelf()
            return START_NOT_STICKY
        }
        val phrase = if (settings.wakeSource == "custom" && settings.wakeFile(this).exists()) "Hey Friday" else "Jarvis"
        VoiceLog.add(this, "Sun raha hu ('$phrase' bolo)")
        update("'$phrase' bolo")
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        wake.release()
        speaker.shutdown()
        wakeLock?.takeIf { it.isHeld }?.release()
        VoiceLog.add(this, "Service band")
        super.onDestroy()
    }

    // ---- One conversation ---------------------------------------------------

    private fun onWake() {
        if (busy || paused) return
        busy = true
        scope.launch {
            val audio = getSystemService(AudioManager::class.java)
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                ).build()
            try {
                wake.pause()
                audio.requestAudioFocus(focus) // pauses music so it does not drown out the user
                converse()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                VoiceLog.add(this@FridayService, "Gadbad: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                audio.abandonAudioFocusRequest(focus)
                busy = false
                if (!paused) wake.resume()
                update("Bolo")
            }
        }
    }

    private suspend fun converse() {
        VoiceLog.add(this, "Wake word suna")
        chime()
        val tools = PhoneTools(this, settings, VoicePrompts(this, listener, speaker), fromBackground = true)
        // Kept only while follow-ups last; the conversation ends as soon as Friday does not ask anything.
        val history = mutableListOf<ChatMessage>()

        repeat(MAX_TURNS) { turn ->
            update("Sun raha hu...")
            val heard = withTimeoutOrNull(LISTEN_MS) { listener.listen() }
            if (heard.isNullOrBlank()) {
                VoiceLog.add(this, "Kuch suna nahi")
                if (turn == 0) speaker.speak("Kuch sunai nahi diya")
                return
            }
            VoiceLog.add(this, "Suna: $heard")
            history += ChatMessage(fromUser = true, text = heard)

            update("Soch raha hu...")
            val reply = Assistant.router(settings).ask(
                Assistant.systemPrompt(spoken = true), history.takeLast(MAX_HISTORY), tools.specs,
            ) { call ->
                tools.run(call).also { VoiceLog.add(this, "Tool ${call.name}: ${it.text}") }
            }
            reply.errors.forEach { VoiceLog.add(this, "Error: $it") }
            VoiceLog.add(this, "Friday: ${reply.text}")
            update("Bol raha hu...")
            speaker.speak(reply.text)

            history += ChatMessage(fromUser = false, text = reply.text)
            // A question from Friday means an answer is expected, so keep listening without a new wake word.
            if (reply.provider == null || !reply.text.trimEnd().endsWith("?")) return
        }
    }

    /** A short beep so it is clear Friday heard its name and is now listening. */
    private suspend fun chime() {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        delay(350) // wait out the beep, or it is heard as speech
        tone.release()
    }

    // ---- Notification -------------------------------------------------------

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        fun pending(intent: Intent, code: Int) = PendingIntent.getActivity(
            this, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FridayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Friday")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pending(Intent(this, MainActivity::class.java), 0))
            .addAction(Notification.Action.Builder(null, "Band karo", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "friday_listening"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "dev.friday.assistant.STOP"
        private const val ACTION_PAUSE = "dev.friday.assistant.PAUSE"
        private const val ACTION_RESUME = "dev.friday.assistant.RESUME"

        /** Longest wait for the user to start speaking after the beep. */
        private const val LISTEN_MS = 12_000L
        private const val MAX_TURNS = 4
        private const val MAX_HISTORY = 10

        /** True while the service is up. Only the main thread reads or writes it. */
        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FridayService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FridayService::class.java).setAction(ACTION_STOP))
        }

        /** The app's own microphone button and voice need the mic, so wake word listening steps aside. */
        fun pause(context: Context) = signal(context, ACTION_PAUSE)

        fun resume(context: Context) = signal(context, ACTION_RESUME)

        private fun signal(context: Context, action: String) {
            if (running) context.startService(Intent(context, FridayService::class.java).setAction(action))
        }
    }
}
