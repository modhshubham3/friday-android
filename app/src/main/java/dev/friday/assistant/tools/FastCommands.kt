package dev.friday.assistant.tools

import dev.friday.assistant.llm.ToolCall
import org.json.JSONObject

/**
 * Plain commands the phone can recognise by itself, so they run at once instead of waiting for a model
 * to read them. Anything not clearly one of these returns null and goes to the model as usual, so this
 * only ever has to be right about the easy cases. Words are listed in English, Hinglish, Hindi and Gujarati
 * because the speech engine writes in whichever script matches the language it was set to listen for.
 */
object FastCommands {
    private val TORCH = setOf(
        "torch", "flashlight", "flash",
        "टॉर्च", "टार्च", "टोर्च", "फ्लैशलाइट", "फ्लैश",
        "ટોર્ચ", "ટૉર્ચ", "ફ્લેશલાઇટ", "ફ્લેશ",
    )
    private val ON = setOf(
        "on", "chalu", "jalao", "jala", "start", "kholo", "enable",
        "ऑन", "ओन", "चालू", "जलाओ", "जला", "जलाएं", "शुरू", "खोलो",
        "ઓન", "ચાલુ", "શરૂ", "પ્રગટાવો",
    )
    private val OFF = setOf(
        "off", "band", "bandh", "bujha", "bujhao", "stop", "disable",
        "ऑफ", "ओफ", "बंद", "बन्द", "बुझाओ", "बुझा", "बुझाएं",
        "ઓફ", "બંધ", "બુઝાવો",
    )

    /** What the speech engine tends to write for the words that matter here (seen in the voice log). */
    private val HEARD_AS = mapOf("clash" to "flash", "flush" to "flash", "flesh" to "flash")

    private val CALL_WORDS = setOf("call", "phone", "dial", "कॉल", "कोल", "फोन", "फ़ोन", "डायल", "કૉલ", "કોલ", "ફોન", "ડાયલ")
    private val FILLER = setOf(
        "call", "phone", "dial", "karo", "kar", "do", "de", "dena", "ko", "to", "ka", "ki", "lagao", "laga",
        "please", "pls", "mujhe", "mera", "meri", "hey", "friday", "the", "a", "me", "can", "you", "could",
        "make", "give", "zara", "jara", "ek", "abhi", "now", "hello",
        "कॉल", "कोल", "फोन", "फ़ोन", "डायल", "करो", "कर", "दो", "दे", "को", "का", "की", "लगाओ", "लगा", "प्लीज", "मुझे",
        "કૉલ", "કોલ", "ફોન", "ડાયલ", "કરો", "કર", "ને", "મને", "લગાવો", "લગાવ",
    )
    private val NOT_A_CALL = setOf(
        "history", "log", "logs", "record", "recording", "kaise", "kya", "kyun", "kab",
        "why", "how", "what", "when", "missed", "block",
    )

    fun match(text: String): ToolCall? {
        // \p{M} keeps the vowel signs that Hindi and Gujarati words are built from; without it they split in pieces.
        val words = text.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotEmpty() }
            .map { HEARD_AS[it] ?: it }
        if (words.isEmpty() || words.size > 6) return null
        return torch(words) ?: call(words)
    }

    private fun torch(words: List<String>): ToolCall? {
        if (words.none { it in TORCH }) return null
        val on = words.any { it in ON }
        // "flash off" comes through as "clash of", so "of" counts as "off" once the subject is clearly the torch.
        val off = words.any { it in OFF } || "of" in words
        if (on == off) return null // neither, or both: not clear enough to act on
        return ToolCall("fast-torch", "flashlight", JSONObject().put("on", on))
    }

    private fun call(words: List<String>): ToolCall? {
        if (words.none { it in CALL_WORDS } || words.any { it in NOT_A_CALL }) return null
        val rest = words.filter { it !in FILLER }
        if (rest.isEmpty() || rest.size > 3) return null
        // A name in Hindi or Gujarati script cannot be matched to contacts saved in English letters;
        // the model turns it into the right spelling, so leave it to the model.
        if (rest.any { word -> word.any { it.code > 127 } }) return null
        val digits = rest.joinToString("")
        val target = if (digits.all { it.isDigit() } && digits.length >= 5) digits else rest.joinToString(" ")
        return ToolCall("fast-call", "make_call", JSONObject().put("name_or_number", target))
    }
}
