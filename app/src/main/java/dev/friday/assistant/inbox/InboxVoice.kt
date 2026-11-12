package dev.friday.assistant.inbox

import android.content.Context
import dev.friday.assistant.tools.UserPrompts
import dev.friday.assistant.voice.Listener
import dev.friday.assistant.voice.Speaker
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the user can say right after a message is read out, handled on the phone without asking a model,
 * so that it is instant: reply, skip, read all, mark it as mattering or not, never read this person, stop.
 */
object InboxVoice {
    sealed interface Cmd {
        data class Reply(val text: String?) : Cmd
        data object Skip : Cmd
        data object Full : Cmd
        data object Mute : Cmd
        data object Stop : Cmd
        data object Mattered : Cmd
        data object NotMattered : Cmd
        data object None : Cmd
    }

    private val REPLY = Regex(
        "(?i)\\b(reply|jawab|likho|bolo|bol do|tell (?:him|her|them)|say)\\b[\\s,:-]*(?:karo|kar do|bolo|likho|ki|ke|that|saying)?[\\s,:-]*(.*)",
    )

    fun parse(said: String?): Cmd {
        val s = said?.trim().orEmpty()
        if (s.isEmpty()) return Cmd.None
        val lower = s.lowercase()
        val words = lower.split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotEmpty() }
        fun has(vararg w: String) = words.any { it in w }
        return when {
            has("stop", "bas", "enough") || "drive mode off" in lower || "band karo" in lower -> Cmd.Stop
            "mat padho" in lower || has("ignore", "block", "mute") -> Cmd.Mute
            "not important" in lower || "zaroori nahi" in lower || has("faltu", "bekaar", "unimportant") -> Cmd.NotMattered
            has("important", "zaroori") -> Cmd.Mattered
            "read all" in lower || "poora" in lower || "puri" in lower || has("full") -> Cmd.Full
            else -> {
                val match = REPLY.find(s)
                when {
                    match != null -> Cmd.Reply(match.groupValues[2].trim().ifEmpty { null })
                    has("haan", "yes", "ha", "han", "ok", "okay") -> Cmd.Reply(null)
                    has("skip", "next", "agla", "nahi", "no", "nope", "chhodo", "later", "baad") -> Cmd.Skip
                    else -> Cmd.None
                }
            }
        }
    }

    /** Carries out what the user said about [event]. Returns false when reading should stop altogether. */
    suspend fun respond(
        context: Context,
        event: Event,
        said: String?,
        speaker: Speaker,
        listener: Listener,
        prompts: UserPrompts,
    ): Boolean {
        when (val cmd = parse(said)) {
            is Cmd.Reply -> {
                val body = cmd.text ?: run {
                    speaker.speak("What should I say?")
                    withTimeoutOrNull(12_000) { listener.listen() }
                }
                if (body.isNullOrBlank()) {
                    speaker.speak("Okay, no reply.")
                } else {
                    speaker.speak(Inbox.confirmAndSend(context, event, body.trim(), prompts, english = true))
                }
            }
            Cmd.Full -> speaker.speak(Inbox.clip(event.text, 800))
            Cmd.Mute -> {
                Inbox.setSenderRule(context, event.senderKey, event.sender, Rule.NEVER)
                speaker.speak("Okay, I will not read ${event.sender} out any more.")
            }
            Cmd.Mattered -> {
                Inbox.feedback(context, event.id, true)
                speaker.speak("Noted. I will treat messages like that as important.")
            }
            Cmd.NotMattered -> {
                Inbox.feedback(context, event.id, false)
                speaker.speak("Noted. I will read fewer like that.")
            }
            Cmd.Stop -> {
                DriveMode.set(context, false, "voice")
                speaker.speak("Drive mode off.")
                return false
            }
            Cmd.Skip, Cmd.None -> {}
        }
        return true
    }
}
