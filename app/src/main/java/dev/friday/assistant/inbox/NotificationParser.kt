package dev.friday.assistant.inbox

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/** Turns an Android notification into the messages inside it, skipping everything that is not a real message. */
object NotificationParser {
    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    const val GMAIL = "com.google.android.gm"

    private val NOISE = Regex(
        "(?i)^(\\d+ new messages?|checking for new messages|whatsapp web.*|backup in progress.*|" +
            "\\d+ messages? from \\d+ chats?|you have \\d+ new .*)$",
    )

    /** Notifications are posted again whenever anything about them changes; a message is only read the first time. */
    private val seen = object : LinkedHashMap<String, Boolean>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 800
    }

    private fun firstSight(id: String) = synchronized(seen) { if (seen.containsKey(id)) false else { seen[id] = true; true } }

    fun parse(context: Context, sbn: StatusBarNotification): List<Parsed> {
        val n = sbn.notification ?: return emptyList()
        val pkg = sbn.packageName
        if (pkg == context.packageName) return emptyList()
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return emptyList()
        if ((n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0) return emptyList()

        val extras = n.extras
        val app = label(context, pkg)
        val reply = replyHandle(n)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty().trim()

        // Chat apps describe a conversation as a list of messages, each with its own sender.
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            val group = style.isGroupConversation
            val conversation = style.conversationTitle?.toString()?.takeIf { it.isNotBlank() } ?: title
            val out = mutableListOf<Parsed>()
            for (m in style.messages) {
                val body = m.text?.toString().orEmpty().trim()
                if (body.isEmpty()) continue
                val who = m.person?.name?.toString()?.takeIf { it.isNotBlank() } ?: title
                if (!firstSight("${sbn.key}|${m.timestamp}|$who|$body")) continue
                out += Parsed(pkg, app, sbn.key, conversation, who, body, group, kindOf(pkg, body), m.timestamp, reply)
            }
            return out
        }

        if (title.isEmpty() && text.isEmpty()) return emptyList()
        if (isNoise(pkg, title, text)) return emptyList()

        var sender = title
        var body = text
        var conversation = title
        val group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        if (isWhatsApp(pkg) && group && ": " in text) {
            sender = text.substringBefore(": ")
            body = text.substringAfter(": ")
        }
        if (pkg == GMAIL) {
            conversation = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
            body = text.ifBlank { title }
        }
        if (!firstSight("${sbn.key}|${sbn.postTime}|$sender|$body")) return emptyList()
        return listOf(Parsed(pkg, app, sbn.key, conversation, sender, body, group, kindOf(pkg, body), sbn.postTime, reply))
    }

    private fun isWhatsApp(pkg: String) = pkg == WHATSAPP || pkg == WHATSAPP_BUSINESS

    private fun kindOf(pkg: String, body: String): Kind = when {
        pkg == GMAIL -> Kind.MAIL
        isWhatsApp(pkg) && body.lowercase().let { "missed" in it && "call" in it } -> Kind.CALL
        isWhatsApp(pkg) -> Kind.MESSAGE
        else -> Kind.OTHER
    }

    private fun isNoise(pkg: String, title: String, text: String): Boolean = when {
        isWhatsApp(pkg) -> title.equals("WhatsApp", ignoreCase = true) && NOISE.matches(text) || NOISE.matches(text) || NOISE.matches(title)
        pkg == GMAIL -> title.contains("sync", ignoreCase = true) || text.isBlank() && title.isBlank()
        else -> false
    }

    private fun replyHandle(n: Notification): ReplyHandle? {
        for (action in n.actions ?: return null) {
            val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: continue
            val intent = action.actionIntent ?: continue
            return ReplyHandle(intent, input)
        }
        return null
    }

    fun label(context: Context, pkg: String): String = when (pkg) {
        WHATSAPP -> "WhatsApp"
        WHATSAPP_BUSINESS -> "WhatsApp Business"
        GMAIL -> "Gmail"
        else -> runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })
    }
}
