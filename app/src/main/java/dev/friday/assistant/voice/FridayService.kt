package dev.friday.assistant.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.service.quicksettings.TileService
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
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import dev.friday.assistant.Assistant
import dev.friday.assistant.MainActivity
import dev.friday.assistant.R
import dev.friday.assistant.Settings
import dev.friday.assistant.inbox.Inbox
import dev.friday.assistant.inbox.InboxVoice
import dev.friday.assistant.inbox.Privacy
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
    private lateinit var acks: AckPlayer
    private var wakeLock: PowerManager.WakeLock? = null

    private var busy = false   // a conversation is in progress
    private var paused = false // the app's own mic button or voice is using the microphone
    private var announceRequested = false // an important message arrived while Friday was busy

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        wake = WakeWord(this)
        speaker = Speaker(this, settings)
        listener = Listener(this, settings)
        acks = AckPlayer(this, settings)
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
                settings.voiceWanted = false // the user asked for it to stop, so do not bring it back by itself
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CALL -> {
                val who = intent.getStringExtra("who").orEmpty()
                if (running && who.isNotBlank() && !busy) {
                    session {
                        VoiceLog.add(this, "Call aa raha hai: $who")
                        VoiceState.mode.value = Mode.SPEAKING
                        speaker.speak("$who is calling.")
                    }
                }
                return START_STICKY
            }
            ACTION_ANNOUNCE -> {
                if (!running) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                announceRequested = true
                main.post { drainAnnouncements() }
                return START_STICKY
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

        if (intent == null) VoiceLog.add(this, "System ne service dobara shuru ki (pehle band ho gayi thi)")
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
            refreshTile()
            // Without this the processor may sleep with the screen off and stop hearing the wake word.
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "friday:listening").apply { acquire() }
        }
        val problem = wake.start(
            onWake = { main.post { onWake() } },
            onProblem = { reason ->
                main.post {
                    VoiceLog.add(this, "Wake word band: $reason")
                    update("Band ho gaya: $reason")
                    stopSelf()
                }
            },
        )
        if (problem != null) {
            VoiceLog.add(this, "Wake word start nahi hua: $problem")
            update("Chalu nahi hua: $problem")
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch { acks.prepare() } // record the spoken answers now, so they can play at once later
        VoiceLog.add(this, "Sun rahi hu ('Hey Friday' bolo)")
        update("'Hey Friday' bolo")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Android keeps a foreground service alive when the app is swiped away, but some phones do not.
        // If the log stops after this line, the phone killed it.
        VoiceLog.add(this, "App recents se hata di gayi")
        super.onTaskRemoved(rootIntent)
    }

    /** Asks the notification shade tile to redraw itself, so it shows whether listening is on. */
    private fun refreshTile() {
        runCatching {
            TileService.requestListeningState(this, ComponentName(this, FridayTileService::class.java))
        }
    }

    override fun onDestroy() {
        running = false
        refreshTile()
        scope.cancel()
        wake.release()
        speaker.shutdown()
        acks.shutdown()
        wakeLock?.takeIf { it.isHeld }?.release()
        VoiceLog.add(this, "Service band")
        super.onDestroy()
    }

    // ---- One conversation ---------------------------------------------------

    private fun onWake() {
        if (busy || paused) return
        session { converse() }
    }

    /** Reads out what is waiting, if anything, once Friday is free. */
    private fun drainAnnouncements() {
        if (busy || paused) return
        if (!announceRequested && !settings.driveMode) return
        if (Inbox.nextToAnnounce(this) == null) {
            announceRequested = false
            return
        }
        session { announceLoop() }
    }

    /**
     * One stretch of Friday using the microphone and speaker: the wake word listener lets go of the microphone,
     * music steps aside, [block] runs, and everything goes back. Only one session runs at a time.
     */
    private fun session(block: suspend () -> Unit) {
        busy = true
        talking = true
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
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                VoiceLog.add(this@FridayService, "Gadbad: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                audio.abandonAudioFocusRequest(focus)
                busy = false
                talking = false
                VoiceState.mode.value = Mode.IDLE
                VoiceState.level.value = 0f
                if (!paused) wake.resume()
                update("Bolo")
                drainAnnouncements() // anything important that arrived meanwhile
            }
        }
    }

    /** Reads each waiting message out, then listens a few seconds for what the user wants done with it. */
    private suspend fun announceLoop() {
        val prompts = VoicePrompts(this, listener, speaker, english = true)
        while (settings.driveMode || announceRequested) {
            announceRequested = false
            val event = Inbox.nextToAnnounce(this) ?: break
            Inbox.markAnnounced(this, event.id)
            Inbox.lastEventId = event.id
            VoiceLog.add(this, "Padh rahi hu: ${event.app} ${event.sender}")
            update("Bol rahi hu...")
            VoiceState.mode.value = Mode.SPEAKING
            speaker.speak(Inbox.line(event, Privacy.mayReadContent(this)))
            update("Sun rahi hu...")
            VoiceState.mode.value = Mode.LISTENING
            delay(150)
            val said = withTimeoutOrNull(6_000) { listener.listen() }
            VoiceState.mode.value = Mode.THINKING
            VoiceLog.add(this, "Jawab: ${said ?: "-"}")
            if (!InboxVoice.respond(this, event, said, speaker, listener, prompts)) break
        }
    }

    private suspend fun converse() {
        VoiceLog.add(this, "Wake word suna")
        VoiceState.mode.value = Mode.WAKE
        val english = settings.speakEnglish // whether answers stay English; plain tool results always are
        // Friday answers "Yes, boss" while already listening, so a one-breath "Friday, call Gauri" is not lost.
        if (settings.speakAck) {
            scope.launch {
                val ackAt = SystemClock.elapsedRealtime()
                if (acks.play()) VoiceLog.add(this@FridayService, "Jawab diya wake word ka (${since(ackAt)}, parallel)")
            }
        } else {
            scope.launch { chime() }
        }
        val tools = PhoneTools(
            this, settings, VoicePrompts(this, listener, speaker, english = true),
            fromBackground = true, english = true,
        )
        // Kept only while follow-ups last; the conversation ends as soon as Friday does not ask anything.
        val history = mutableListOf<ChatMessage>()

        repeat(MAX_TURNS) { turn ->
            update("Sun rahi hu...")
            VoiceState.mode.value = Mode.LISTENING
            val listenedAt = SystemClock.elapsedRealtime()
            var heard = withTimeoutOrNull(LISTEN_MS) { listener.listen() }
            // The recogniser may catch Friday's own "Yes, boss": drop that and the name, listen again if nothing is left.
            heard = heard?.let(::stripEcho)
            if (heard.isNullOrBlank() && turn == 0) heard = withTimeoutOrNull(LISTEN_MS) { listener.listen() }?.let(::stripEcho)
            if (heard.isNullOrBlank()) {
                // Stay quiet: a false wake in a quiet room should not make Friday talk to itself.
                VoiceLog.add(this, "Kuch suna nahi")
                return
            }
            VoiceLog.add(this, "Suna (${since(listenedAt)}): $heard")
            history += ChatMessage(fromUser = true, text = heard)

            update("Soch rahi hu...")
            VoiceState.mode.value = Mode.THINKING
            val thoughtAt = SystemClock.elapsedRealtime()
            val reply = Assistant.fastReply(heard, tools) ?: Assistant.router(settings).ask(
                Assistant.systemPrompt(spoken = true, english = english, facts = Inbox.facts(this)), history.takeLast(MAX_HISTORY), tools.specs,
            ) { call ->
                tools.run(call).also { VoiceLog.add(this, "Tool ${call.name}: ${it.text}") }
            }
            reply.errors.forEach { VoiceLog.add(this, "Error: $it") }
            VoiceLog.add(this, "Friday (${since(thoughtAt)}, ${reply.provider ?: "koi nahi"}): ${reply.text}")
            update("Bol rahi hu...")
            VoiceState.mode.value = Mode.SPEAKING
            speaker.speak(reply.text)

            history += ChatMessage(fromUser = false, text = reply.text)
            // A question from Friday means an answer is expected, so keep listening without a new wake word.
            if (reply.provider == null || !reply.text.trimEnd().endsWith("?")) return
        }
    }

    private fun stripEcho(text: String): String {
        var t = text.trim()
        val junk = Regex("^(hey\\s+)?friday[,.!?\\s]*|^(yes,?\\s+boss|go ahead|i'?m listening)[,.!?\\s]*", RegexOption.IGNORE_CASE)
        while (true) { val n = junk.replaceFirst(t, ""); if (n == t) break; t = n }
        return t.trim()
    }

    /** Used only when the recorded phrases are not ready, so it speaks one live. */
    private fun acknowledgement(english: Boolean) =
        AckPlayer.ENGLISH.random()

    /** A short beep so it is clear Friday heard its name and is now listening. */
    private suspend fun chime() {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        delay(220) // wait out the beep, or it is heard as speech
        tone.release()
    }

    private fun since(startedAt: Long) = "%.1fs".format((SystemClock.elapsedRealtime() - startedAt) / 1000.0)

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
        private const val ACTION_ANNOUNCE = "dev.friday.assistant.ANNOUNCE"
        private const val ACTION_CALL = "dev.friday.assistant.CALL"

        /** A call is ringing in drive mode: say who it is. */
        fun announceCall(context: Context, who: String) {
            if (running) context.startService(Intent(context, FridayService::class.java).setAction(ACTION_CALL).putExtra("who", who))
        }

        /** Longest wait for the user to start speaking after the beep. */
        private const val LISTEN_MS = 12_000L
        private const val MAX_TURNS = 4
        private const val MAX_HISTORY = 10

        /** True while Friday is in the middle of a spoken exchange. */
        @Volatile
        var talking = false
            private set

        /** An important message arrived; read it out as soon as Friday is free. */
        fun announce(context: Context) {
            if (running) context.startService(Intent(context, FridayService::class.java).setAction(ACTION_ANNOUNCE))
        }

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
