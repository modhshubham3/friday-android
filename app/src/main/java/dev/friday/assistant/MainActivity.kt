package dev.friday.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.text.InputType
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.friday.assistant.llm.ChatMessage
import dev.friday.assistant.llm.GeminiProvider
import dev.friday.assistant.llm.GroqProvider
import dev.friday.assistant.llm.LlmRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The chat screen. A question arrives typed or spoken (SpeechRecognizer), goes to
 * LlmRouter together with the conversation so far, and the reply is shown as a
 * bubble and, for spoken questions, read aloud (TextToSpeech).
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: View
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var divider: View
    private lateinit var scroll: ScrollView
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private lateinit var talkButton: TextView
    private lateinit var sendButton: TextView
    private lateinit var newChatButton: TextView
    private lateinit var settingsButton: TextView

    /** What a bubble on screen holds. Only USER and FRIDAY are sent to the model. */
    private enum class Kind { USER, FRIDAY, ERROR }
    private data class Bubble(val kind: Kind, val text: String)

    /** Everything on screen, oldest first. Kept in memory only: closing the app clears it. */
    private val bubbles = mutableListOf<Bubble>()
    private var busy = false

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private lateinit var settings: Settings

    /** Built per question so a key changed in Settings applies immediately. */
    private fun buildRouter() = LlmRouter(
        settings.geminiModels.map { GeminiProvider(settings.geminiKey, it) } +
            GroqProvider(settings.groqKey, settings.groqModel),
    )

    /** Set while we wait for the user to allow "install unknown apps". */
    private var pendingUpdate: Updater.Update? = null
    private var updateDialogShowing = false
    private var lastUpdateCheck = 0L

    private val askMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else status.text = "Mic permission ke bina kaam nahi chalega"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        root = findViewById(R.id.root)
        title = findViewById(R.id.title)
        status = findViewById(R.id.status)
        divider = findViewById(R.id.divider)
        scroll = findViewById(R.id.scroll)
        messages = findViewById(R.id.messages)
        talkButton = findViewById(R.id.talkButton)
        newChatButton = findViewById(R.id.newChatButton)
        settingsButton = findViewById(R.id.settingsButton)
        settingsButton.setOnClickListener { showSettings() }
        newChatButton.setOnClickListener {
            if (!busy) {
                bubbles.clear()
                renderBubbles()
                status.text = getString(R.string.idle)
            }
        }

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

        applyLook()

        talkButton.setOnClickListener {
            if (busy) return@setOnClickListener
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
        setBusy(true)
        status.text = "Sun raha hu..."
        sr.startListening(intent)
    }

    /**
     * Runs every time the screen comes to the front, not only on a cold start: Android
     * keeps the app alive in the background, so onCreate alone can go days without running.
     * [manual] is true when the user asked from Settings, and then the outcome is always
     * reported; an automatic check stays silent unless there is an update.
     */
    private fun checkForUpdate(manual: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!manual && (updateDialogShowing || now - lastUpdateCheck < UPDATE_CHECK_GAP_MS)) return
        lastUpdateCheck = now
        lifecycleScope.launch {
            val result = runCatching { Updater.check(this@MainActivity) }
            val update = result.getOrNull()
            if (update == null) {
                // A failed automatic check (offline, site down) must never get in the way.
                if (manual) {
                    status.text = result.exceptionOrNull()?.let { "Update check fail: ${it.message}" }
                        ?: "Friday latest hai (build ${Updater.installedCode(this@MainActivity)})"
                }
                return@launch
            }
            if (updateDialogShowing) return@launch
            updateDialogShowing = true
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Naya version ${update.name}")
                .setMessage(update.notes.ifBlank { "Friday ka naya build available hai." })
                .setPositiveButton("Update") { _, _ -> startUpdate(update) }
                .setNegativeButton("Baad mein", null)
                .setOnDismissListener { updateDialogShowing = false }
                .show()
        }
    }

    private fun startUpdate(update: Updater.Update) {
        if (!Updater.canInstall(this)) {
            pendingUpdate = update // resumed in onResume once the switch is on
            status.text = "Friday ko 'Install unknown apps' allow karo, phir wapas aao"
            Updater.openInstallPermission(this)
            return
        }
        pendingUpdate = null
        lifecycleScope.launch {
            try {
                val apk = Updater.download(this@MainActivity, update) { pct ->
                    status.text = "Update download ho raha hai: $pct%"
                }
                status.text = "Installer khul raha hai..."
                Updater.install(this@MainActivity, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = "Update fail: ${e.message}"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val update = pendingUpdate
        if (update == null) checkForUpdate()
        else if (Updater.canInstall(this)) startUpdate(update)
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy) return
        input.text.clear()
        onHeard(text, speak = false) // typed questions get a silent, on-screen answer
    }

    private fun onHeard(text: String, speak: Boolean = true) {
        addBubble(Bubble(Kind.USER, text))
        status.text = "Soch raha hu..."
        setBusy(true)
        lifecycleScope.launch {
            val reply = buildRouter().ask(SYSTEM_PROMPT, historyForModel())
            // No Android Studio means no logcat, so failures are shown on screen.
            reply.errors.forEach { addBubble(Bubble(Kind.ERROR, it)) }
            // A canned "nobody answered" line is not Friday speaking, so it must not enter the history.
            addBubble(Bubble(if (reply.provider != null) Kind.FRIDAY else Kind.ERROR, reply.text))
            status.text = reply.provider?.let { "Jawab: $it" } ?: getString(R.string.idle)
            setBusy(false)
            if (speak && ttsReady) tts?.speak(reply.text, TextToSpeech.QUEUE_FLUSH, null, "reply")
        }
    }

    /**
     * The conversation as the model should see it: no error bubbles, starting with a
     * user turn, and only the most recent turns so requests stay small and cheap.
     */
    private fun historyForModel(): List<ChatMessage> =
        bubbles.filter { it.kind != Kind.ERROR }
            .map { ChatMessage(fromUser = it.kind == Kind.USER, text = it.text) }
            // A failed question leaves two user turns in a row; keep only the newer one.
            .fold(mutableListOf<ChatMessage>()) { acc, m ->
                if (acc.isNotEmpty() && acc.last().fromUser == m.fromUser) acc[acc.lastIndex] = m else acc += m
                acc
            }
            .takeLast(MAX_HISTORY)
            .dropWhile { !it.fromUser }

    private fun setBusy(value: Boolean) {
        busy = value
        val alpha = if (value) 0.45f else 1f
        talkButton.alpha = alpha
        sendButton.alpha = alpha
    }

    // ---- Look: theme colours and text size --------------------------------

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp)
        if (strokeColor != null) setStroke(dp(1f).toInt().coerceAtLeast(1), strokeColor)
    }

    /** Paints every fixed view from the chosen theme and size, then redraws the bubbles. */
    private fun applyLook() {
        val p = Look.theme(settings.themeId)
        val scale = Look.size(settings.sizeId).scale
        fun TextView.sp(size: Float) = setTextSize(TypedValue.COMPLEX_UNIT_SP, size * scale)

        root.setBackgroundColor(p.bg)
        divider.setBackgroundColor(p.line)
        title.setTextColor(p.ink); title.sp(20f)
        status.setTextColor(p.mute); status.sp(12f)
        for (link in listOf(newChatButton, settingsButton)) { link.setTextColor(p.accent); link.sp(14f) }

        input.background = rounded(p.surface, 24f, p.line)
        input.setTextColor(p.ink); input.setHintTextColor(p.mute); input.sp(16f)

        // Filled buttons carry dark text because every accent colour is a light one.
        sendButton.background = rounded(p.accent, 24f)
        sendButton.setTextColor(p.bg); sendButton.sp(15f)
        talkButton.background = rounded(p.surface, 24f, p.accent)
        talkButton.setTextColor(p.accent); talkButton.sp(15f)

        renderBubbles()
    }

    private fun renderBubbles() {
        messages.removeAllViews()
        bubbles.forEach { messages.addView(bubbleView(it)) }
        scrollToEnd()
    }

    private fun addBubble(bubble: Bubble) {
        bubbles += bubble
        messages.addView(bubbleView(bubble))
        scrollToEnd()
    }

    private fun scrollToEnd() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun bubbleView(b: Bubble): View {
        val p = Look.theme(settings.themeId)
        val scale = Look.size(settings.sizeId).scale
        val padH = dp(14f).toInt()
        val padV = dp(10f).toInt()
        return TextView(this).apply {
            text = b.text
            setTextIsSelectable(true)
            setPadding(padH, padV, padH, padV)
            // Bubbles stop at 82% of the screen so it is clear at a glance who said what.
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            when (b.kind) {
                Kind.USER -> {
                    background = rounded(p.accent, 18f)
                    setTextColor(p.bg)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale)
                }
                Kind.FRIDAY -> {
                    background = rounded(p.surface, 18f, p.line)
                    setTextColor(p.ink)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale)
                }
                Kind.ERROR -> {
                    background = rounded(p.bg, 12f, p.line)
                    setTextColor(p.mute)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * scale)
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = if (b.kind == Kind.USER) Gravity.END else Gravity.START
                bottomMargin = dp(8f).toInt()
            }
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
        val geminiModel = field("Gemini models (comma se alag)", settings.geminiModel)
        val groqKey = field("Groq API key (optional)", settings.groqKey, secret = true)
        val groqModel = field("Groq model", settings.groqModel)
        fun picker(labels: List<String>, selected: Int) = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(selected.coerceAtLeast(0))
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            setPadding(0, dp(12f).toInt(), 0, 0)
        }
        val themePicker = picker(Look.THEMES.map { it.label }, Look.THEMES.indexOfFirst { it.id == settings.themeId })
        val sizePicker = picker(Look.SIZES.map { it.label }, Look.SIZES.indexOfFirst { it.id == settings.sizeId })
        val pad = dp(20f).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label("Theme")); addView(themePicker)
            addView(label("Text ka size")); addView(sizePicker)
            addView(label("AI models"))
            listOf(geminiKey, geminiModel, groqKey, groqModel).forEach(::addView)
        }
        AlertDialog.Builder(this)
            .setTitle("Settings (build ${Updater.installedCode(this)})")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.themeId = Look.THEMES[themePicker.selectedItemPosition].id
                settings.sizeId = Look.SIZES[sizePicker.selectedItemPosition].id
                applyLook()
                settings.geminiKey = geminiKey.text.toString()
                settings.geminiModel = geminiModel.text.toString()
                settings.groqKey = groqKey.text.toString()
                settings.groqModel = groqModel.text.toString()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("Update check") { _, _ ->
                status.text = "Update check ho raha hai..."
                checkForUpdate(manual = true)
            }
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
            setBusy(false)
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
        /** Turns sent to the model per request; older ones are dropped from the request, not the screen. */
        const val MAX_HISTORY = 20

        /** Coming back to the app within this time does not hit the site again. */
        const val UPDATE_CHECK_GAP_MS = 60_000L

        const val SYSTEM_PROMPT =
            "You are Friday, a personal assistant on the user's Android phone. " +
                "Replies appear in a small chat bubble and are sometimes read aloud, so keep them " +
                "short and conversational with no markdown, lists, or emojis. Reply in Hinglish (Hindi " +
                "written in Roman script mixed with English) unless the user writes pure English."
    }
}
