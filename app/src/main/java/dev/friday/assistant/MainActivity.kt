package dev.friday.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dev.friday.assistant.llm.ChatMessage
import dev.friday.assistant.llm.GeminiProvider
import dev.friday.assistant.llm.GroqProvider
import dev.friday.assistant.llm.LlmRouter
import dev.friday.assistant.tools.PhoneTools
import dev.friday.assistant.tools.UserPrompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

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
    private lateinit var talkButton: ImageView
    private lateinit var sendButton: ImageView
    private lateinit var chatsButton: ImageView
    private lateinit var newChatButton: ImageView
    private lateinit var settingsButton: ImageView

    /** What a bubble on screen holds. Only USER and FRIDAY are saved and sent to the model; TOOL lines show what Friday did. */
    private enum class Kind { USER, FRIDAY, ERROR, TOOL }
    private data class Bubble(val kind: Kind, val text: String)

    /** Everything on screen for the open chat, oldest first. */
    private val bubbles = mutableListOf<Bubble>()
    private var busy = false

    private lateinit var settings: Settings
    private lateinit var chats: ChatStore
    private lateinit var phoneTools: PhoneTools
    private var chatId = ""
    private lateinit var style: Style

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var micPulse: ObjectAnimator? = null
    private var typingView: View? = null
    private val typingAnimators = mutableListOf<ObjectAnimator>()

    /** Built per question so a key changed in Settings applies immediately. */
    private fun buildRouter() = LlmRouter(
        settings.geminiModels.map { GeminiProvider(settings.geminiKey, it) } +
            GroqProvider(settings.groqKey, settings.groqModel),
    )

    /** Set while we wait for the user to allow "install unknown apps". */
    private var pendingUpdate: Updater.Update? = null
    private var updateDialogShowing = false
    private var lastUpdateCheck = 0L

    private var contactsAnswer: CompletableDeferred<Boolean>? = null
    private val askContacts =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            contactsAnswer?.complete(granted)
        }

    /** Suspends the running tool until the user answers the permission prompt. */
    private suspend fun ensureContacts(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        ) return true
        val answer = CompletableDeferred<Boolean>()
        contactsAnswer = answer
        askContacts.launch(Manifest.permission.READ_CONTACTS)
        return answer.await()
    }

    /** The questions tools put to the user: pick from a list, or confirm before removing something. */
    private val prompts = object : UserPrompts {
        override suspend fun ensureContacts() = this@MainActivity.ensureContacts()

        override suspend fun choose(title: String, options: List<String>): Int? =
            suspendCancellableCoroutine { cont ->
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(title)
                    .setItems(options.toTypedArray()) { _, which -> if (cont.isActive) cont.resume(which) }
                    .setNegativeButton("Rehne do", null)
                    .setOnDismissListener { if (cont.isActive) cont.resume(null) }
                    .create()
                cont.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
            }

        override suspend fun confirm(message: String, yes: String): Boolean =
            suspendCancellableCoroutine { cont ->
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(yes) { _, _ -> if (cont.isActive) cont.resume(true) }
                    .setNegativeButton("Nahi", null)
                    .setOnDismissListener { if (cont.isActive) cont.resume(false) }
                    .create()
                cont.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
            }
    }

    private val askMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else status.text = "Mic permission ke bina kaam nahi chalega"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        chats = ChatStore(this)
        phoneTools = PhoneTools(this, settings, prompts)

        root = findViewById(R.id.root)
        title = findViewById(R.id.title)
        status = findViewById(R.id.status)
        divider = findViewById(R.id.divider)
        scroll = findViewById(R.id.scroll)
        messages = findViewById(R.id.messages)
        input = findViewById(R.id.input)
        talkButton = findViewById(R.id.talkButton)
        sendButton = findViewById(R.id.sendButton)
        chatsButton = findViewById(R.id.chatsButton)
        newChatButton = findViewById(R.id.newChatButton)
        settingsButton = findViewById(R.id.settingsButton)

        tts = TextToSpeech(this) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale("en", "IN")
        }

        settingsButton.setOnClickListener { showSettings() }
        chatsButton.setOnClickListener { if (!busy) showChats() }
        newChatButton.setOnClickListener { if (!busy) openChat(null) }
        sendButton.setOnClickListener { sendTyped() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }
        talkButton.setOnClickListener {
            if (busy) return@setOnClickListener
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) startListening() else askMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Reopen the most recent chat, so closing the app does not lose the conversation.
        openChat(chats.list().firstOrNull()?.id)
    }

    override fun onResume() {
        super.onResume()
        val update = pendingUpdate
        if (update == null) checkForUpdate()
        else if (Updater.canInstall(this)) startUpdate(update)
    }

    override fun onDestroy() {
        stopMicPulse()
        stopTyping()
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---- Chats: open, save, list ------------------------------------------

    /** Shows the saved chat with [id], or starts an empty one when [id] is null. */
    private fun openChat(id: String?) {
        tts?.stop()
        bubbles.clear()
        if (id == null) {
            chatId = chats.newId()
        } else {
            chatId = id
            chats.load(id).forEach { bubbles += Bubble(if (it.fromUser) Kind.USER else Kind.FRIDAY, it.text) }
        }
        status.text = getString(R.string.idle)
        applyLook()
    }

    private fun saveChat() {
        chats.save(chatId, bubbles.filter { it.kind == Kind.USER || it.kind == Kind.FRIDAY }.map { ChatMessage(it.kind == Kind.USER, it.text) })
    }

    private fun showChats() {
        val list = chats.list()
        if (list.isEmpty()) {
            status.text = "Abhi koi purana chat nahi hai"
            return
        }
        val labels = list.map {
            val whenText = DateUtils.getRelativeTimeSpanString(
                it.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            )
            "${it.title}\n$whenText"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.chats)
            .setItems(labels.toTypedArray()) { _, which -> openChat(list[which].id) }
            .setNeutralButton("Ye chat delete karo") { _, _ ->
                chats.delete(chatId)
                openChat(chats.list().firstOrNull()?.id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Asking -----------------------------------------------------------

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy) return
        input.text.clear()
        playSendAnimation()
        onHeard(text, speak = false) // typed questions get a silent, on-screen answer
    }

    private fun onHeard(text: String, speak: Boolean = true) {
        addBubble(Bubble(Kind.USER, text))
        saveChat()
        status.text = "Soch raha hu..."
        setBusy(true)
        showTyping()
        lifecycleScope.launch {
            val reply = buildRouter().ask(systemPrompt(), historyForModel(), phoneTools.specs) { call ->
                val output = phoneTools.run(call)
                addBubble(Bubble(Kind.TOOL, output))
                output
            }
            stopTyping()
            // No Android Studio means no logcat, so failures are shown on screen.
            reply.errors.forEach { addBubble(Bubble(Kind.ERROR, it)) }
            // A canned "nobody answered" line is not Friday speaking, so it must not enter the history.
            addBubble(Bubble(if (reply.provider != null) Kind.FRIDAY else Kind.ERROR, reply.text))
            saveChat()
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
        bubbles.filter { it.kind == Kind.USER || it.kind == Kind.FRIDAY }
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
        sendButton.alpha = alpha
        // The mic stays fully visible while it is the thing that is busy (listening).
        talkButton.alpha = if (value && micPulse == null) 0.45f else 1f
    }

    // ---- Voice ------------------------------------------------------------

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
        startMicPulse()
        setBusy(true)
        status.text = "Sun raha hu..."
        sr.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isBlank()) {
                onError(SpeechRecognizer.ERROR_NO_MATCH)
            } else {
                stopMicPulse()
                onHeard(text)
            }
        }

        override fun onError(error: Int) {
            stopMicPulse()
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

    // ---- Look: colours, sizes, shapes -------------------------------------

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp)
        if (strokeColor != null) setStroke(dp(1f).toInt().coerceAtLeast(1), strokeColor)
    }

    private fun TextView.sp(size: Float) = setTextSize(TypedValue.COMPLEX_UNIT_SP, size * style.scale)

    private fun bodyFont(): Typeface =
        if (style.lightFont) Typeface.create("sans-serif-light", Typeface.NORMAL) else Typeface.DEFAULT

    /** Paints every fixed view from the chosen theme, look and size, then redraws the bubbles. */
    private fun applyLook() {
        style = Look.style(settings.themeId, settings.lookId, settings.sizeId)
        val s = style

        root.setBackgroundColor(s.bg)
        // On a light background the clock and battery icons must turn dark to stay visible.
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = s.lightBars
            isAppearanceLightNavigationBars = s.lightBars
        }
        divider.setBackgroundColor(s.line)

        title.setTextColor(s.ink); title.sp(20f)
        title.typeface = if (s.lightFont) bodyFont() else Typeface.DEFAULT_BOLD
        status.setTextColor(s.mute); status.sp(12f)
        for (icon in listOf(chatsButton, newChatButton, settingsButton)) icon.setColorFilter(s.mute)

        input.background = rounded(s.surface, 24f, s.line)
        input.setTextColor(s.ink); input.setHintTextColor(s.mute); input.sp(16f)
        input.typeface = bodyFont()

        sendButton.background = rounded(s.accent, 24f)
        sendButton.setColorFilter(s.onAccent)
        paintMic(listening = micPulse != null)

        renderBubbles()
    }

    /** Outlined when idle, filled with the accent colour while listening. */
    private fun paintMic(listening: Boolean) {
        val s = style
        talkButton.background = if (listening) rounded(s.accent, 24f) else rounded(s.surface, 24f, s.accent)
        talkButton.setColorFilter(if (listening) s.onAccent else s.accent)
    }

    private fun renderBubbles() {
        messages.removeAllViews()
        bubbles.forEach { messages.addView(bubbleView(it)) }
        typingView?.let { messages.addView(it) }
        scrollToEnd()
    }

    private fun addBubble(bubble: Bubble) {
        bubbles += bubble
        val view = bubbleView(bubble)
        // Keep the typing dots at the very bottom while a reply is still on its way.
        messages.addView(view, if (typingView != null) messages.childCount - 1 else messages.childCount)
        animateIn(view, fromRight = bubble.kind == Kind.USER)
        scrollToEnd()
    }

    private fun scrollToEnd() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun bubbleParams(alignEnd: Boolean) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = if (alignEnd) Gravity.END else Gravity.START
        bottomMargin = dp(8f).toInt()
    }

    private fun bubbleView(b: Bubble): View {
        val s = style
        val padH = dp(14f).toInt()
        val padV = dp(10f).toInt()
        return TextView(this).apply {
            text = b.text
            typeface = bodyFont()
            setTextIsSelectable(true)
            setPadding(padH, padV, padH, padV)
            // Bubbles stop at 82% of the screen so it is clear at a glance who said what.
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            when (b.kind) {
                Kind.USER -> {
                    // Minimal keeps your messages quiet: a tinted surface instead of a solid accent block.
                    if (s.plainReplies) {
                        background = rounded(s.surface, s.bubbleRadiusDp, s.line)
                        setTextColor(s.ink)
                    } else {
                        background = rounded(s.accent, s.bubbleRadiusDp)
                        setTextColor(s.onAccent)
                    }
                    sp(16f)
                }
                Kind.FRIDAY -> {
                    if (s.plainReplies) {
                        setPadding(0, padV, 0, padV)
                    } else {
                        background = rounded(s.surface, s.bubbleRadiusDp, s.line)
                    }
                    setTextColor(s.ink)
                    sp(16f)
                }
                Kind.ERROR -> {
                    background = rounded(s.bg, 12f, s.line)
                    setTextColor(s.mute)
                    sp(12f)
                }
                Kind.TOOL -> {
                    text = "⚙ ${b.text}"
                    background = rounded(s.bg, 12f, s.accent)
                    setTextColor(s.accent)
                    sp(12f)
                }
            }
            layoutParams = bubbleParams(alignEnd = b.kind == Kind.USER)
        }
    }

    // ---- Motion -----------------------------------------------------------
    // Animators honour the phone's "remove animations" accessibility setting on their own.

    /** New bubbles rise into place; the Animated look also slides them in from their side with a bounce. */
    private fun animateIn(view: View, fromRight: Boolean) {
        view.alpha = 0f
        view.translationY = dp(10f)
        val anim = view.animate().alpha(1f).translationY(0f).setDuration(220)
        if (style.lively) {
            view.translationX = dp(if (fromRight) 40f else -40f)
            anim.translationX(0f).setDuration(380).setInterpolator(OvershootInterpolator(1.4f))
        }
        anim.start()
    }

    /** The arrow flies up and out, then drops back in: a small confirmation that the message left. */
    private fun playSendAnimation() {
        sendButton.animate().cancel()
        sendButton.animate().translationY(-dp(14f)).alpha(0f).setDuration(140).withEndAction {
            sendButton.translationY = dp(14f)
            sendButton.animate().translationY(0f).alpha(if (busy) 0.45f else 1f).setDuration(180).start()
        }.start()
    }

    /** The mic breathes while Friday is listening, so it is obvious when to speak. */
    private fun startMicPulse() {
        if (micPulse != null) return
        paintMic(listening = true)
        micPulse = ObjectAnimator.ofPropertyValuesHolder(
            talkButton,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f),
        ).apply {
            duration = 550
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopMicPulse() {
        micPulse?.cancel()
        micPulse = null
        talkButton.scaleX = 1f
        talkButton.scaleY = 1f
        if (::style.isInitialized) paintMic(listening = false)
    }

    /** Three dots that light up one after another while a reply is on its way. */
    private fun showTyping() {
        stopTyping()
        val s = style
        val dot = dp(8f).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(14f).toInt())
            if (!s.plainReplies) background = rounded(s.surface, s.bubbleRadiusDp, s.line)
            layoutParams = bubbleParams(alignEnd = false)
        }
        repeat(3) { i ->
            val v = View(this).apply {
                background = rounded(s.mute, 4f)
                alpha = 0.25f
                layoutParams = LinearLayout.LayoutParams(dot, dot).apply { if (i > 0) marginStart = dp(6f).toInt() }
            }
            row.addView(v)
            typingAnimators += ObjectAnimator.ofFloat(v, View.ALPHA, 0.25f, 1f).apply {
                duration = 450
                startDelay = i * 150L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
        typingView = row
        messages.addView(row)
        scrollToEnd()
    }

    private fun stopTyping() {
        typingAnimators.forEach { it.cancel() }
        typingAnimators.clear()
        typingView?.let { messages.removeView(it) }
        typingView = null
    }

    // ---- Updates ----------------------------------------------------------

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

    // ---- Settings ---------------------------------------------------------

    private fun showSettings() {
        // Keys are shown as dots so a screenshot of this dialog cannot leak them.
        fun field(label: String, value: String, secret: Boolean = false) = EditText(this).apply {
            hint = label
            inputType = InputType.TYPE_CLASS_TEXT or
                if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(value)
        }
        fun picker(labels: List<String>, selected: Int) = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(selected.coerceAtLeast(0))
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            setPadding(0, dp(12f).toInt(), 0, 0)
        }
        val geminiKey = field("Gemini API key", settings.geminiKey, secret = true)
        val geminiModel = field("Gemini models (comma se alag)", settings.geminiModel)
        val groqKey = field("Groq API key (optional)", settings.groqKey, secret = true)
        val groqModel = field("Groq model", settings.groqModel)
        val lookPicker = picker(Look.LOOKS.map { it.label }, Look.LOOKS.indexOfFirst { it.id == settings.lookId })
        val themePicker = picker(Look.THEMES.map { it.label }, Look.THEMES.indexOfFirst { it.id == settings.themeId })
        val sizePicker = picker(Look.SIZES.map { it.label }, Look.SIZES.indexOfFirst { it.id == settings.sizeId })
        // Only worth showing when there is a real choice to make.
        val clockApps = phoneTools.clockApps()
        val clockPicker = if (clockApps.size > 1) {
            picker(
                listOf("Har baar poocho") + clockApps.map { it.label },
                clockApps.indexOfFirst { it.pkg == settings.alarmPackage } + 1,
            )
        } else null
        val pad = dp(20f).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label("Look")); addView(lookPicker)
            addView(label("Theme (iOS looks ke apne colours hain)")); addView(themePicker)
            addView(label("Text ka size")); addView(sizePicker)
            clockPicker?.let { addView(label("Alarm ke liye Clock app")); addView(it) }
            addView(label("AI models"))
            listOf(geminiKey, geminiModel, groqKey, groqModel).forEach(::addView)
        }
        AlertDialog.Builder(this)
            .setTitle("Settings (build ${Updater.installedCode(this)})")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.lookId = Look.LOOKS[lookPicker.selectedItemPosition].id
                settings.themeId = Look.THEMES[themePicker.selectedItemPosition].id
                settings.sizeId = Look.SIZES[sizePicker.selectedItemPosition].id
                clockPicker?.let { picker ->
                    settings.alarmPackage = clockApps.getOrNull(picker.selectedItemPosition - 1)?.pkg
                }
                settings.geminiKey = geminiKey.text.toString()
                settings.geminiModel = geminiModel.text.toString()
                settings.groqKey = groqKey.text.toString()
                settings.groqModel = groqModel.text.toString()
                applyLook()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("Update check") { _, _ ->
                status.text = "Update check ho raha hai..."
                checkForUpdate(manual = true)
            }
            .show()
    }

    /** Rebuilt for every question, because "7 baje" or "20 minute mein" only means something next to the current time. */
    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        return "$BASE_PROMPT Current local time: $now (${TimeZone.getDefault().id})."
    }

    private companion object {
        /** Turns sent to the model per request; older ones are dropped from the request, not the screen. */
        const val MAX_HISTORY = 20

        /** Coming back to the app within this time does not hit the site again. */
        const val UPDATE_CHECK_GAP_MS = 60_000L

        const val BASE_PROMPT =
            "You are Friday, a personal assistant on the user's Android phone. " +
                "Replies appear in a small chat bubble and are sometimes read aloud, so keep them " +
                "short and conversational with no markdown, lists, or emojis. Reply in Hinglish (Hindi " +
                "written in Roman script mixed with English) unless the user writes pure English. " +
                "You can set and cancel alarms, start phone calls and switch the flashlight with your tools. " +
                "Never guess a missing detail. If the time (including AM or PM), the contact or the alarm " +
                "is unclear, ask one short question first and wait for the answer. To remove an alarm you " +
                "need its exact time: if the user does not give it, use get_next_alarm and ask whether that " +
                "is the one. Say a task is done only if the tool result says it was confirmed; if the " +
                "result says it could not confirm, say exactly that and tell the user to check. " +
                "For an alarm like 'in 20 minutes', work out the exact time from the current time below."
    }
}
