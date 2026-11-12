package dev.friday.assistant.voice

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import dev.friday.assistant.Settings
import java.util.Locale

/** Which installed text-to-speech voice Friday speaks with. */
object Voices {
    /**
     * English voices installed on the phone, in the order a Friday-like assistant would want them:
     * Irish first (the accent of the film's Friday), then British, Indian, the rest; better quality first within each.
     */
    fun available(tts: TextToSpeech): List<Voice> = runCatching {
        tts.voices.orEmpty()
            .filter { it.locale.language == "en" && "notInstalled" !in it.features }
            .sortedWith(compareBy<Voice>({ rank(it) }, { -it.quality }, { it.name }))
    }.getOrDefault(emptyList())

    private fun rank(v: Voice) = when (v.locale.country) {
        "IE" -> 0
        "GB" -> 1
        "IN" -> 2
        else -> 3
    }

    /** A name a person can tell apart; the engine's own names ("en-ie-x-iea-network") mean nothing to read. */
    fun label(v: Voice, number: Int) =
        "${v.locale.displayName} · ${if (v.isNetworkConnectionRequired) "online" else "offline"} · #$number"

    /** Sets the saved voice, or the best one available, or plain Indian English when speaking Hinglish. */
    fun apply(tts: TextToSpeech, settings: Settings) {
        runCatching {
            val chosen = if (settings.speakEnglish) {
                val all = available(tts)
                all.firstOrNull { it.name == settings.voiceName } ?: all.firstOrNull()
            } else {
                null
            }
            if (chosen != null) tts.voice = chosen else tts.language = Locale("en", "IN")
            tts.setSpeechRate(0.95f)
        }
    }
}
