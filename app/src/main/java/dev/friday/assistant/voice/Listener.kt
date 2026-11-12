package dev.friday.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Listens once and returns what was said, or null if nothing usable was heard. Main thread only. */
class Listener(private val context: Context) {

    suspend fun listen(): String? = suspendCancellableCoroutine { cont ->
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            VoiceLog.add(context, "Is phone pe speech recognition service nahi mili")
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        var done = false
        fun finish(text: String?) {
            if (done) return
            done = true
            if (cont.isActive) cont.resume(text)
            recognizer.destroy()
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                finish(
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.takeIf { it.isNotBlank() },
                )
            }

            override fun onError(error: Int) {
                // 6 (no speech) and 7 (nothing recognised) are ordinary; anything else is worth a look.
                if (error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && error != SpeechRecognizer.ERROR_NO_MATCH) {
                    VoiceLog.add(context, "Speech error code $error")
                }
                finish(null)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        cont.invokeOnCancellation {
            if (!done) {
                done = true
                recognizer.cancel()
                recognizer.destroy()
            }
        }
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Stop soon after the user stops talking, instead of waiting out a long silence.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L),
        )
    }
}
