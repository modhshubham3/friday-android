package dev.friday.assistant.inbox

import android.content.Context
import dev.friday.assistant.Assistant
import dev.friday.assistant.Settings
import dev.friday.assistant.llm.ChatMessage
import dev.friday.assistant.llm.ToolOutput
import dev.friday.assistant.tools.UserPrompts
import dev.friday.assistant.voice.FridayService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The inbox as the rest of Friday uses it: taking things in, reading them out, learning, and summing up the day. */
object Inbox {
    /** At or above this a message is worth interrupting for; at or above [DIGEST] it is worth a place in the summary. */
    const val SPEAK = 0.6f
    const val DIGEST = 0.3f

    /** Only these are watched until the user switches on more, so nothing else is ever collected by default. */
    private val DEFAULT_ON = setOf(NotificationParser.WHATSAPP, NotificationParser.WHATSAPP_BUSINESS, NotificationParser.GMAIL)

    /** The message read out last, which "ye zaroori tha", "mat padho" and "reply bolo" refer to. */
    @Volatile var lastEventId = 0L

    fun ingest(context: Context, p: Parsed) {
        val db = InboxDb.get(context)
        val app = db.app(p.pkg) ?: AppRow(p.pkg, p.app, p.pkg in DEFAULT_ON).also { db.upsertApp(it) }
        if (!app.enabled || Privacy.shouldDrop(p)) return

        val analysis = Classifier.analyze(context, p)
        val senderKey = Classifier.senderKey(p.sender)
        db.touchSender(senderKey, p.sender)
        val importance = when (db.rule(senderKey)) {
            Rule.ALWAYS -> 0.99f
            Rule.NEVER -> 0.01f
            else -> Scorer.probability(db, analysis.features, analysis.prior)
        }
        val id = db.insert(
            NewEvent(
                ts = if (p.time > 0) p.time else System.currentTimeMillis(),
                pkg = p.pkg, app = p.app, conv = p.conv, sender = p.sender, text = p.text, kind = p.kind,
                category = analysis.category, importance = importance, prior = analysis.prior, group = p.group,
                question = analysis.question, sensitive = analysis.sensitive, features = analysis.features,
            ),
        )
        p.reply?.let { ReplyHandles.put(id, it) }
        if (importance >= SPEAK && (Settings(context).driveMode || FridayService.talking)) Announcer.request(context)
    }

    // ---- Reading out ----------------------------------------------------------

    fun nextToAnnounce(context: Context): Event? = InboxDb.get(context).pending(SPEAK, 1).firstOrNull()

    fun markAnnounced(context: Context, id: Long) = InboxDb.get(context).markAnnounced(id)

    /** One thing, worded to be heard. Money messages are never read out, and without private sound neither are the words. */
    fun line(e: Event, withContent: Boolean): String {
        if (e.sensitive) return "A money related ${e.app} message arrived."
        val who = e.sender
        if (!withContent) {
            return when (e.kind) {
                Kind.MAIL -> "New mail from $who."
                Kind.CALL -> "Missed call from $who on ${e.app}."
                else -> "New ${e.app} message from ${if (e.group) "$who in ${e.conv}" else who}."
            }
        }
        val body = clip(e.text, 220)
        val more = if (e.text.length > 220) " Say read all for the rest." else ""
        return when (e.kind) {
            Kind.MAIL -> "Mail from $who. $body.$more"
            Kind.CALL -> "Missed call from $who on ${e.app}."
            else -> if (e.group) "In ${e.conv}, $who says: $body.$more" else "$who on ${e.app} says: $body.$more"
        }
    }

    fun clip(text: String, max: Int): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= max) return flat
        return flat.take(max).substringBeforeLast(' ', flat.take(max)) + "..."
    }

    /** "Kya aaya?": what is new and matters, or the latest from one person. */
    fun readNew(context: Context, from: String?, english: Boolean): String {
        val db = InboxDb.get(context)
        val items = if (from == null) db.pending(DIGEST, 5) else db.fromSender(from, 4)
        if (items.isEmpty()) {
            return if (from != null) {
                if (english) "I have nothing from $from." else "$from ka kuch nahi aaya."
            } else if (english) "Nothing new that matters." else "Koi zaroori naya message nahi hai."
        }
        val content = Privacy.mayReadContent(context)
        items.forEach { db.markAnnounced(it.id) }
        lastEventId = items.first().id
        val intro = if (english) "You have ${items.size} ${if (items.size == 1) "thing" else "things"}. " else "${items.size} cheezein hain. "
        return intro + items.joinToString(" ") { line(it, content) }
    }

    // ---- Answering ---------------------------------------------------------------

    /** The message to reply to: the one just read out, or the latest from [to] that can still be answered. */
    fun replyTarget(context: Context, to: String?): Event? {
        val db = InboxDb.get(context)
        if (to == null) return db.event(lastEventId)?.takeIf { ReplyHandles.has(it.id) } ?: db.recent(30).firstOrNull { ReplyHandles.has(it.id) }
        return db.fromSender(to, 20).firstOrNull { ReplyHandles.has(it.id) }
    }

    /** Reads the reply back and sends it only after a yes. */
    suspend fun confirmAndSend(context: Context, event: Event, body: String, prompts: UserPrompts, english: Boolean): String {
        if (!ReplyHandles.has(event.id)) {
            return if (english) "I can't reply to ${event.sender} from here: that notification is gone."
            else "${event.sender} ko yahan se reply nahi ho sakta, notification ab nahi hai."
        }
        val ask = if (english) "Send to ${event.sender}: $body. Should I send it?" else "${event.sender} ko bhejun: $body. Bhej du?"
        if (!prompts.confirm(ask, if (english) "Yes, send" else "Haan, bhejo")) {
            return if (english) "Okay, I have not sent it." else "Theek hai, nahi bheja."
        }
        return if (ReplyHandles.send(context, event.id, body)) {
            if (english) "Sent to ${event.app}." else "${event.app} ko bhej diya."
        } else {
            if (english) "I could not send it. The conversation is no longer open in notifications." else "Bhej nahi paya, conversation ab notification mein nahi hai."
        }
    }

    // ---- Learning --------------------------------------------------------------

    /** The user says a message did or did not matter. This is the correction the classifier learns from. */
    fun feedback(context: Context, eventId: Long, mattered: Boolean) {
        val db = InboxDb.get(context)
        val e = db.event(eventId) ?: return
        db.setFeedback(eventId, if (mattered) 1 else -1)
        Scorer.learn(db, e.features, e.prior, mattered)
    }

    fun setSenderRule(context: Context, senderKey: String, name: String, rule: Int) =
        InboxDb.get(context).setRule(senderKey, name, rule)

    // ---- Facts about the user -----------------------------------------------------

    fun facts(context: Context): List<String> = InboxDb.get(context).facts().take(30)

    // ---- The day's summary --------------------------------------------------------

    private fun startOfToday() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** The summary without any model: counts, the people who need an answer, mail worth a look. */
    fun localDigest(context: Context): String {
        val all = InboxDb.get(context).since(startOfToday())
        if (all.isEmpty()) return "Nothing came in today."
        val urgent = all.filter { it.importance >= SPEAK && !it.sensitive && it.category != Cat.PROMO }
        val mail = all.filter { it.kind == Kind.MAIL && it.category == Cat.MAIL && it !in urgent }
        val promos = all.count { it.category == Cat.PROMO }
        val groups = all.count { it.category == Cat.GROUP && it !in urgent }
        val deliveries = all.count { it.category == Cat.DELIVERY }
        val money = all.count { it.sensitive }

        val out = StringBuilder("${all.size} things came in today. ")
        if (urgent.isNotEmpty()) {
            out.append("${urgent.size} need attention: ")
                .append(urgent.take(4).joinToString("; ") { "${it.sender}: ${clip(it.text, 70)}" }).append(". ")
        }
        if (mail.isNotEmpty()) out.append("${mail.size} mail worth a look, from ${mail.take(3).joinToString(", ") { it.sender }}. ")
        if (deliveries > 0) out.append("$deliveries delivery updates. ")
        if (money > 0) out.append("$money money messages, not read out. ")
        if (promos + groups > 0) out.append("Skipped $promos promotions and $groups group chats.")
        return out.toString().trim()
    }

    /**
     * The day's summary, written by a model when the user allows it (one request, with short snippets), otherwise
     * by [localDigest]. It is saved so it can be shown again without asking again.
     */
    suspend fun digest(context: Context, english: Boolean): String {
        val settings = Settings(context)
        val db = InboxDb.get(context)
        db.purge(System.currentTimeMillis() - 90L * 24 * 3600 * 1000)
        val local = localDigest(context)
        var text = local
        if (settings.digestAi) {
            val items = db.since(startOfToday()).filter { !it.sensitive }.take(40)
            if (items.isNotEmpty()) {
                val data = items.joinToString("\n") {
                    "- [${it.app}${if (it.group) ", group ${it.conv}" else ""}] ${it.sender}: ${clip(it.text, 110)} " +
                        "(matters ${(it.importance * 100).toInt()}%, ${it.category.lowercase()})"
                }
                val language = if (english) "English" else "Hinglish"
                val reply = Assistant.router(settings).ask(
                    "You write a short end-of-day summary of the user's WhatsApp messages and mail, to be read aloud by an " +
                        "assistant called Friday. Group it: who needs a reply (most important first), mail worth reading, " +
                        "deliveries and updates, and say how many promotions and group chats were left out. At most 110 words, " +
                        "plain sentences, no lists or markdown, in $language. Use only what is in the data; never invent anything.",
                    listOf(ChatMessage(fromUser = true, text = "Today's messages:\n$data")),
                    emptyList(),
                ) { ToolOutput("") }
                if (reply.provider != null && reply.text.isNotBlank()) text = reply.text
            }
        }
        db.saveDigest(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()), text)
        return text
    }
}
