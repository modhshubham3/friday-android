package dev.friday.assistant.tools

import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import dev.friday.assistant.Settings
import dev.friday.assistant.llm.ToolCall
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
    private val activity: ComponentActivity,
    private val settings: Settings,
    private val prompts: UserPrompts,
) {
    data class ClockApp(val pkg: String, val label: String)

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
            "Start a phone call by opening the dialer with the number filled in. " +
                "Pass the contact's name as the user said it, or a phone number. " +
                "The app shows the user a list when the name is unclear.",
            schema(
                required = listOf("name_or_number"),
                "name_or_number" to prop("string", "Contact name or phone number"),
            ),
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

    suspend fun run(call: ToolCall): String = try {
        when (call.name) {
            "set_alarm" -> setAlarm(call.args)
            "get_next_alarm" -> nextAlarmText()
            "cancel_alarm" -> cancelAlarm(call.args)
            "make_call" -> makeCall(call.args.optString("name_or_number"))
            "flashlight" -> flashlight(call.args.optBoolean("on", true))
            else -> "Error: '${call.name}' naam ka koi tool nahi hai"
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "Error: ${e.message ?: e.javaClass.simpleName}"
    }

    // ---- Alarm ------------------------------------------------------------

    /** Every app that can set alarms. Phones often have two (the maker's Clock and Google's). */
    @Suppress("DEPRECATION")
    fun clockApps(): List<ClockApp> {
        val pm = activity.packageManager
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
        val index = prompts.choose("Alarm kaunsi Clock app mein lagaun?", apps.map { it.label }) ?: return null
        settings.alarmPackage = apps[index].pkg
        return apps[index]
    }

    private fun alarmManager() = activity.getSystemService(AlarmManager::class.java)

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
        val app = pickClockApp(apps) ?: return "User ne Clock app nahi chuni, isliye alarm nahi lagaya"

        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .setPackage(app.pkg)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            return "Error: ${app.label} alarm set nahi kar payi"
        }
        val expected = nextOccurrence(hour, minute)
        return if (waitFor { isAt(it, expected) }) {
            "Alarm ${time(hour, minute)} ka ${app.label} mein set ho gaya (system ne confirm kiya)"
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
        if (!prompts.confirm("$label ka alarm hata du?", "Haan, hata do")) {
            return "User ne mana kiya, koi alarm nahi hataya"
        }
        val apps = clockApps()
        if (apps.isEmpty()) return "Error: is phone mein koi Clock app nahi mili"
        val app = pickClockApp(apps) ?: return "User ne Clock app nahi chuni, isliye kuch nahi hataya"

        val expected = nextOccurrence(hour, minute)
        val wasNext = isAt(alarmManager().nextAlarmClock?.triggerTime, expected)
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
            .setPackage(app.pkg)
            .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            return "Error: ${app.label} alarm hatane ki request nahi leti. User ko Clock app se haath se hatana hoga"
        }
        if (!wasNext) {
            return "Hatane ki request ${app.label} ko bheji, par confirm nahi kar sakta kyunki ye agla alarm nahi hai. " +
                "User ko Clock app mein check karne ko bolo"
        }
        return if (waitFor { !isAt(it, expected) }) {
            "$label ka alarm hat gaya (system ne confirm kiya)"
        } else {
            "$label ka alarm abhi bhi agla alarm dikh raha hai, ${app.label} ne request nahi maani. " +
                "User ko Clock app se haath se hatana hoga"
        }
    }

    // ---- Call -------------------------------------------------------------

    private data class Contact(val name: String, val numbers: List<String>, val score: Int)

    private suspend fun makeCall(target: String): String {
        val raw = target.trim()
        if (raw.isEmpty()) return "Error: kisko call karna hai?"

        val number: String
        val who: String
        if (Regex("^[+0-9 ()-]{5,}$").matches(raw)) {
            number = raw
            who = raw
        } else {
            if (!prompts.ensureContacts()) {
                return "Error: contacts ki permission nahi mili, naam se call nahi kar sakta. Number bolo"
            }
            val matches = findContacts(raw)
            if (matches.isEmpty()) return "Error: '$raw' se milta koi contact nahi mila. User se poocho kaunsa naam hai"
            // One clear match goes straight to the dialer. Anything fuzzy, or several candidates,
            // is the user's call to make, from a list, and never a guess.
            val sure = matches.singleOrNull()?.takeIf { it.score <= 1 }
            val contact = sure ?: run {
                val index = prompts.choose("Kisko call karun?", matches.map { it.name })
                    ?: return "User ne koi contact nahi chuna, call nahi kiya"
                matches[index]
            }
            who = contact.name
            number = if (contact.numbers.size == 1) {
                contact.numbers[0]
            } else {
                val index = prompts.choose("${contact.name}: kaunsa number?", contact.numbers)
                    ?: return "User ne number nahi chuna, call nahi kiya"
                contact.numbers[index]
            }
        }
        // ACTION_DIAL opens the dialer and leaves the final tap to the user. A misheard name
        // must not ring the wrong person on its own.
        val dial = number.filter { it.isDigit() || it == '+' }
        activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", dial, null)))
        return "Dialer $who ke number ke saath khul gaya, user ko call ka button dabana hai"
    }

    /**
     * Best matches first. Names are compared with punctuation, emoji and doubled letters removed,
     * so "matashree" finds "Maatashree ❤️". Score 0 is the same name, 1 contains it, 2 is a near miss.
     */
    private suspend fun findContacts(query: String): List<Contact> = withContext(Dispatchers.IO) {
        val q = normalize(query)
        if (q.isEmpty()) return@withContext emptyList()
        val numbersByName = linkedMapOf<String, MutableList<String>>()
        activity.contentResolver.query(
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
                val list = numbersByName.getOrPut(name) { mutableListOf() }
                val digits = number.filter { it.isDigit() }
                if (list.none { it.filter { ch -> ch.isDigit() } == digits }) list += number
            }
        }
        numbersByName.mapNotNull { (name, numbers) ->
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

    // ---- Flashlight -------------------------------------------------------

    private fun flashlight(on: Boolean): String {
        val cameras = activity.getSystemService(CameraManager::class.java)
        val id = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Error: is phone mein flashlight nahi mili"
        cameras.setTorchMode(id, on)
        return if (on) "Flashlight on kar di" else "Flashlight band kar di"
    }

    private companion object {
        fun prop(type: String, description: String) = JSONObject().put("type", type).put("description", description)

        fun schema(required: List<String>, vararg props: Pair<String, JSONObject>) = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().apply { props.forEach { put(it.first, it.second) } })
            .put("required", JSONArray(required))
    }
}
