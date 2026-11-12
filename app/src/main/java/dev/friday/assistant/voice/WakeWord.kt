package dev.friday.assistant.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.sqrt

internal const val SAMPLE_RATE = 16_000

/** The phrase, "friday" alone (people drop the "hey"), or "[unk]" for everything else, so ordinary talk is not forced into a match. */
internal const val WAKE_GRAMMAR = "[\"hey friday\", \"friday\", \"[unk]\"]"

/** Loudness of a stretch of audio, 0 to about 10000; silence is under 50 and speech is in the hundreds or more. */
internal fun loudness(samples: ShortArray, count: Int): Int {
    var sum = 0.0
    for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
    return if (count == 0) 0 else sqrt(sum / count).toInt()
}

/**
 * The always-on listener. A small speech model (Vosk) runs on the phone and is told to expect only
 * "hey friday"; everything else it hears is thrown away. Nothing is recorded or sent anywhere
 * until the phrase is heard.
 *
 * One thread reads the microphone. Pausing stops that thread and frees the microphone, keeping the
 * loaded model, so listening can restart quickly.
 */
class WakeWord(private val context: Context) {
    private var model: Model? = null
    private var thread: Thread? = null
    @Volatile private var stopRequested = false
    private var onWake: (() -> Unit)? = null
    private var onProblem: ((String) -> Unit)? = null
    private var sessions = 0

    /**
     * Starts listening. Returns a plain-words reason right away if it cannot; a problem that only
     * shows up later (model fails to load, microphone taken) goes to [onProblem], from any thread.
     */
    @Synchronized
    fun start(onWake: () -> Unit, onProblem: (String) -> Unit): String? {
        release()
        if (!ModelStore.isReady(context)) return "Voice model download nahi hua"
        this.onWake = onWake
        this.onProblem = onProblem
        begin()
        return null
    }

    @Synchronized
    fun pause() = halt()

    @Synchronized
    fun resume() {
        if (onWake != null && thread?.isAlive != true) begin()
    }

    @Synchronized
    fun release() {
        halt()
        runCatching { model?.close() }
        model = null
        onWake = null
        onProblem = null
    }

    private fun begin() {
        stopRequested = false
        sessions++
        val first = sessions == 1
        thread = Thread({ listenLoop(first) }, "friday-wake").also { it.start() }
    }

    private fun halt() {
        stopRequested = true
        thread?.let { runCatching { it.join(2_000) } }
        thread = null
    }

    private fun listenLoop(logLevels: Boolean) {
        var recorder: AudioRecord? = null
        var recognizer: Recognizer? = null
        try {
            val loaded = model ?: run {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                Model(ModelStore.dir(context).absolutePath).also { model = it }
            }
            recognizer = Recognizer(loaded, SAMPLE_RATE.toFloat(), WAKE_GRAMMAR)

            val chunk = ShortArray(SAMPLE_RATE / 10) // 100 ms
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, chunk.size * 2 * 4),
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                onProblem?.invoke("Mic khul nahi paya (koi aur app use kar rahi hai?)")
                return
            }
            recorder.startRecording()
            var chunks = 0
            var peak = 0

            while (!stopRequested) {
                val n = recorder.read(chunk, 0, chunk.size)
                if (n < 0) {
                    onProblem?.invoke("Mic padhne mein error ($n)")
                    return
                }
                if (n == 0) continue
                // Proof in the log that the microphone is really delivering sound, and how loud.
                peak = maxOf(peak, loudness(chunk, n))
                chunks++
                if (logLevels && (chunks == 50 || chunks == 300)) {
                    VoiceLog.add(context, "Mic chal raha hai: ${chunks / 10} second mein sabse tez level $peak")
                    peak = 0
                }
                val json = if (recognizer.acceptWaveForm(chunk, n)) recognizer.result else recognizer.partialResult
                val heard = JSONObject(json).let { it.optString("text").ifEmpty { it.optString("partial") } }
                if ("friday" in heard) {
                    VoiceLog.add(context, "Wake word pehchana: \"$heard\"")
                    onWake?.invoke()
                    return // the conversation takes over the microphone; resume() restarts this afterwards
                }
            }
        } catch (e: Throwable) {
            // Throwable on purpose: a missing native library is an Error, not an Exception.
            onProblem?.invoke(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { recognizer?.close() }
        }
    }
}
