package dev.friday.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.friday.assistant.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

/** Reads text aloud and returns only once it has finished, so the next step does not talk over it. */
class Speaker(context: Context, private val settings: Settings) {
    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    /** Returns whether anything was actually spoken, so a caller can fall back to a beep. */
    suspend fun speak(text: String): Boolean {
        if (!ready.await()) return false
        val clean = speakable(text)
        if (clean.isBlank()) return false
        Voices.apply(tts, settings, clean)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }

                @Deprecated("Required by the base class")
                override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
            })
            cont.invokeOnCancellation { tts.stop() }
            tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, id)
        }
        return true
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    companion object {
        /** Emoji and symbols are read out as words ("red heart"), so only letters, digits and plain punctuation stay. */
        fun speakable(text: String) = text.replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s.,?!'।-]"), "").replace(Regex("\\s+"), " ").trim()
    }
}
