package dev.friday.assistant.agent

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.friday.assistant.voice.VoiceLog
import kotlinx.coroutines.CompletableDeferred

/** One WhatsApp message waiting for its Send button to be pressed. */
class PendingWhatsApp(val text: String, val startedAt: Long = System.currentTimeMillis()) {
    val done = CompletableDeferred<Boolean>()
}

/**
 * Presses WhatsApp's Send button when Friday has opened a chat with the words already typed in (wa.me link).
 * It only ever looks at WhatsApp, and only while one message is pending; it clicks Send and nothing else.
 */
class FridayAccessibility : AccessibilityService() {
    private var lastTry = 0L

    override fun onServiceConnected() {
        VoiceLog.add(this, "Accessibility chalu (WhatsApp Send ke liye)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pending = pending ?: return
        if (System.currentTimeMillis() - pending.startedAt > 20_000) { WhatsAppSend.clear(); return }
        val now = System.currentTimeMillis()
        if (now - lastTry < 300) return
        lastTry = now
        val root = rootInActiveWindow ?: return
        val send = findSend(root) ?: return
        if (send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            VoiceLog.add(this, "WhatsApp Send dabaya")
            WhatsAppSend.clear()
            pending.done.complete(true)
        }
    }

    private fun findSend(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull { it.isEnabled }?.let { return it }
        }
        // Different WhatsApp versions label it differently; the label is the same word the screen reader says.
        return SEND_LABELS.firstNotNullOfOrNull { label ->
            root.findAccessibilityNodeInfosByText(label).firstOrNull {
                it.isClickable && it.isEnabled && (it.contentDescription?.toString()?.equals(label, true) == true)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    init { instance = this }

    companion object {
        private val SEND_LABELS = listOf("Send", "भेजें", "મોકલો")
        @Volatile var instance: FridayAccessibility? = null
            private set
        @Volatile var pending: PendingWhatsApp? = null

        /**
         * Switches the service on by itself, but only if the phone let this app write secure settings. Android never grants that
         * to an ordinary install; it is given once from a computer (adb shell pm grant dev.friday.assistant android.permission.WRITE_SECURE_SETTINGS).
         * After that Friday can also turn the service back on whenever the phone switches it off (it does that after a force-stop).
         */
        fun tryEnable(context: Context): Boolean {
            if (isEnabled(context)) return true
            if (context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
            return runCatching {
                val me = ComponentName(context, FridayAccessibility::class.java).flattenToString()
                val now = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                val all = if (now.isBlank()) me else "$now:$me"
                Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, all)
                Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                true
            }.getOrDefault(false)
        }

        /** The page of this one service, so turning it on is a single tap; the general list where the phone has no such page. */
        fun settingsIntent(context: Context): android.content.Intent {
            val me = ComponentName(context, FridayAccessibility::class.java).flattenToString()
            return android.content.Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(android.content.Intent.EXTRA_COMPONENT_NAME, me)
        }

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, FridayAccessibility::class.java).flattenToString()
            val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return on.split(':').any { it.equals(me, true) }
        }
    }
}

object WhatsAppSend {
    fun start(text: String): PendingWhatsApp = PendingWhatsApp(text).also { FridayAccessibility.pending = it }
    fun clear() { FridayAccessibility.pending = null }
}
