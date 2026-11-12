package dev.friday.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.friday.assistant.Settings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Listens once and returns what was said, or null if nothing usable was heard. Main thread only. */
class Listener(private val context: Context, private val settings: Settings) {

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
            override fun onRmsChanged(rmsdB: Float) = VoiceState.hear(rmsdB)
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
        recognizer.startListening(request(context, settings))
    }

    companion object {
        /** Which language the speech engine listens for: "en-IN", "hi-IN", "gu-IN", or "auto". */
        val LANGUAGES = listOf("en-IN", "hi-IN", "gu-IN", "auto")

        /** The recognition request, shared by the voice service and the app's own microphone button. */
        fun request(context: Context, settings: Settings): Intent {
            val choice = settings.listenLanguage
            val primary = if (choice == "auto") "hi-IN" else choice // Hindi recognition copes with English words mixed in
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Wait a little for a pause mid-sentence, but not for long once the sentence is over.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_100L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_100L)
            if (Build.VERSION.SDK_INT >= 33) {
                if (choice == "auto") {
                    // Experimental: lets the engine pick between the three languages itself, if it supports that.
                    intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    intent.putStringArrayListExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                        arrayListOf("hi-IN", "en-IN", "gu-IN"),
                    )
                }
                // Hints for the commands Friday really gets, so "flash off" is not heard as "clash of".
                intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, hints(primary))
            }
            return intent
        }

        private fun hints(language: String): ArrayList<String> = when (language) {
            "hi-IN" -> arrayListOf("टॉर्च ऑन", "टॉर्च ऑफ", "फ्लैशलाइट ऑन", "फ्लैशलाइट ऑफ", "कॉल करो", "अलार्म लगाओ")
            "gu-IN" -> arrayListOf("ટોર્ચ ચાલુ", "ટોર્ચ બંધ", "કોલ કરો", "એલાર્મ મૂકો")
            else -> arrayListOf(
                "torch on", "torch off", "flashlight on", "flashlight off", "flash on", "flash off",
                "call", "set an alarm", "cancel the alarm",
            )
        }
    }
}
