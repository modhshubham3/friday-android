package dev.friday.assistant

import dev.friday.assistant.llm.GeminiProvider
import dev.friday.assistant.llm.GroqProvider
import dev.friday.assistant.llm.LlmRouter
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

    /** Rebuilt for every question, because "7 baje" or "20 minute mein" only means something next to the current time. */
    fun systemPrompt(spoken: Boolean = false): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        val voice = if (spoken) {
            " This request was spoken and your answer will be read aloud: use one or two short sentences."
        } else {
            ""
        }
        return "$BASE_PROMPT$voice Current local time: $now (${TimeZone.getDefault().id})."
    }

    private const val BASE_PROMPT =
        "You are Friday, a personal assistant on the user's Android phone. " +
            "Replies appear in a small chat bubble and are sometimes read aloud, so keep them " +
            "short and conversational with no markdown, lists, or emojis. Reply in Hinglish (Hindi " +
            "written in Roman script mixed with English) unless the user writes pure English. " +
            "You can set and cancel alarms, start phone calls and switch the flashlight with your tools. " +
            "When a request is clear, act on it without asking for confirmation. Never guess a missing " +
            "detail: if the time (including AM or PM), the contact or the alarm is unclear, ask one short " +
            "question first and wait for the answer. To remove an alarm you need its exact time: if the " +
            "user does not give it, use get_next_alarm and ask whether that is the one. Say a task is done " +
            "only if the tool result says it was confirmed, and never without calling the tool in this very " +
            "reply, even if an earlier answer looked similar; if the result says it could not confirm, say " +
            "exactly that and tell the user to check. For an alarm like 'in 20 minutes', work out the exact " +
            "time from the current time below."
}
