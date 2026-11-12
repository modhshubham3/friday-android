package dev.friday.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.animation.doOnEnd
import androidx.core.view.WindowCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.friday.assistant.llm.ChatMessage
import dev.friday.assistant.tools.PhoneTools
import dev.friday.assistant.tools.UserPrompts
import dev.friday.assistant.voice.FridayService
import dev.friday.assistant.voice.FridayTileService
import dev.friday.assistant.voice.Listener
import dev.friday.assistant.voice.ModelStore
import dev.friday.assistant.voice.WakeTest
import dev.friday.assistant.voice.Speaker
import dev.friday.assistant.inbox.DigestScheduler
import dev.friday.assistant.inbox.Inbox
import dev.friday.assistant.inbox.InboxActivity
import dev.friday.assistant.ui.AuroraView
import dev.friday.assistant.ui.OrbView
import dev.friday.assistant.voice.Mode
import dev.friday.assistant.voice.VoiceLog
import dev.friday.assistant.voice.VoiceState
import dev.friday.assistant.voice.Voices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The chat screen. A question arrives typed or spoken (SpeechRecognizer), goes to
 * LlmRouter together with the conversation so far, and the reply is shown as a
 * bubble and, for spoken questions, read aloud (TextToSpeech).
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: View
    private lateinit var aurora: AuroraView
    private lateinit var orbMain: OrbView
    private lateinit var orbHeader: OrbView
    private lateinit var emptyState: View
    private lateinit var greeting: TextView
    private lateinit var chips: LinearLayout
    private var shimmer: ValueAnimator? = null
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var divider: View
    private lateinit var scroll: ScrollView
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private lateinit var talkButton: ImageView
    private lateinit var sendButton: ImageView
    private lateinit var chatsButton: ImageView
    private lateinit var inboxButton: ImageView
    private lateinit var newChatButton: ImageView
    private lateinit var settingsButton: ImageView

    /** What a bubble on screen holds. Only USER and FRIDAY are saved and sent to the model; TOOL lines show what Friday did. */
    private enum class Kind { USER, FRIDAY, ERROR, TOOL }
    private data class Bubble(val kind: Kind, val text: String, val viaTool: Boolean = false)

    /** Everything on screen for the open chat, oldest first. */
    private val bubbles = mutableListOf<Bubble>()
    private var busy = false

    private lateinit var settings: Settings
    private lateinit var chats: ChatStore
    private lateinit var phoneTools: PhoneTools
    private lateinit var spokenTools: PhoneTools
    private var chatId = ""
    private lateinit var style: Style

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var micPulse: ObjectAnimator? = null
    private var typingView: View? = null
    private val typingAnimators = mutableListOf<ObjectAnimator>()

    /** Set while we wait for the user to allow "install unknown apps". */
    private var pendingUpdate: Updater.Update? = null
    private var updateDialogShowing = false
    private var lastUpdateCheck = 0L

    private var permissionAnswer: CompletableDeferred<Boolean>? = null
    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionAnswer?.complete(granted)
        }

    /** Suspends the running tool until the user answers the permission prompt. */
    private suspend fun ensurePermission(permission: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return true
        val answer = CompletableDeferred<Boolean>()
        permissionAnswer = answer
        askPermission.launch(permission)
        return answer.await()
    }

    /** The questions tools put to the user: pick from a list, or confirm before removing something. */
    private val prompts = object : UserPrompts {
        override suspend fun ensureContacts() = ensurePermission(Manifest.permission.READ_CONTACTS)

        override suspend fun ensureCallPhone() = ensurePermission(Manifest.permission.CALL_PHONE)

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

        override suspend fun ask(question: String): String? =
            suspendCancellableCoroutine { cont ->
                val box = EditText(this@MainActivity)
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setMessage(question)
                    .setView(box)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        if (cont.isActive) cont.resume(box.text.toString().trim().ifEmpty { null })
                    }
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
        spokenTools = PhoneTools(this, settings, prompts, english = true)

        root = findViewById(R.id.root)
        title = findViewById(R.id.title)
        status = findViewById(R.id.status)
        // The line only takes space while it has something to say (a reply time, an error, a download).
        status.doOnTextChanged { text, _, _, _ -> status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE }
        divider = findViewById(R.id.divider)
        scroll = findViewById(R.id.scroll)
        messages = findViewById(R.id.messages)
        input = findViewById(R.id.input)
        talkButton = findViewById(R.id.talkButton)
        sendButton = findViewById(R.id.sendButton)
        chatsButton = findViewById(R.id.chatsButton)
        inboxButton = findViewById(R.id.inboxButton)
        newChatButton = findViewById(R.id.newChatButton)
        settingsButton = findViewById(R.id.settingsButton)
        aurora = findViewById(R.id.aurora)
        orbMain = findViewById(R.id.orbMain)
        orbHeader = findViewById(R.id.orbHeader)
        emptyState = findViewById(R.id.emptyState)
        greeting = findViewById(R.id.greeting)
        chips = findViewById(R.id.chips)
        inboxButton.setOnClickListener { startActivity(Intent(this, InboxActivity::class.java)) }
        pressable(inboxButton, chatsButton, newChatButton, settingsButton, sendButton, orbHeader, orbMain)
        DigestScheduler.schedule(this) // makes sure tonight's summary is armed
        // The orbs are a button too: tap Friday to talk to Friday.
        orbHeader.setOnClickListener { talkButton.performClick() }
        orbMain.setOnClickListener { talkButton.performClick() }
        // Whatever Friday is doing, whether started here or by "Hey Friday", the orbs show it.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { VoiceState.mode.collect { orbMain.mode = it; orbHeader.mode = it } }
                launch { VoiceState.level.collect { orbMain.level = it; orbHeader.level = it } }
            }
        }

        tts = TextToSpeech(this) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.let { Voices.apply(it, settings) }
                // While Friday speaks, the wake word listener sits out, or it hears the name in Friday's own voice.
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        VoiceState.mode.value = Mode.IDLE
                        FridayService.resume(this@MainActivity)
                    }

                    @Deprecated("Required by the base class")
                    override fun onError(utteranceId: String?) {
                        VoiceState.mode.value = Mode.IDLE
                        FridayService.resume(this@MainActivity)
                    }
                })
            }
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

    override fun onPause() {
        shimmer?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        shimmer?.resume()
        val update = pendingUpdate
        if (update == null) checkForUpdate()
        else if (Updater.canInstall(this)) startUpdate(update)
        resumeVoiceIfWanted()
    }

    /**
     * Android will not start a microphone service in the background, but opening the app is allowed to.
     * So if Hey Friday was on and has since stopped (phone restarted, app killed), opening Friday turns it back on.
     */
    private fun resumeVoiceIfWanted() {
        if (!settings.voiceWanted || FridayService.running) return
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micOk && ModelStore.isReady(this)) {
            FridayService.start(this)
            status.text = "Hey Friday dobara chalu kiya"
        }
    }

    override fun onDestroy() {
        shimmer?.cancel()
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
        greeting.text = "" // so a new chat greets again
        bubbles.clear()
        if (id == null) {
            chatId = chats.newId()
        } else {
            chatId = id
            chats.load(id).forEach {
                val kind = when (it.kind) { "user" -> Kind.USER; "tool" -> Kind.TOOL; else -> Kind.FRIDAY }
                bubbles += Bubble(kind, it.text, it.viaTool)
            }
        }
        status.text = getString(R.string.idle)
        applyLook()
    }

    private fun saveChat() {
        chats.save(
            chatId,
            bubbles.filter { it.kind != Kind.ERROR }.map {
                StoredBubble(it.kind.name.lowercase(), it.text, it.viaTool)
            },
        )
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
        val userIndex = bubbles.lastIndex
        saveChat()
        status.text = "Soch rahi hu..."
        setBusy(true)
        VoiceState.mode.value = Mode.THINKING
        showTyping()
        val startedAt = System.currentTimeMillis()
        lifecycleScope.launch {
            // A question that will be read out is answered in English when an English voice is chosen.
            val english = settings.speakEnglish
            val tools = if (speak) spokenTools else phoneTools
            val reply = Assistant.fastReply(text, tools) ?: Assistant.router(settings).ask(
                Assistant.systemPrompt(spoken = speak, english = english, facts = Inbox.facts(this@MainActivity)), historyForModel(), tools.specs,
            ) { call ->
                val output = tools.run(call)
                // A plain result is shown as the reply itself; only ones the model must explain appear here.
                if (!output.final) addBubble(Bubble(Kind.TOOL, output.text, viaTool = true))
                output
            }
            stopTyping()
            // No Android Studio means no logcat, so failures are shown on screen.
            reply.errors.forEach { addBubble(Bubble(Kind.ERROR, it)) }
            if (reply.usedTools) bubbles[userIndex] = bubbles[userIndex].copy(viaTool = true)
            // A canned "nobody answered" line is not Friday speaking, so it must not enter the history.
            val kind = when {
                reply.provider == null -> Kind.ERROR
                reply.direct -> Kind.TOOL
                else -> Kind.FRIDAY
            }
            addBubble(Bubble(kind, reply.text, viaTool = reply.usedTools))
            saveChat()
            // The time is shown so it is plain when a reply was slow, and which model caused it.
            val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
            status.text = reply.provider?.let { "Jawab: $it · ${"%.1f".format(seconds)}s" } ?: getString(R.string.idle)
            setBusy(false)
            VoiceState.mode.value = if (speak && ttsReady) Mode.SPEAKING else Mode.IDLE
            if (speak && ttsReady) {
                FridayService.pause(this@MainActivity)
                tts?.let { Voices.apply(it, settings, reply.text) }
                tts?.speak(Speaker.speakable(reply.text), TextToSpeech.QUEUE_FLUSH, null, "reply")
            }
        }
    }

    /**
     * The conversation as the model should see it: no error bubbles, starting with a user turn,
     * and only the most recent turns so requests stay small and cheap.
     *
     * Turns in which Friday used a tool are left out. The history holds only text, so a past
     * "call Gauri" answered "call laga raha hu" looks, to the model, like a request that is
     * answered in words alone, and it copies that instead of calling the tool.
     */
    private fun historyForModel(): List<ChatMessage> =
        bubbles.filter { (it.kind == Kind.USER || it.kind == Kind.FRIDAY) && !it.viaTool }
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
        val intent = Listener.request(this, settings)
        FridayService.pause(this)
        startMicPulse()
        setBusy(true)
        VoiceState.mode.value = Mode.LISTENING
        status.text = "Sun rahi hu..."
        // The wake word listener holds the microphone; give it a moment to let go before recognition starts.
        val wait = if (FridayService.running) 350L else 0L
        root.postDelayed({ sr.startListening(intent) }, wait)
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
        override fun onRmsChanged(rmsdB: Float) = VoiceState.hear(rmsdB)
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { status.text = "Samajh rahi hu..." }
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
        for (icon in listOf(inboxButton, chatsButton, newChatButton, settingsButton)) icon.setColorFilter(s.mute)

        input.background = rounded(s.surface, 24f, s.line)
        input.setTextColor(s.ink); input.setHintTextColor(s.mute); input.sp(16f)
        input.typeface = bodyFont()

        sendButton.background = rounded(s.accent, 24f)
        sendButton.setColorFilter(s.onAccent)
        paintMic(listening = micPulse != null)

        applyFx(s)
        renderBubbles()
    }

    /** Outlined when idle, filled with the accent colour while listening. */
    private fun paintMic(listening: Boolean) {
        val s = style
        talkButton.background = if (listening) rounded(s.accent, 24f) else rounded(s.surface, 24f, s.accent)
        talkButton.setColorFilter(if (listening) s.onAccent else s.accent)
    }

    private fun renderBubbles() {
        val empty = bubbles.isEmpty()
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        emptyState.alpha = 1f
        if (empty && greeting.text.isEmpty()) typewrite(greeting, greetingLine())
        messages.removeAllViews()
        bubbles.forEach { messages.addView(bubbleView(it)) }
        typingView?.let { messages.addView(it) }
        scrollToEnd()
    }

    private fun addBubble(bubble: Bubble) {
        bubbles += bubble
        updateEmptyState()
        val view = bubbleView(bubble, typewriter = true)
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

    private fun bubbleView(b: Bubble, typewriter: Boolean = false): View {
        val s = style
        val padH = dp(14f).toInt()
        val padV = dp(10f).toInt()
        val view = TextView(this).apply {
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
        // Friday's words appear letter by letter, as if being written.
        if (typewriter && (b.kind == Kind.FRIDAY || b.kind == Kind.TOOL)) typewrite(view, b.text)
        return view
    }

    // ---- Alive: orbs, drifting light, greeting, writing effect -----------------

    /** Light, colour and suggestions that depend on the chosen look. */
    private fun applyFx(s: Style) {
        aurora.configure(s.accent, s.aurora)
        orbMain.accent = s.accent
        orbHeader.accent = s.accent
        greeting.setTextColor(s.ink)
        greeting.sp(18f)
        greeting.typeface = bodyFont()
        chips.removeAllViews()
        listOf("Kya aaya?", "Torch on karo", "Aaj ka summary").forEach { chips.addView(chip(it, s)) }
        shimmerTitle(s)
    }

    private fun greetingLine(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val part = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
        return "$part, boss.\nMain Friday hu. Kya karna hai?"
    }

    /** The big orb and the suggestions fade away once a chat begins, and return for a new one. */
    private fun updateEmptyState() {
        val show = bubbles.isEmpty()
        if (show == (emptyState.visibility == View.VISIBLE)) return
        if (show) {
            emptyState.visibility = View.VISIBLE
            emptyState.alpha = 0f
            emptyState.animate().alpha(1f).setDuration(350).start()
            typewrite(greeting, greetingLine())
        } else {
            emptyState.animate().alpha(0f).setDuration(220).withEndAction { emptyState.visibility = View.GONE }.start()
        }
    }

    private fun chip(label: String, s: Style) = TextView(this).apply {
        text = label
        setTextColor(s.ink)
        sp(14f)
        typeface = bodyFont()
        gravity = Gravity.CENTER
        setPadding(dp(18f).toInt(), dp(10f).toInt(), dp(18f).toInt(), dp(10f).toInt())
        background = rounded(s.surface, 22f, s.accent)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8f).toInt() }
        setOnClickListener { if (!busy) onHeard(label, speak = false) }
        pressable(this)
    }

    /** Shows [full] one letter at a time. With animations turned off in the phone's settings it just appears. */
    private fun typewrite(view: TextView, full: String) {
        (view.tag as? ValueAnimator)?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled() || full.length < 2) {
            view.text = full
            return
        }
        view.text = ""
        view.tag = ValueAnimator.ofInt(0, full.length).apply {
            duration = (full.length * 16L).coerceIn(250L, 1_500L)
            interpolator = LinearInterpolator()
            addUpdateListener { view.text = full.substring(0, it.animatedValue as Int) }
            doOnEnd { view.text = full }
            start()
        }
    }

    /** A band of colour sweeps slowly across the name in the header. */
    private fun shimmerTitle(s: Style) {
        shimmer?.cancel()
        title.paint.shader = null
        if (!ValueAnimator.areAnimatorsEnabled() || s.lightFont) return // Minimal stays plain
        title.post {
            val width = title.width.toFloat().coerceAtLeast(1f)
            val shader = LinearGradient(
                0f, 0f, width, 0f, intArrayOf(s.ink, s.accent, s.ink), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.MIRROR,
            )
            title.paint.shader = shader
            val matrix = Matrix()
            shimmer = ValueAnimator.ofFloat(0f, width * 2f).apply {
                duration = 4_200
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    matrix.setTranslate(it.animatedValue as Float, 0f)
                    shader.setLocalMatrix(matrix)
                    title.invalidate()
                }
                start()
            }
        }
    }

    /** Buttons shrink a little under the finger and spring back. Returns false so the tap itself still works. */
    @SuppressLint("ClickableViewAccessibility")
    private fun pressable(vararg views: View) {
        views.forEach { v ->
            v.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(OvershootInterpolator(2.2f)).start()
                }
                false
            }
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
        FridayService.resume(this)
        if (VoiceState.mode.value == Mode.LISTENING) VoiceState.mode.value = Mode.IDLE
        VoiceState.level.value = 0f
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
            if (settings.savedContactCount > 0) {
                addView(TextView(this@MainActivity).apply {
                    text = "Yaad rakhe contacts: ${settings.savedContactCount} (bhulane ke liye yahan tap karo)"
                    setPadding(0, dp(12f).toInt(), 0, 0)
                    setOnClickListener {
                        settings.clearSavedContacts()
                        text = "Yaad rakhe hue contacts saaf kar diye"
                    }
                })
            }
            addView(TextView(this@MainActivity).apply {
                text = "Inbox, drive mode aur summary  ▸"
                setTextColor(this@MainActivity.style.accent)
                setPadding(0, dp(16f).toInt(), 0, dp(4f).toInt())
                setOnClickListener { startActivity(Intent(this@MainActivity, InboxActivity::class.java)) }
            })
            addView(TextView(this@MainActivity).apply {
                text = "Awaaz chuno (Friday jaisi)  ▸"
                setTextColor(this@MainActivity.style.accent)
                setPadding(0, dp(16f).toInt(), 0, dp(4f).toInt())
                setOnClickListener { showVoicePicker() }
            })
            addView(TextView(this@MainActivity).apply {
                text = "Hey Friday (voice) setup  ▸"
                setTextColor(this@MainActivity.style.accent)
                setPadding(0, dp(16f).toInt(), 0, dp(4f).toInt())
                setOnClickListener { showVoiceSetup() }
            })
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

    // ---- "Hey Friday": setup, start, log -------------------------------------

    private fun showVoiceSetup() {
        fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        fun describe(): String {
            val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
            return listOf(
                "Service: ${if (FridayService.running) "chalu" else "band"}",
                "Voice model: ${if (ModelStore.isReady(this)) "hai (${ModelStore.sizeMb(this)} MB)" else "download nahi hua"}",
                "Mic: ${if (granted(Manifest.permission.RECORD_AUDIO)) "ok" else "permission nahi"}",
                "Contacts aur Call: ${
                    if (granted(Manifest.permission.READ_CONTACTS) && granted(Manifest.permission.CALL_PHONE)) "ok"
                    else "permission nahi"
                }",
                "Battery: ${if (battery) "unrestricted" else "restricted (neeche button se hatao)"}",
            ).joinToString("\n")
        }
        val info = TextView(this).apply { text = describe() }
        // Progress and errors are shown here, in the dialog itself: the status line is hidden behind it.
        val note = TextView(this).apply { setPadding(0, dp(8f).toInt(), 0, 0) }
        fun button(label: String) = Button(this).apply {
            text = label
            isAllCaps = false
        }
        val download = button(if (ModelStore.isReady(this)) "Voice model hai ✓" else "Voice model download karo (~36 MB)")
        download.setOnClickListener { downloadVoiceModel(download, note) { info.text = describe() } }
        val test = button("Mic aur wake word test (15 sec)")
        test.setOnClickListener { runWakeTest(test, note) }
        val tile = button("Quick Settings tile jodo").apply { setOnClickListener { addQuickTile(note) } }
        val battery = button("Battery restriction hatao").apply {
            setOnClickListener {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
        }
        val log = button("Voice log dekho").apply { setOnClickListener { showVoiceLog() } }
        val pad = dp(20f).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            listOf(info, download, test, note, tile, battery, log).forEach(::addView)
        }
        AlertDialog.Builder(this)
            .setTitle("Hey Friday setup")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Chalu karo") { _, _ -> startVoice() }
            .setNeutralButton("Band karo") { _, _ ->
                FridayService.stop(this)
                status.text = "Hey Friday band kar diya"
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadVoiceModel(button: Button, note: TextView, onDone: () -> Unit) {
        if (ModelStore.isReady(this)) {
            note.text = "Voice model pehle se hai"
            return
        }
        button.isEnabled = false
        button.text = "Download ho raha hai: 0%"
        note.text = ""
        lifecycleScope.launch {
            ModelStore.download(this@MainActivity) { pct -> button.text = "Download ho raha hai: $pct%" }
                .onSuccess {
                    button.text = "Voice model hai ✓"
                    note.text = "Taiyaar. Ab 'Chalu karo' dabao."
                    onDone()
                }
                .onFailure {
                    button.text = "Dobara try karo"
                    button.isEnabled = true
                    note.text = "Download fail: ${it.message ?: it.javaClass.simpleName}"
                }
        }
    }

    /** Lets the user listen to each installed English voice and keep one. The Irish ones come first. */
    private fun showVoicePicker() {
        val speech = tts
        val voices = if (ttsReady && speech != null) Voices.available(speech) else emptyList()
        var chosen = voices.indexOfFirst { it.name == settings.voiceName }.coerceAtLeast(0)
        val english = CheckBox(this).apply {
            text = "Hamesha English mein jawab do (band karo toh jis bhasha mein bolo, usi mein)"
            isChecked = settings.speakEnglish
        }
        val labels = listOf("English", "हिन्दी (Hindi)", "ગુજરાતી (Gujarati)", "Auto: teeno (experimental)")
        val language = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(Listener.LANGUAGES.indexOf(settings.listenLanguage).coerceAtLeast(0))
        }
        val ack = CheckBox(this).apply {
            text = "Wake word ke baad bolo ('Yes, boss?'), warna sirf beep"
            isChecked = settings.speakAck
        }
        val group = RadioGroup(this)
        voices.forEachIndexed { i, voice ->
            group.addView(
                RadioButton(this).apply {
                    id = View.generateViewId()
                    text = Voices.label(voice, i + 1)
                    isChecked = i == chosen
                    setOnClickListener {
                        chosen = i
                        // Friday's own name in the sample must not wake the listener.
                        FridayService.pause(this@MainActivity)
                        speech?.voice = voice
                        speech?.speak("Hello, I'm Friday. How can I help?", TextToSpeech.QUEUE_FLUSH, null, "sample")
                    }
                },
            )
        }
        val pad = dp(20f).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(this@MainActivity).apply { text = "Main in bhasha mein sunti hu:" })
            addView(language)
            addView(english)
            addView(ack)
            addView(
                if (voices.isEmpty()) {
                    TextView(this@MainActivity).apply { text = "Koi English voice installed nahi mili. Neeche se download karo." }
                } else {
                    group
                },
            )
        }
        AlertDialog.Builder(this)
            .setTitle("Awaaz chuno")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Ye rakho") { _, _ ->
                settings.listenLanguage = Listener.LANGUAGES[language.selectedItemPosition]
                settings.speakEnglish = english.isChecked
                settings.speakAck = ack.isChecked
                settings.voiceName = voices.getOrNull(chosen)?.name
                speech?.let { Voices.apply(it, settings) }
            }
            .setNeutralButton("Aur voices download karo") { _, _ ->
                runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    .onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Puts an on/off switch for Hey Friday in the notification shade, which works even when the app is closed. */
    private fun addQuickTile(note: TextView) {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, FridayTileService::class.java),
                "Hey Friday",
                Icon.createWithResource(this, R.drawable.ic_mic),
                mainExecutor,
            ) { result ->
                note.text = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                        "Tile jud gayi. Notification shade mein 'Hey Friday' se on/off karo."
                    else -> "Tile nahi judi (code $result). Shade kholo, edit (pencil) dabao aur 'Hey Friday' ko kheench lo."
                }
            }
        } else {
            note.text = "Shade kholo, edit (pencil) dabao aur 'Hey Friday' tile ko kheench lo."
        }
    }

    /** Pins down a wake word failure: shows live what the microphone and the model hear. */
    private fun runWakeTest(button: Button, note: TextView) {
        if (!ModelStore.isReady(this)) {
            note.text = "Pehle voice model download karo"
            return
        }
        button.isEnabled = false
        lifecycleScope.launch {
            if (!ensurePermission(Manifest.permission.RECORD_AUDIO)) {
                note.text = "Mic permission chahiye"
                button.isEnabled = true
                return@launch
            }
            FridayService.pause(this@MainActivity) // the microphone can only be used by one at a time
            delay(600)
            note.text = "Model load ho raha hai, phir bolo: Hey Friday"
            val result = WakeTest.run(this@MainActivity, 15) { live ->
                note.text = "Bolo: Hey Friday  (${live.secondsLeft} sec)\n" +
                    "Mic level: ${live.level} (max ${live.peak})\n" +
                    "Wake decoder: ${live.wake.ifEmpty { "-" }}\n" +
                    "Free decoder: ${live.free.ifEmpty { "-" }}"
            }
            FridayService.resume(this@MainActivity)
            button.isEnabled = true
            note.text = when {
                result.error != null -> "Test fail: ${result.error}"
                result.matched -> "Wake word pehchana gaya ✓\nFree decoder ne suna: ${result.free.ifEmpty { "-" }}"
                result.peak < 300 -> "Mic mein awaaz bahut kam aayi (max level ${result.peak}). Mic ya permission ka masla ho sakta hai."
                else -> "Awaaz aayi (max level ${result.peak}) par 'Hey Friday' pehchana nahi gaya.\n" +
                    "Wake decoder: ${result.wake.ifEmpty { "-" }}\nFree decoder: ${result.free.ifEmpty { "-" }}"
            }
        }
    }

    private fun startVoice() {
        settings.voiceWanted = true
        if (!ModelStore.isReady(this)) {
            status.text = "Pehle Voice model download karo"
            return
        }
        lifecycleScope.launch {
            if (!ensurePermission(Manifest.permission.RECORD_AUDIO)) {
                status.text = "Mic permission ke bina Hey Friday nahi chalega"
                return@launch
            }
            // None of these stop the service from running, but with the screen off nothing can ask for them later:
            // without them the notification stays hidden and voice cannot look up contacts or place calls.
            if (Build.VERSION.SDK_INT >= 33) ensurePermission(Manifest.permission.POST_NOTIFICATIONS)
            ensurePermission(Manifest.permission.READ_CONTACTS)
            ensurePermission(Manifest.permission.CALL_PHONE)
            FridayService.start(this@MainActivity)
            status.text = "Hey Friday chalu ho raha hai... Voice log mein dekho kya hua"
        }
    }

    private fun showVoiceLog() {
        val pad = dp(16f).toInt()
        val text = TextView(this).apply {
            text = VoiceLog.read(this@MainActivity)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle("Voice log")
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Theek hai", null)
            .setNeutralButton("Saaf karo") { _, _ -> VoiceLog.clear(this) }
            .show()
    }

    private companion object {
        /** Turns sent to the model per request; older ones are dropped from the request, not the screen. */
        const val MAX_HISTORY = 20

        /** Coming back to the app within this time does not hit the site again. */
        const val UPDATE_CHECK_GAP_MS = 60_000L
    }
}
