package dev.friday.assistant.tools

import android.content.ActivityNotFoundException
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import dev.friday.assistant.llm.ToolCall
import dev.friday.assistant.llm.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The things Friday can do on the phone. Each tool has a spec (what the model sees)
 * and a handler (what the app does). Handlers return a short sentence for the model
 * to relay, and report failure in words rather than by throwing.
 */
class PhoneTools(
    private val activity: ComponentActivity,
    /** Asks the user for the contacts permission, returning whether it was granted. */
    private val ensureContacts: suspend () -> Boolean,
) {
    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "set_alarm",
            "Set an alarm on the phone for the next time the clock shows this hour and minute.",
            schema(
                required = listOf("hour", "minute"),
                "hour" to prop("integer", "Hour in 24-hour time, 0 to 23"),
                "minute" to prop("integer", "Minute, 0 to 59"),
                "label" to prop("string", "Optional short name for the alarm"),
            ),
        ),
        ToolSpec(
            "make_call",
            "Start a phone call: opens the dialer with the number filled in. " +
                "Give a contact's name exactly as the user said it, or a phone number.",
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

    private fun setAlarm(args: JSONObject): String {
        if (!args.has("hour") || !args.has("minute")) return "Error: alarm ka hour aur minute chahiye"
        val hour = args.getInt("hour")
        val minute = args.getInt("minute")
        if (hour !in 0..23 || minute !in 0..59) return "Error: $hour:$minute sahi samay nahi hai"
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true) // no Clock screen, so it works without taking over
        args.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return try {
            activity.startActivity(intent)
            "Alarm ${"%02d:%02d".format(hour, minute)} ke liye Clock app ko bhej diya"
        } catch (e: ActivityNotFoundException) {
            "Error: is phone mein alarm ke liye koi Clock app nahi mili"
        }
    }

    // ---- Call -------------------------------------------------------------

    private sealed interface Match {
        data class One(val name: String, val number: String) : Match
        data class Many(val names: List<String>) : Match
        data object None : Match
    }

    private suspend fun makeCall(target: String): String {
        val raw = target.trim()
        if (raw.isEmpty()) return "Error: kisko call karna hai?"

        val number: String
        val who: String
        if (Regex("^[+0-9 ()-]{5,}$").matches(raw)) {
            number = raw
            who = raw
        } else {
            if (!ensureContacts()) return "Error: contacts ki permission nahi mili, naam se call nahi kar sakta. Number bolo."
            when (val match = findContact(raw)) {
                is Match.One -> { number = match.number; who = match.name }
                // Only names go back to the model, never numbers: the model needs to ask which one, nothing more.
                is Match.Many -> return "Kai contacts mile: ${match.names.joinToString(", ")}. User se poocho kaunsa wala."
                Match.None -> return "Error: '$raw' naam ka koi contact nahi mila"
            }
        }
        // ACTION_DIAL opens the dialer and leaves the final tap to the user. A misheard name
        // must not ring the wrong person on its own.
        val dial = number.filter { it.isDigit() || it == '+' }
        activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", dial, null)))
        return "Dialer $who ke number ke saath khul gaya, user ko call ka button dabana hai"
    }

    private suspend fun findContact(query: String): Match = withContext(Dispatchers.IO) {
        val byName = linkedMapOf<String, String>() // display name -> first number
        activity.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                byName.putIfAbsent(name, number)
            }
        }
        val exact = byName.entries.filter { it.key.equals(query, ignoreCase = true) }
        when {
            exact.size == 1 -> Match.One(exact[0].key, exact[0].value)
            byName.size == 1 -> byName.entries.first().let { Match.One(it.key, it.value) }
            byName.isEmpty() -> Match.None
            else -> Match.Many(byName.keys.take(5))
        }
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
