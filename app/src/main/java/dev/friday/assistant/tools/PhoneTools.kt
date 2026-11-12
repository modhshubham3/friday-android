package dev.friday.assistant.tools

import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.telecom.TelecomManager
import dev.friday.assistant.SavedContact
import dev.friday.assistant.inbox.DriveMode
import dev.friday.assistant.inbox.Inbox
import dev.friday.assistant.inbox.InboxDb
import dev.friday.assistant.inbox.Rule
import dev.friday.assistant.Settings
import dev.friday.assistant.llm.ToolCall
import dev.friday.assistant.llm.ToolOutput
import dev.friday.assistant.llm.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs

/**
 * The things Friday can do on the phone. Each tool has a spec (what the model sees)
 * and a handler (what the app does).
 *
 * Two rules every handler follows:
 *  - Never act on a guess. If the request is unclear (two Clock apps, two matching
 *    contacts, a misspelt name) the user picks from a list; anything that removes
 *    something asks first.
 *  - Never claim success it has not seen. Alarms are checked against the system's
 *    next-alarm record, and the result says plainly when that check could not confirm.
 */
class PhoneTools(
    private val context: Context,
    private val settings: Settings,
    private val prompts: UserPrompts,
    /** True when running from the voice service with no screen open, where some things work differently. */
    private val fromBackground: Boolean = false,
    /** True when an English voice will read the answers out; plain results are then worded in English. */
    private val english: Boolean = false,
) {
    data class ClockApp(val pkg: String, val label: String)

    /** One sentence in two languages: Hinglish for the screen, English when it will be spoken by an English voice. */
    private fun say(hi: String, en: String) = if (english) en else hi

    /** "14:05" is read out as "2:05 PM". */
    private fun clock12(hour: Int, minute: Int) =
        "${if (hour % 12 == 0) 12 else hour % 12}:${"%02d".format(minute)} ${if (hour < 12) "AM" else "PM"}"

    /** Opens another app's screen. Outside an Activity Android requires a task of its own. */
    private fun launch(intent: Intent) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "set_alarm",
            "Set an alarm for the next time the clock shows this hour and minute. " +
                "Only call it once the exact time, including AM or PM, is clear.",
            schema(
                required = listOf("hour", "minute"),
                "hour" to prop("integer", "Hour in 24-hour time, 0 to 23"),
                "minute" to prop("integer", "Minute, 0 to 59"),
                "label" to prop("string", "Optional short name for the alarm"),
            ),
        ),
        ToolSpec(
            "get_next_alarm",
            "Read the time of the user's next alarm. Only the next one is visible; Android does not " +
                "let apps list all alarms.",
            JSONObject().put("type", "object").put("properties", JSONObject()),
        ),
        ToolSpec(
            "cancel_alarm",
            "Remove the alarm at one exact time. Needs the exact hour and minute; if the user has not " +
                "given it, ask, or use get_next_alarm and check that is the one. The app asks the user to confirm.",
            schema(
                required = listOf("hour", "minute"),
                "hour" to prop("integer", "Hour in 24-hour time, 0 to 23"),
                "minute" to prop("integer", "Minute, 0 to 59"),
            ),
        ),
        ToolSpec(
            "make_call",
            "Place a phone call right away. Pass the contact's name as the user said it, or a phone number. " +
                "The app shows the user a list when the name is unclear and remembers the answer, " +
                "so do not ask the user to confirm a clear request yourself.",
            schema(
                required = listOf("name_or_number"),
                "name_or_number" to prop("string", "Contact name or phone number"),
            ),
        ),
        ToolSpec(
            "read_messages",
            "Read out the user's new WhatsApp messages and mail that matter, or the latest from one person. " +
                "Use for 'kya aaya', 'what did I miss', 'read my messages', 'Gauri ka message padho'.",
            schema(required = emptyList(), "from" to prop("string", "A sender's name; leave out for everything new")),
        ),
        ToolSpec(
            "reply_message",
            "Reply to a WhatsApp message. Pass only the exact words to send. The app reads them back and asks the user " +
                "to confirm, so never ask for confirmation yourself.",
            schema(
                required = listOf("text"),
                "text" to prop("string", "The exact words to send"),
                "to" to prop("string", "Who to reply to; leave out for the message that was just read out"),
            ),
        ),
        ToolSpec(
            "digest",
            "The summary of today's WhatsApp messages and mail: who needs an answer, mail worth reading, what was skipped.",
            JSONObject().put("type", "object").put("properties", JSONObject()),
        ),
        ToolSpec(
            "teach",
            "Record that the message just read out did or did not matter, or that a sender should always or never be read out.",
            schema(
                required = listOf("important"),
                "important" to prop("boolean", "true if it mattered, false if it did not"),
                "sender_rule" to prop("string", "always, never, or normal: how to treat this sender from now on"),
                "sender" to prop("string", "Whose rule it is; leave out for the sender of the message just read out"),
            ),
        ),
        ToolSpec(
            "drive_mode",
            "Turn drive mode on or off. While on, important messages are read out as they arrive.",
            schema(required = listOf("on"), "on" to prop("boolean", "true to turn on, false to turn off")),
        ),
        ToolSpec(
            "remember",
            "Remember a fact the user tells you about themselves, such as who someone is or a preference.",
            schema(required = listOf("fact"), "fact" to prop("string", "The fact, as a short sentence")),
        ),
        ToolSpec(
            "forget",
            "Forget remembered facts that mention these words.",
            schema(required = listOf("fact"), "fact" to prop("string", "Words from the fact to forget")),
        ),
        ToolSpec(
            "flashlight",
            "Turn the phone's flashlight (torch) on or off.",
            schema(
                required = listOf("on"),
                "on" to prop("boolean", "true to turn on, false to turn off"),
            ),
        ),
    )

    suspend fun run(call: ToolCall): ToolOutput {
        val text = try {
            when (call.name) {
                "set_alarm" -> setAlarm(call.args)
                "get_next_alarm" -> nextAlarmText()
                "cancel_alarm" -> cancelAlarm(call.args)
                "make_call" -> makeCall(call.args.optString("name_or_number"))
                "flashlight" -> flashlight(call.args.optBoolean("on", true))
                "read_messages" -> Inbox.readNew(context, call.args.optString("from").takeIf { it.isNotBlank() }, english)
                "reply_message" -> replyMessage(call.args.optString("to"), call.args.optString("text"))
                "digest" -> Inbox.digest(context, english)
                "teach" -> teach(call.args)
                "drive_mode" -> driveMode(call.args.optBoolean("on", true))
                "remember" -> remember(call.args.optString("fact"))
                "forget" -> forget(call.args.optString("fact"))
                else -> "Error: '${call.name}' naam ka koi tool nahi hai"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
        // A plain result is shown to the user as it is. Errors, and anything written for the model
        // ("User ko ... bolo"), go back to the model to put into words; so does get_next_alarm,
        // which only exists to give the model something to ask the user about.
        val forModel = call.name == "get_next_alarm" || text.startsWith("Error:") ||
            text.contains("user ko", ignoreCase = true) || text.contains("user se", ignoreCase = true)
        return ToolOutput(text, final = !forModel)
    }

    // ---- Alarm ------------------------------------------------------------

    /** Every app that can set alarms. Phones often have two (the maker's Clock and Google's). */
    @Suppress("DEPRECATION")
    fun clockApps(): List<ClockApp> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(AlarmClock.ACTION_SET_ALARM), 0)
            .map { ClockApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }
    }

    /**
     * With two Clock apps Android shows its own chooser, which a voice assistant cannot answer,
     * so the alarm is never set. Friday asks once, remembers, and then addresses that app directly.
     */
    private suspend fun pickClockApp(apps: List<ClockApp>): ClockApp? {
        if (apps.size == 1) return apps[0]
        settings.alarmPackage?.let { saved -> apps.firstOrNull { it.pkg == saved }?.let { return it } }
        val index = prompts.choose(say("Alarm kaunsi Clock app mein lagaun?", "Which clock app should I use?"), apps.map { it.label }) ?: return null
        settings.alarmPackage = apps[index].pkg
        return apps[index]
    }

    private fun alarmManager() = context.getSystemService(AlarmManager::class.java)

    /** The next moment the clock shows [hour]:[minute]. */
    private fun nextOccurrence(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun isAt(trigger: Long?, expected: Long) = trigger != null && abs(trigger - expected) < 60_000

    /** Polls the system's next-alarm record for up to three seconds. */
    private suspend fun waitFor(condition: (Long?) -> Boolean): Boolean {
        repeat(10) {
            if (condition(alarmManager().nextAlarmClock?.triggerTime)) return true
            delay(300)
        }
        return condition(alarmManager().nextAlarmClock?.triggerTime)
    }

    private fun time(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

    private fun readTime(args: JSONObject): Pair<Int, Int>? {
        if (!args.has("hour") || !args.has("minute")) return null
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", -1)
        return if (hour in 0..23 && minute in 0..59) hour to minute else null
    }

    private suspend fun setAlarm(args: JSONObject): String {
        val (hour, minute) = readTime(args) ?: return "Error: alarm ka sahi hour (0-23) aur minute (0-59) chahiye"
        val apps = clockApps()
        if (apps.isEmpty()) return "Error: is phone mein alarm ke liye koi Clock app nahi mili"
        val app = pickClockApp(apps) ?: return say("Theek hai, Clock app nahi chuni isliye alarm nahi lagaya", "Okay, I have not set the alarm because no clock app was chosen.")

        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .setPackage(app.pkg)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        try {
            launch(intent)
        } catch (e: ActivityNotFoundException) {
            return "Error: ${app.label} alarm set nahi kar payi"
        }
        val expected = nextOccurrence(hour, minute)
        return if (waitFor { isAt(it, expected) }) {
            say("Alarm ${time(hour, minute)} ka ${app.label} mein set ho gaya (system ne confirm kiya)", "Alarm set for ${clock12(hour, minute)}.")
        } else {
            "Alarm ${time(hour, minute)} ke liye request ${app.label} ko bheji, par confirm nahi ho paya " +
                "(ho sakta hai koi aur alarm pehle ho). User ko Clock app mein check karne ko bolo"
        }
    }

    private fun nextAlarmText(): String {
        val trigger = alarmManager().nextAlarmClock?.triggerTime ?: return "Koi alarm set nahi hai"
        val cal = Calendar.getInstance().apply { timeInMillis = trigger }
        val today = Calendar.getInstance()
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val day = when {
            sameDay(cal, today) -> "aaj"
            sameDay(cal, tomorrow) -> "kal"
            else -> "%02d/%02d".format(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1)
        }
        val hhmm = time(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        return "Agla alarm $day $hhmm baje hai. Sirf agla alarm dikhta hai, baaki alarms ki list Android nahi deta"
    }

    private suspend fun cancelAlarm(args: JSONObject): String {
        val (hour, minute) = readTime(args)
            ?: return "Error: kaunsa alarm? Exact hour aur minute chahiye. User se poocho"
        val label = time(hour, minute)
        // The question is asked by the app, not left to the model, so a destructive step
        // can never happen on a guess.
        if (!prompts.confirm(say("$label ka alarm hata du?", "Should I remove the ${clock12(hour, minute)} alarm?"), say("Haan, hata do", "Yes, remove it"))) {
            return say("Theek hai, alarm nahi hataya", "Okay, I have not removed the alarm.")
        }
        val apps = clockApps()
        if (apps.isEmpty()) return "Error: is phone mein koi Clock app nahi mili"
        val app = pickClockApp(apps) ?: return say("Theek hai, Clock app nahi chuni isliye kuch nahi hataya", "Okay, nothing was removed because no clock app was chosen.")

        val expected = nextOccurrence(hour, minute)
        val wasNext = isAt(alarmManager().nextAlarmClock?.triggerTime, expected)
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
            .setPackage(app.pkg)
            .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
        try {
            launch(intent)
        } catch (e: ActivityNotFoundException) {
            return "Error: ${app.label} alarm hatane ki request nahi leti. User ko Clock app se haath se hatana hoga"
        }
        if (!wasNext) {
            return "Hatane ki request ${app.label} ko bheji, par confirm nahi kar sakta kyunki ye agla alarm nahi hai. " +
                "User ko Clock app mein check karne ko bolo"
        }
        return if (waitFor { !isAt(it, expected) }) {
            say("$label ka alarm hat gaya (system ne confirm kiya)", "Removed the ${clock12(hour, minute)} alarm.")
        } else {
            "$label ka alarm abhi bhi agla alarm dikh raha hai, ${app.label} ne request nahi maani. " +
                "User ko Clock app se haath se hatana hoga"
        }
    }

    // ---- Call -------------------------------------------------------------

    private data class Contact(val name: String, val numbers: List<String>, val score: Int)

    private fun digits(number: String) = number.filter { it.isDigit() }
    private fun sameNumber(a: String, b: String) = digits(a) == digits(b)

    private suspend fun makeCall(target: String): String {
        val raw = target.trim()
        if (raw.isEmpty()) return "Error: kisko call karna hai?"

        // A number needs no lookup.
        if (Regex("^[+0-9 ()-]{5,}$").matches(raw)) return placeCall(raw, raw)

        if (!prompts.ensureContacts()) {
            return "Error: contacts ki permission nahi mili, naam se call nahi kar sakta. Number bolo"
        }
        val book = loadContacts()
        val key = normalize(raw)

        // A choice the user made before is never asked again, as long as that contact still exists.
        settings.savedContact(key)?.let { saved ->
            if (book[saved.name]?.any { sameNumber(it, saved.number) } == true) return placeCall(saved.number, saved.name)
        }

        val matches = rank(book, raw)
        if (matches.isEmpty()) return "Error: '$raw' se milta koi contact nahi mila. User se poocho kaunsa naam hai"

        // One clear match is called at once. Anything fuzzy, or several candidates, is the user's
        // call to make from a list, never a guess, and the answer is remembered.
        var userChose = false
        val sure = matches.singleOrNull()?.takeIf { it.score <= 1 }
        val contact = sure ?: run {
            userChose = true
            val index = prompts.choose(say("Kisko call karun?", "Who should I call?"), matches.map { it.name })
                ?: return say("Theek hai, call nahi kiya", "Okay, I will not call.")
            matches[index]
        }
        val number = if (contact.numbers.size == 1) {
            contact.numbers[0]
        } else {
            userChose = true
            val index = prompts.choose(say("${contact.name}: kaunsa number?", "Which number for ${contact.name}?"), contact.numbers)
                ?: return say("Theek hai, call nahi kiya", "Okay, I will not call.")
            contact.numbers[index]
        }
        if (userChose) settings.saveContact(key, SavedContact(contact.name, number))
        return placeCall(number, contact.name)
    }

    /** Rings straight away when the call permission is granted, and falls back to the dialer when it is not. */
    private suspend fun placeCall(number: String, who: String): String {
        val uri = Uri.fromParts("tel", number.filter { it.isDigit() || it == '+' }, null)
        return if (prompts.ensureCallPhone()) {
            if (fromBackground) {
                // With no screen of ours open, Android will not let us start the dialer's screen.
                // The phone service can place the call itself, which also works on the lock screen.
                context.getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
            } else {
                launch(Intent(Intent.ACTION_CALL, uri))
            }
            say("$who ko call lagayi ja rahi hai (Android ko call shuru karne ki request di)", "Calling $who.")
        } else {
            launch(Intent(Intent.ACTION_DIAL, uri))
            "Call ki permission nahi mili, isliye dialer $who ke number ke saath khola; user ko call ka button dabana hai"
        }
    }

    /** Every contact with a phone number: display name to its distinct numbers. */
    private suspend fun loadContacts(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val book = linkedMapOf<String, MutableList<String>>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                val list = book.getOrPut(name) { mutableListOf() }
                if (list.none { sameNumber(it, number) }) list += number
            }
        }
        book
    }

    /**
     * Best matches first. Names are compared with punctuation, emoji and doubled letters removed,
     * so "matashree" finds "Maatashree ❤️". Score 0 is the same name, 1 contains it, 2 is a near miss.
     */
    private fun rank(book: Map<String, List<String>>, query: String): List<Contact> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        return book.mapNotNull { (name, numbers) ->
            val n = normalize(name)
            val score = when {
                n == q -> 0
                n.length >= 3 && (n.contains(q) || q.contains(n)) -> 1
                nearMiss(name, query) -> 2
                else -> return@mapNotNull null
            }
            Contact(name, numbers, score)
        }.sortedWith(compareBy({ it.score }, { it.name })).take(6)
    }

    /** Lowercase letters and digits only, with runs of the same character collapsed to one. */
    private fun normalize(s: String): String {
        val sb = StringBuilder()
        s.lowercase().codePoints().forEach { cp ->
            if (Character.isLetterOrDigit(cp)) {
                val last = if (sb.isEmpty()) -1 else sb.codePointBefore(sb.length)
                if (cp != last) sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    /** True when some word of the name is a spelling slip away from the whole query. */
    private fun nearMiss(name: String, query: String): Boolean {
        val q = normalize(query)
        if (q.length < 4) return false
        val allowed = if (q.length >= 8) 2 else 1
        return name.split(Regex("\\s+")).map { normalize(it) }.any {
            it.length >= 3 && editDistance(it, q) <= allowed
        }
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    // ---- Inbox ------------------------------------------------------------

    private suspend fun replyMessage(to: String, text: String): String {
        if (text.isBlank()) return "Error: reply mein kya likhna hai? Exact words chahiye"
        val event = Inbox.replyTarget(context, to.takeIf { it.isNotBlank() })
            ?: return say("Reply dene layak koi message nahi mila", "I couldn't find a message I can reply to.")
        return Inbox.confirmAndSend(context, event, text.trim(), prompts, english)
    }

    private fun teach(args: JSONObject): String {
        val db = InboxDb.get(context)
        val last = db.event(Inbox.lastEventId)
        val rule = args.optString("sender_rule").lowercase()
        if (rule == "always" || rule == "never" || rule == "normal") {
            val named = args.optString("sender").takeIf { it.isNotBlank() }
            val target = named?.let { db.fromSender(it, 1).firstOrNull() } ?: last
                ?: return say("Kiska rule? Pehle kuch padh ke sunao ya naam batao", "Whose rule? Read something out first, or tell me the name.")
            val value = when (rule) { "always" -> Rule.ALWAYS; "never" -> Rule.NEVER; else -> Rule.NONE }
            Inbox.setSenderRule(context, target.senderKey, target.sender, value)
            return when (rule) {
                "always" -> say("${target.sender} ko hamesha padhungi.", "I will always read ${target.sender} out.")
                "never" -> say("${target.sender} ko ab nahi padhungi.", "I will not read ${target.sender} out any more.")
                else -> say("${target.sender} ke liye rule hata diya.", "${target.sender} is back to normal.")
            }
        }
        if (last == null) return say("Pehle kuch padh ke sunaya hi nahi, kis par feedback du?", "I haven't read anything out yet, so there is nothing to mark.")
        val mattered = args.optBoolean("important", true)
        Inbox.feedback(context, last.id, mattered)
        return if (mattered) say("Samajh gayi, aisa message zaroori maanungi.", "Noted. I will treat messages like that as important.")
        else say("Samajh gayi, aise kam padhungi.", "Noted. I will read fewer like that.")
    }

    private fun driveMode(on: Boolean): String {
        DriveMode.set(context, on, "voice")
        return if (on) say("Drive mode on. Zaroori messages padh ke sunaungi.", "Drive mode on. I will read important messages out.")
        else say("Drive mode off.", "Drive mode off.")
    }

    private fun remember(fact: String): String {
        if (fact.isBlank()) return "Error: kya yaad rakhna hai?"
        InboxDb.get(context).putFact(fact.trim())
        return say("Yaad rakh liya.", "I'll remember that.")
    }

    private fun forget(words: String): String {
        val removed = InboxDb.get(context).forgetFacts(words)
        return if (removed == 0) say("Aisa kuch yaad nahi tha.", "I didn't have anything like that.")
        else say("Bhool gayi.", "Forgotten.")
    }

    // ---- Flashlight -------------------------------------------------------

    private fun flashlight(on: Boolean): String {
        val cameras = context.getSystemService(CameraManager::class.java)
        val id = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Error: is phone mein flashlight nahi mili"
        cameras.setTorchMode(id, on)
        return if (on) say("Flashlight on kar di", "Flashlight on.") else say("Flashlight band kar di", "Flashlight off.")
    }

    private companion object {
        fun prop(type: String, description: String) = JSONObject().put("type", type).put("description", description)

        fun schema(required: List<String>, vararg props: Pair<String, JSONObject>) = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().apply { props.forEach { put(it.first, it.second) } })
            .put("required", JSONArray(required))
    }
}
