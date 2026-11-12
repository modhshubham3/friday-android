package dev.friday.assistant

import android.content.Context

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
