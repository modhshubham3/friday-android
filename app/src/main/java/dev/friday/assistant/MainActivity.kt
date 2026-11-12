package dev.friday.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.text.InputType
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.friday.assistant.llm.GeminiProvider
import dev.friday.assistant.llm.GroqProvider
import dev.friday.assistant.llm.LlmRouter
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
    private lateinit var input: EditText
    private lateinit var sendButton: Button

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private lateinit var settings: Settings

    /** Built per question so a key changed in Settings applies immediately. */
    private fun buildRouter() = LlmRouter(
        listOf(
            GeminiProvider(settings.geminiKey, settings.geminiModel),
            GroqProvider(settings.groqKey, settings.groqModel),
        ),
    )

    private val askMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else status.text = "Mic permission ke bina kaam nahi chalega"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettings() }
        status = findViewById(R.id.status)
        transcript = findViewById(R.id.transcript)
        talkButton = findViewById(R.id.talkButton)

        tts = TextToSpeech(this) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale("en", "IN")
        }

        input = findViewById(R.id.input)
        sendButton = findViewById(R.id.sendButton)
        sendButton.setOnClickListener { sendTyped() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
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
        tts?.stop() // do not let Friday hear its own voice
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

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || !sendButton.isEnabled) return
        input.text.clear()
        onHeard(text, speak = false) // typed questions get a silent, on-screen answer
    }

    private fun onHeard(text: String, speak: Boolean = true) {
        transcript.append("Tum: $text\n")
        status.text = "Soch raha hu..."
        talkButton.isEnabled = false
        sendButton.isEnabled = false
        lifecycleScope.launch {
            val reply = buildRouter().ask(SYSTEM_PROMPT, text)
            // No Android Studio means no logcat, so failures are printed on screen.
            reply.errors.forEach { transcript.append("[error] $it\n") }
            transcript.append("Friday: ${reply.text}\n\n")
            status.text = reply.provider?.let { "Jawab: $it" } ?: getString(R.string.idle)
            talkButton.isEnabled = true
            sendButton.isEnabled = true
            if (speak && ttsReady) tts?.speak(reply.text, TextToSpeech.QUEUE_FLUSH, null, "reply")
        }
    }

    private fun showSettings() {
        // Keys are shown as dots so a screenshot of this dialog cannot leak them.
        fun field(label: String, value: String, secret: Boolean = false) = EditText(this).apply {
            hint = label
            inputType = InputType.TYPE_CLASS_TEXT or
                if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(value)
        }
        val geminiKey = field("Gemini API key", settings.geminiKey, secret = true)
        val geminiModel = field("Gemini model", settings.geminiModel)
        val groqKey = field("Groq API key (optional)", settings.groqKey, secret = true)
        val groqModel = field("Groq model", settings.groqModel)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            listOf(geminiKey, geminiModel, groqKey, groqModel).forEach(::addView)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(form)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.geminiKey = geminiKey.text.toString()
                settings.geminiModel = geminiModel.text.toString()
                settings.groqKey = groqKey.text.toString()
                settings.groqModel = groqModel.text.toString()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            "You are Friday, a voice assistant on the user's Android phone. " +
                "Your reply is read aloud by text-to-speech, so answer in one to three short " +
                "sentences with no markdown, lists, or emojis. Reply in Hinglish (Hindi written " +
                "in Roman script mixed with English) unless the user speaks pure English."
    }
}
