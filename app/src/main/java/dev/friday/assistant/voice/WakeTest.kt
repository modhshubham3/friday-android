package dev.friday.assistant.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * A live check of the wake word on its own, with the screen on, so a failure can be pinned down:
 * is sound arriving at all, and what does the model make of it? Two recognizers hear the same audio,
 * one limited to the wake phrase (what really runs) and one free to write down any words.
 */
object WakeTest {
    data class Live(val secondsLeft: Int, val level: Int, val peak: Int, val wake: String, val free: String)

    data class Outcome(
        val error: String?,
        val matched: Boolean,
        val peak: Int,
        val wake: String,
        val free: String,
    )

    suspend fun run(context: Context, seconds: Int, report: (Live) -> Unit): Outcome = withContext(Dispatchers.IO) {
        var recorder: AudioRecord? = null
        var wake: Recognizer? = null
        var free: Recognizer? = null
        var model: Model? = null
        var matched = false
        var peak = 0
        var lastWake = ""
        var lastFree = ""
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(ModelStore.dir(context).absolutePath)
            wake = Recognizer(model, SAMPLE_RATE.toFloat(), WAKE_GRAMMAR)
            free = Recognizer(model, SAMPLE_RATE.toFloat())

            val chunk = ShortArray(SAMPLE_RATE / 10)
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, chunk.size * 2 * 4),
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext Outcome("Mic khul nahi paya (koi aur app use kar rahi hai?)", false, 0, "", "")
            }
            recorder.startRecording()

            val endAt = SystemClock.elapsedRealtime() + seconds * 1_000L
            while (isActive && SystemClock.elapsedRealtime() < endAt) {
                val n = recorder.read(chunk, 0, chunk.size)
                if (n < 0) return@withContext Outcome("Mic padhne mein error ($n)", matched, peak, lastWake, lastFree)
                if (n == 0) continue
                val level = loudness(chunk, n)
                peak = maxOf(peak, level)

                val wakeText = text(if (wake.acceptWaveForm(chunk, n)) wake.result else wake.partialResult)
                val freeText = text(if (free.acceptWaveForm(chunk, n)) free.result else free.partialResult)
                if (wakeText.isNotEmpty()) lastWake = wakeText
                if (freeText.isNotEmpty()) lastFree = freeText
                if ("friday" in wakeText) matched = true

                val left = ((endAt - SystemClock.elapsedRealtime()) / 1_000).toInt() + 1
                withContext(Dispatchers.Main) { report(Live(left, level, peak, lastWake, lastFree)) }
            }
            Outcome(null, matched, peak, lastWake, lastFree)
        } catch (e: Throwable) {
            Outcome(e.message ?: e.javaClass.simpleName, matched, peak, lastWake, lastFree)
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { wake?.close() }
            runCatching { free?.close() }
            runCatching { model?.close() }
        }
    }

    private fun text(json: String): String =
        JSONObject(json).let { it.optString("text").ifEmpty { it.optString("partial") } }
}
