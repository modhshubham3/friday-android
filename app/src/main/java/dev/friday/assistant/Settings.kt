package dev.friday.assistant

import android.content.Context
import org.json.JSONObject

/** A contact the user picked once, so the same words next time go straight to them. */
data class SavedContact(val name: String, val number: String)

/**
 * API keys and model names, entered once inside the app.
 * Stored in the app's private SharedPreferences, so the APK itself holds no keys.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("friday", Context.MODE_PRIVATE)

    var geminiKey: String
        get() = prefs.getString("gemini_key", "").orEmpty()
        set(v) = prefs.edit().putString("gemini_key", v.trim()).apply()

    /**
     * Comma-separated Gemini models, tried in order. The free tier often answers
     * 503 (busy) or 429 (quota used up) for one model while another works.
     */
    var geminiModel: String
        get() = prefs.getString("gemini_model", null)
            ?.takeUnless { it in RETIRED_DEFAULTS }
            ?: DEFAULT_GEMINI_MODELS
        set(v) = prefs.edit().putString("gemini_model", v.trim()).apply()

    val geminiModels: List<String>
        get() = geminiModel.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    var groqKey: String
        get() = prefs.getString("groq_key", "").orEmpty()
        set(v) = prefs.edit().putString("groq_key", v.trim()).apply()

    var groqModel: String
        get() = prefs.getString("groq_model", null) ?: "llama-3.3-70b-versatile"
        set(v) = prefs.edit().putString("groq_model", v.trim()).apply()

    /** The Clock app used for alarms; null means ask. */
    var alarmPackage: String?
        get() = prefs.getString("alarm_pkg", null)
        set(v) = prefs.edit().apply { if (v == null) remove("alarm_pkg") else putString("alarm_pkg", v) }.apply()

    /** [key] is the spoken name with punctuation and doubled letters removed, so "Gauri" and "gauri" share an entry. */
    fun savedContact(key: String): SavedContact? = runCatching {
        JSONObject(prefs.getString("contact_choices", "{}")).optJSONObject(key)
            ?.let { SavedContact(it.getString("name"), it.getString("number")) }
    }.getOrNull()

    fun saveContact(key: String, contact: SavedContact) {
        val all = runCatching { JSONObject(prefs.getString("contact_choices", "{}")) }.getOrDefault(JSONObject())
        all.put(key, JSONObject().put("name", contact.name).put("number", contact.number))
        prefs.edit().putString("contact_choices", all.toString()).apply()
    }

    val savedContactCount: Int
        get() = runCatching { JSONObject(prefs.getString("contact_choices", "{}")).length() }.getOrDefault(0)

    fun clearSavedContacts() = prefs.edit().remove("contact_choices").apply()

    /** True from "Chalu karo" until the user turns Hey Friday off, so opening the app can bring it back after the system stopped it. */
    var voiceWanted: Boolean
        get() = prefs.getBoolean("voice_wanted", false)
        set(v) = prefs.edit().putBoolean("voice_wanted", v).apply()

    /** The text-to-speech voice picked in "Awaaz chuno"; null means the best English one available. */
    var voiceName: String?
        get() = prefs.getString("voice_name", null)
        set(v) = prefs.edit().apply { if (v == null) remove("voice_name") else putString("voice_name", v) }.apply()

    /** Spoken replies in English (the film's Friday) instead of Hinglish read by an English voice. */
    var speakEnglish: Boolean
        get() = prefs.getBoolean("speak_english", true)
        set(v) = prefs.edit().putBoolean("speak_english", v).apply()

    var themeId: String
        get() = prefs.getString("theme", null) ?: "classic"
        set(v) = prefs.edit().putString("theme", v).apply()

    var lookId: String
        get() = prefs.getString("look", null) ?: "std"
        set(v) = prefs.edit().putString("look", v).apply()

    var sizeId: String
        get() = prefs.getString("size", null) ?: "m"
        set(v) = prefs.edit().putString("size", v).apply()

    private companion object {
        const val DEFAULT_GEMINI_MODELS =
            "gemini-3.5-flash,gemini-3.1-flash-lite,gemini-3.5-flash-lite,gemini-flash-lite-latest,gemini-flash-latest"

        /** Earlier single-model defaults; a phone that saved one moves to the list above. */
        val RETIRED_DEFAULTS = setOf("gemini-2.5-flash", "gemini-3.8-flash")
    }
}
