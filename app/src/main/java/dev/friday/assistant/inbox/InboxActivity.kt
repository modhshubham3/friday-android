package dev.friday.assistant.inbox

import android.Manifest
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.friday.assistant.Look
import dev.friday.assistant.Settings
import dev.friday.assistant.Style
import dev.friday.assistant.ui.AuroraView
import dev.friday.assistant.ui.OrbView
import dev.friday.assistant.voice.Mode
import dev.friday.assistant.voice.Speaker
import dev.friday.assistant.voice.VoiceState
import kotlinx.coroutines.launch

/**
 * Where the user watches Friday learn: which apps are listened to, drive mode, the evening summary, and the list of
 * recent messages with a way to say "this mattered" or "this did not". Everything here is drawn in code with the same
 * look as the chat screen.
 */
class InboxActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var db: InboxDb
    private lateinit var style: Style
    private lateinit var orb: OrbView
    private lateinit var access: TextView
    private lateinit var events: LinearLayout
    private var speaker: Speaker? = null

    private val askBluetooth = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) chooseCar() else Toast.makeText(this, "Bluetooth permission ke bina car nahi chun sakte", Toast.LENGTH_LONG).show()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        db = InboxDb.get(this)
        style = Look.style(settings.themeId, settings.lookId, settings.sizeId)

        val root = FrameLayout(this).apply { setBackgroundColor(style.bg) }
        root.addView(AuroraView(this).apply { configure(style.accent, style.aurora) }, match())
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f).toInt(), dp(8f).toInt(), dp(16f).toInt(), dp(40f).toInt())
        }
        root.addView(
            ScrollView(this).apply {
                fitsSystemWindows = true
                clipToPadding = false
                addView(content)
            },
            match(),
        )
        setContentView(root)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = style.lightBars
            isAppearanceLightNavigationBars = style.lightBars
        }

        // Header: the orb shows what Friday is doing, here too.
        orb = OrbView(this).apply { accent = style.accent }
        content.addView(
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(orb, LinearLayout.LayoutParams(dp(64f).toInt(), dp(64f).toInt()))
                addView(
                    LinearLayout(this@InboxActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(8f).toInt(), 0, 0, 0)
                        addView(label("Inbox", 24f, style.ink, bold = true))
                        addView(label("WhatsApp aur Gmail, aur Friday ka seekhna", 13f, style.mute))
                    },
                )
            },
        )

        access = label("", 14f, style.ink)
        content.addView(
            card(
                label("Notification access", 16f, style.ink, bold = true),
                access,
                label(
                    "Friday sirf WhatsApp aur Gmail ke notifications padhta hai, aur wo bhi sirf phone ke andar, locked. " +
                        "Agar switch grey ho: Apps → Friday → ⋮ → 'Allow restricted settings', phir yahan wapas aao.",
                    12f, style.mute,
                ),
                action("Notification access kholo") { startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                action("Friday ki app info (restricted settings)") {
                    startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                },
            ),
        )

        content.addView(card(*driveViews().toTypedArray()))
        content.addView(card(*summaryViews().toTypedArray()))
        content.addView(
            card(
                label("Kaunse apps sunu", 16f, style.ink, bold = true),
                label("WhatsApp aur Gmail pehle se on hain. Baaki sab band, jab tak aap on na karo.", 12f, style.mute),
                action("Apps chuno") { chooseApps() },
            ),
        )

        content.addView(label("Seekhao: kya zaroori tha?", 16f, style.ink, bold = true).apply { setPadding(0, dp(8f).toInt(), 0, dp(4f).toInt()) })
        content.addView(label("Har message ke neeche jawab do. Friday har jawab se seekhta hai.", 12f, style.mute))
        events = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(events)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { VoiceState.mode.collect { orb.mode = it } }
            }
        }
        if (intent.getBooleanExtra(EXTRA_SPEAK, false)) speakDigest()
    }

    override fun onResume() {
        super.onResume()
        refreshAccess()
        renderEvents()
    }

    override fun onDestroy() {
        speaker?.shutdown()
        super.onDestroy()
    }

    // ---- Sections -----------------------------------------------------------------

    private fun driveViews(): List<View> {
        val drive = Switch(this).apply {
            text = "Drive mode"
            setTextColor(style.ink)
            isChecked = settings.driveMode
            setOnCheckedChangeListener { _, on -> DriveMode.set(this@InboxActivity, on, "inbox screen") }
        }
        val content = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@InboxActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Auto: sirf earbuds ya car pe message padho", "Hamesha message padho", "Kabhi nahi, sirf naam batao"),
            )
            setSelection(MODES.indexOf(settings.contentMode).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    settings.contentMode = MODES[position]
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        return listOf(
            label("Drive mode", 16f, style.ink, bold = true),
            label(
                "Zaroori message aate hi bol ke sunaya jaata hai, phir 6 second mein aap bol sakte ho: " +
                    "'reply bolo ...', 'skip', 'poora padho', 'mat padho', 'zaroori tha'. Bhejne se pehle hamesha poochta hai. " +
                    "Jawab dene ke liye Hey Friday chalu hona chahiye.",
                12f, style.mute,
            ),
            drive,
            label("Message ke shabd kab bolun?", 13f, style.ink),
            content,
            action("Car ka Bluetooth chuno (khud on/off)") { chooseCar() },
        )
    }

    private fun summaryViews(): List<View> {
        val ai = Switch(this).apply {
            text = "AI se likhwao (chhote snippets Google ko jaate hain)"
            setTextColor(style.ink)
            isChecked = settings.digestAi
            setOnCheckedChangeListener { _, on -> settings.digestAi = on }
        }
        val time = action(timeLabel()) {}
        time.setOnClickListener {
            TimePickerDialog(
                this,
                { _, h, m ->
                    settings.digestHour = h
                    settings.digestMinute = m
                    time.text = timeLabel()
                    DigestScheduler.schedule(this)
                },
                settings.digestHour, settings.digestMinute, true,
            ).show()
        }
        return listOf(
            label("Din ka summary", 16f, style.ink, bold = true),
            label("Roz is waqt ek notification: kise jawab dena hai, kaun se mail kaam ke, baaki kitna chhoda.", 12f, style.mute),
            time,
            ai,
            action("Summary abhi sunao") { speakDigest() },
        )
    }

    private fun timeLabel() = "Summary ka time: %02d:%02d".format(settings.digestHour, settings.digestMinute)

    // ---- Actions ------------------------------------------------------------------

    private fun refreshAccess() {
        val on = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        access.text = if (on) "Chalu hai ✓" else "Band hai. Neeche button se chalu karo."
        access.setTextColor(if (on) style.accent else style.mute)
    }

    private fun speakDigest() {
        Toast.makeText(this, "Summary ban rahi hai...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            VoiceState.mode.value = Mode.THINKING
            val text = Inbox.digest(this@InboxActivity, settings.speakEnglish)
            AlertDialog.Builder(this@InboxActivity).setTitle("Aaj ka summary").setMessage(text).setPositiveButton("Theek hai", null).show()
            VoiceState.mode.value = Mode.SPEAKING
            val voice = speaker ?: Speaker(this@InboxActivity, settings).also { speaker = it }
            voice.speak(text)
            VoiceState.mode.value = Mode.IDLE
        }
    }

    private fun chooseApps() {
        val rows = db.apps()
        if (rows.isEmpty()) {
            Toast.makeText(this, "Abhi kisi app ne notification nahi bheji. Thodi der baad aao.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Kaunse apps sunu")
            .setMultiChoiceItems(rows.map { it.label }.toTypedArray(), rows.map { it.enabled }.toBooleanArray()) { _, which, on ->
                db.upsertApp(rows[which].copy(enabled = on))
            }
            .setPositiveButton("Theek hai", null)
            .show()
    }

    private fun chooseCar() {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val devices = runCatching { adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            Toast.makeText(this, "Koi paired Bluetooth device nahi mila. Pehle car ko phone se pair karo.", Toast.LENGTH_LONG).show()
            return
        }
        val chosen = settings.carDevices.toMutableSet()
        AlertDialog.Builder(this)
            .setTitle("Car ka Bluetooth")
            .setMultiChoiceItems(
                devices.map { it.name ?: it.address }.toTypedArray(),
                devices.map { it.address in chosen }.toBooleanArray(),
            ) { _, which, on -> if (on) chosen += devices[which].address else chosen -= devices[which].address }
            .setPositiveButton("Theek hai") { _, _ -> settings.carDevices = chosen }
            .show()
    }

    // ---- The list the user teaches from -------------------------------------------

    private fun renderEvents() {
        events.removeAllViews()
        val recent = db.recent(40)
        if (recent.isEmpty()) {
            events.addView(label("Abhi tak kuch nahi aaya. Access chalu karke ek WhatsApp ya mail bhejo.", 13f, style.mute).apply { setPadding(0, dp(12f).toInt(), 0, 0) })
            return
        }
        recent.forEach { e ->
            val fb = when (e.feedback) { 1 -> "  ·  👍"; -1 -> "  ·  👎"; else -> "" }
            val body = if (e.sensitive) "(paise ka message, chhupa hua)" else Inbox.clip(e.text, 140)
            events.addView(
                card(
                    label("${e.app} · ${e.sender} · ${(e.importance * 100).toInt()}%$fb", 12f, style.mute),
                    label(body, 14f, style.ink),
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(small("👍 Zaroori") { Inbox.feedback(this@InboxActivity, e.id, true); renderEvents() })
                        addView(small("👎 Nahi") { Inbox.feedback(this@InboxActivity, e.id, false); renderEvents() })
                        addView(small("Hamesha") { rule(e, Rule.ALWAYS, "hamesha padhunga") })
                        addView(small("Kabhi nahi") { rule(e, Rule.NEVER, "kabhi nahi padhunga") })
                    },
                ),
            )
        }
    }

    private fun rule(e: Event, rule: Int, said: String) {
        Inbox.setSenderRule(this, e.senderKey, e.sender, rule)
        Toast.makeText(this, "${e.sender}: $said", Toast.LENGTH_SHORT).show()
    }

    // ---- Small drawing helpers -------------------------------------------------------

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius)
        if (stroke != null) setStroke(dp(1f).toInt().coerceAtLeast(1), stroke)
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size * style.scale)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4f).toInt(), 0, dp(2f).toInt())
    }

    private fun card(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(style.surface, 18f, style.line)
        setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(12f).toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12f).toInt() }
        views.forEach(::addView)
    }

    private fun action(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(style.accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * style.scale)
        gravity = Gravity.CENTER
        background = rounded(style.surface, 14f, style.accent)
        setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(10f).toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8f).toInt() }
        setOnClickListener { onClick() }
    }

    private fun small(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(style.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * style.scale)
        gravity = Gravity.CENTER
        background = rounded(style.bg, 12f, style.line)
        setPadding(dp(10f).toInt(), dp(7f).toInt(), dp(10f).toInt(), dp(7f).toInt())
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { topMargin = dp(8f).toInt(); marginEnd = dp(6f).toInt() }
        setOnClickListener { onClick() }
    }

    companion object {
        const val EXTRA_SPEAK = "speak"
        private val MODES = listOf("auto", "always", "never")
    }
}
