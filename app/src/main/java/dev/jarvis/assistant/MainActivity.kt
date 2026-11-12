package dev.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.jarvis.assistant.llm.GeminiProvider
import dev.jarvis.assistant.llm.GroqProvider
import dev.jarvis.assistant.llm.LlmRouter
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Week 1: push-to-talk.
 * Button -> SpeechRecognizer (voice to text) -> LlmRouter -> TextToSpeech.
 */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var talkButton: Button

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val router = LlmRouter(
        listOf(
            GeminiProvider(BuildConfig.GEMINI_API_KEY, BuildConfig.GEMINI_MODEL),
            GroqProvider(BuildConfig.GROQ_API_KEY, BuildConfig.GROQ_MODEL),
        ),
    )

    private val askMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else status.text = "Mic permission ke bina kaam nahi chalega"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        transcript = findViewById(R.id.transcript)
        talkButton = findViewById(R.id.talkButton)

        tts = TextToSpeech(this) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale("en", "IN")
        }

        talkButton.setOnClickListener {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) startListening() else askMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Is phone pe speech recognition service nahi mili"
            return
        }
        tts?.stop() // do not let Jarvis hear its own voice
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        talkButton.isEnabled = false
        status.text = "Sun raha hu..."
        sr.startListening(intent)
    }

    private fun onHeard(text: String) {
        transcript.append("Tum: $text\n")
        status.text = "Soch raha hu..."
        lifecycleScope.launch {
            val reply = router.ask(SYSTEM_PROMPT, text)
            transcript.append("Jarvis: ${reply.text}\n\n")
            status.text = reply.provider?.let { "Jawab: $it" } ?: getString(R.string.idle)
            talkButton.isEnabled = true
            if (ttsReady) tts?.speak(reply.text, TextToSpeech.QUEUE_FLUSH, null, "reply")
        }
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isBlank()) onError(SpeechRecognizer.ERROR_NO_MATCH) else onHeard(text)
        }

        override fun onError(error: Int) {
            status.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Kuch sunai nahi diya, dobara bolo"
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network problem"
                else -> "Speech error (code $error)"
            }
            talkButton.isEnabled = true
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { status.text = "Samajh raha hu..." }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "You are Jarvis, a voice assistant on the user's Android phone. " +
                "Your reply is read aloud by text-to-speech, so answer in one to three short " +
                "sentences with no markdown, lists, or emojis. Reply in Hinglish (Hindi written " +
                "in Roman script mixed with English) unless the user speaks pure English."
    }
}
