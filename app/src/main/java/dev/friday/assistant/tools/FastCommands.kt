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

    private val MESSAGE_WORDS = setOf("message", "messages", "msg", "mail", "mails", "email", "emails", "whatsapp", "gmail", "notifications")
    private val ASK_WORDS = setOf("read", "padho", "padh", "sunao", "batao", "bolo", "kya", "new", "naya", "nayi", "aaya", "aayi", "check", "any", "tell", "missed")
    private val NOT_A_READ = setOf("send", "bhejo", "reply", "likho", "write", "jawab", "forward", "delete", "mark", "from", "ka", "ki")

    private val REPLY_START = Regex(
        "(?is)^\\s*(?:please\\s+|plz\\s+|ok\\s+)?(?:reply|jawab|send|bhejo|bhej do)\\b[\\s,:-]*" +
            "(?:karo|kar do|de do|bolo|likho|ki|ke|that|saying|him|her|them|it)?[\\s,:-]*(.*)$",
    )
    private val TO_SOMEONE = Regex("(?is)^(.+?)\\s+(?:to|ko)\\s+([A-Za-z][A-Za-z ]{0,30})$")
    private val NOT_A_REPLY = setOf("message", "messages", "mail", "email", "sms", "photo", "location", "money", "file")

    /**
     * "Reply ok", "send hi to Gauri", "reply de do": answers the message just read out. The words are kept exactly as
     * said (or asked for when missing), because a model asked to fill a gap tends to make something up.
     */
    private fun reply(text: String): ToolCall? {
        var body = REPLY_START.find(text)?.groupValues?.get(1)?.trim() ?: return null
        val first = body.lowercase().split(Regex("\\s+")).firstOrNull().orEmpty()
        if (first in NOT_A_REPLY || body.split(Regex("\\s+")).size > 25) return null
        var to = ""
        TO_SOMEONE.find(body)?.let {
            body = it.groupValues[1].trim()
            to = it.groupValues[2].trim()
        }
        return ToolCall("fast-reply", "reply_message", JSONObject().put("text", body).put("to", to))
    }

    fun match(text: String): ToolCall? {
        reply(text)?.let { return it }
        // \p{M} keeps the vowel signs that Hindi and Gujarati words are built from; without it they split in pieces.
        val words = text.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotEmpty() }
            .map { HEARD_AS[it] ?: it }
        if (words.isEmpty() || words.size > 6) return null
        return torch(words) ?: call(words) ?: inbox(words) ?: digest(words) ?: drive(words)
    }

    /** "Kya aaya?", "messages padho", "any new mail": read out what is new and matters. */
    private fun inbox(words: List<String>): ToolCall? {
        val asksWhatCame = words.size <= 5 && words.any { it in setOf("aaya", "aayi", "miss", "missed") } &&
            words.any { it in setOf("kya", "what", "koi") }
        val readsMessages = words.any { it in MESSAGE_WORDS } && words.any { it in ASK_WORDS } && words.none { it in NOT_A_READ }
        if (!(asksWhatCame || readsMessages) || words.size > 7) return null
        return ToolCall("fast-inbox", "read_messages", JSONObject())
    }

    private fun digest(words: List<String>): ToolCall? =
        if ("digest" in words || ("summary" in words && words.any { it in setOf("aaj", "today", "din") })) {
            ToolCall("fast-digest", "digest", JSONObject())
        } else {
            null
        }

    private fun drive(words: List<String>): ToolCall? {
        if ("drive" !in words || "mode" !in words) return null
        val on = words.any { it in ON }
        val off = words.any { it in OFF }
        if (on == off) return null
        return ToolCall("fast-drive", "drive_mode", JSONObject().put("on", on))
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
