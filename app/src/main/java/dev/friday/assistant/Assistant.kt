package dev.friday.assistant

import dev.friday.assistant.llm.GeminiProvider
import dev.friday.assistant.llm.GroqProvider
import dev.friday.assistant.llm.LlmRouter
import dev.friday.assistant.tools.FastCommands
import dev.friday.assistant.tools.PhoneTools
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What the chat screen and the voice service share: which models to ask, and what to tell them. */
object Assistant {
    /** Built per question so a key changed in Settings applies immediately. */
    fun router(settings: Settings) = LlmRouter(
        settings.geminiModels.map { GeminiProvider(settings.geminiKey, it) } +
            GroqProvider(settings.groqKey, settings.groqModel),
    )

    /**
     * A plain torch or call command is run on the phone at once, without asking a model: that is the
     * difference between half a second and several. Null when [text] is anything else, or when the
     * tool has something to ask or explain, in which case the model takes over as usual.
     */
    suspend fun fastReply(text: String, tools: PhoneTools): LlmRouter.Reply? {
        val call = FastCommands.match(text) ?: return null
        val output = tools.run(call)
        if (!output.final) return null
        return LlmRouter.Reply(output.text, "Phone (turant)", emptyList(), usedTools = true, direct = true)
    }

    /**
     * Rebuilt for every question, because "7 baje" or "20 minute mein" only means something next to the current time.
     * [spoken] answers will be read aloud; [english] picks the English voice's language instead of Hinglish.
     */
    fun systemPrompt(spoken: Boolean = false, english: Boolean = false, facts: List<String> = emptyList()): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        val language = when {
            spoken && english ->
                "Your answer is read aloud by an English voice, so answer in clear, calm, natural English even " +
                    "if the user spoke Hindi or Hinglish, in one or two short sentences. "
            spoken ->
                "Your answer is read aloud, so use one or two short sentences. Answer in the language the user " +
                    "spoke (English, Hindi or Gujarati), written in that language's own script: Devanagari for " +
                    "Hindi, Gujarati script for Gujarati. "
            else ->
                "Reply in Hinglish (Hindi written in Roman script mixed with English) unless the user writes pure English. "
        }
        val known = if (facts.isEmpty()) "" else " Things the user has told you about themselves (use them naturally, never recite them): ${facts.joinToString("; ")}."
        return "$BASE_PROMPT$language$TOOL_RULES$known Current local time: $now (${TimeZone.getDefault().id})."
    }

    private const val BASE_PROMPT =
        "You are Friday, a personal assistant on the user's Android phone. " +
            "Replies appear in a small chat bubble and are sometimes read aloud, so keep them " +
            "short and conversational with no markdown, lists, or emojis. "

    private const val TOOL_RULES =
        "You are female: in Hindi and Hinglish use feminine forms for yourself (main sun rahi hoon, kar rahi hoon). " +
            "The user may speak English, Hindi, Gujarati or a mix, and the words may reach you in Latin, Devanagari " +
            "or Gujarati script; understand all of them. When you pass a contact's name to a tool, write it in " +
            "English letters the way it would normally be saved, even if it reached you in another script. " +
            "You can set and cancel alarms, start phone calls and switch the flashlight with your tools. " +
            "When a request is clear, act on it without asking for confirmation. Never guess a missing " +
            "detail: if the time (including AM or PM), the contact or the alarm is unclear, ask one short " +
            "question first and wait for the answer. To remove an alarm you need its exact time: if the " +
            "user does not give it, use get_next_alarm and ask whether that is the one. Say a task is done " +
            "only if the tool result says it was confirmed, and never without calling the tool in this very " +
            "reply, even if an earlier answer looked similar; if the result says it could not confirm, say " +
            "exactly that and tell the user to check. You can also read out the user's new WhatsApp messages and mail " +
            "with read_messages, reply to one with reply_message (pass only the words to send; the app asks the user " +
            "to confirm before anything is sent), sum up the day with digest, record with teach whether the last message " +
            "mattered or whether a sender should always or never be read out, switch drive_mode, and keep facts the user " +
            "tells you with remember. For an alarm like 'in 20 minutes', work out the exact " +
            "time from the current time below."
}
