package dev.friday.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Bundle
import dev.friday.assistant.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * The short "Yes, boss?" Friday says when it hears its name.
 *
 * Asking the speech engine to say it on the spot took 1.5 to 2.5 seconds, which is long enough for the user
 * to start talking over it. So the phrases are recorded to files once, ahead of time, and played back from
 * those, which starts at once. If a file is missing (not recorded yet, or the engine refused) the caller falls
 * back to speaking it live.
 *
 * It owns a text-to-speech engine of its own so it cannot disturb the one that reads Friday's answers.
 */
class AckPlayer(private val context: Context, private val settings: Settings) {
    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(context.applicationContext) { ready.complete(it == TextToSpeech.SUCCESS) }

    /** The phrase files for the voice and language now in use, recorded if they do not exist yet. */
    suspend fun prepare() {
        if (!ready.await()) return
        Voices.apply(tts, settings)
        for (phrase in phrases()) {
            val file = file(phrase)
            if (!file.exists() || file.length() == 0L) {
                if (!record(phrase, file)) file.delete()
            }
        }
    }

    /** Plays one recorded phrase and returns when it has finished; false if there was none to play. */
    suspend fun play(): Boolean {
        val file = phrases().map { file(it) }.filter { it.exists() && it.length() > 0 }.randomOrNull() ?: return false
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val player = MediaPlayer()
                try {
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    player.setDataSource(file.absolutePath)
                    player.setOnCompletionListener {
                        it.release()
                        if (cont.isActive) cont.resume(true)
                    }
                    player.setOnErrorListener { mp, _, _ ->
                        mp.release()
                        if (cont.isActive) cont.resume(false)
                        true
                    }
                    cont.invokeOnCancellation { runCatching { player.release() } }
                    player.prepare() // a file of a second or so: ready almost at once
                    player.start()
                } catch (e: Exception) {
                    runCatching { player.release() }
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun phrases() = ENGLISH

    /** Named after the voice and the text, so choosing another voice records fresh files instead of reusing old ones. */
    private fun file(phrase: String): File {
        val voice = settings.voiceName.orEmpty()
        val key = "$voice-${phrase.hashCode()}".replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(context.cacheDir, "ack-$key.wav")
    }

    private suspend fun record(phrase: String, into: File): Boolean = suspendCancellableCoroutine { cont ->
        val id = UUID.randomUUID().toString()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }

            @Deprecated("Required by the base class")
            override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(false) }
        })
        if (tts.synthesizeToFile(phrase, Bundle(), into, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
    }

    companion object {
        /** None of these may contain Friday's own name, or the wake word listener would hear it. */
        val ENGLISH = listOf("Yes, boss?", "Go ahead.", "I'm listening.")
    }
}
