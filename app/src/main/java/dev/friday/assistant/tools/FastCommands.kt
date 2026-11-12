package dev.friday.assistant.tools

import dev.friday.assistant.llm.ToolCall
import org.json.JSONObject

/**
 * Plain commands the phone can recognise by itself, so they run at once instead of waiting for a model
 * to read them. Anything not clearly one of these returns null and goes to the model as usual, so this
 * only ever has to be right about the easy cases.
 */
object FastCommands {
    private val TORCH = setOf("torch", "flashlight")
    private val ON = setOf("on", "chalu", "jalao", "jala", "start", "kholo", "enable")
    private val OFF = setOf("off", "band", "bandh", "bujha", "bujhao", "stop", "disable")

    private val CALL_WORDS = setOf("call", "phone", "dial")
    private val FILLER = setOf(
        "call", "phone", "dial", "karo", "kar", "do", "de", "dena", "ko", "to", "ka", "ki", "lagao", "laga",
        "please", "pls", "mujhe", "mera", "meri", "hey", "friday", "the", "a", "me", "can", "you", "could",
        "make", "give", "zara", "jara", "ek", "abhi", "now", "hello",
    )
    private val NOT_A_CALL = setOf(
        "history", "log", "logs", "record", "recording", "kaise", "kya", "kyun", "kab",
        "why", "how", "what", "when", "missed", "block",
    )

    fun match(text: String): ToolCall? {
        val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 6) return null
        return torch(words) ?: call(words)
    }

    private fun torch(words: List<String>): ToolCall? {
        val about = words.any { it in TORCH } || ("flash" in words && "light" in words)
        if (!about) return null
        val on = words.any { it in ON }
        val off = words.any { it in OFF }
        if (on == off) return null // neither, or both: not clear enough to act on
        return ToolCall("fast-torch", "flashlight", JSONObject().put("on", on))
    }

    private fun call(words: List<String>): ToolCall? {
        if (words.none { it in CALL_WORDS } || words.any { it in NOT_A_CALL }) return null
        val rest = words.filter { it !in FILLER }
        if (rest.isEmpty() || rest.size > 3) return null
        val digits = rest.joinToString("")
        val target = if (digits.all { it.isDigit() } && digits.length >= 5) digits else rest.joinToString(" ")
        return ToolCall("fast-call", "make_call", JSONObject().put("name_or_number", target))
    }
}
