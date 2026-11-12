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

    var geminiModel: String
        get() = prefs.getString("gemini_model", null) ?: "gemini-3.8-flash"
        set(v) = prefs.edit().putString("gemini_model", v.trim()).apply()

    var groqKey: String
        get() = prefs.getString("groq_key", "").orEmpty()
        set(v) = prefs.edit().putString("groq_key", v.trim()).apply()

    var groqModel: String
        get() = prefs.getString("groq_model", null) ?: "llama-3.3-70b-versatile"
        set(v) = prefs.edit().putString("groq_model", v.trim()).apply()
}
