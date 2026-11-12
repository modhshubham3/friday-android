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

    /** Which language a piece of text is written in, judged by its script. */
    private fun languageOf(text: String): String {
        var hindi = 0
        var gujarati = 0
        var latin = 0
        for (c in text) {
            when (c.code) {
                in 0x0900..0x097F -> hindi++
                in 0x0A80..0x0AFF -> gujarati++
                in 0x41..0x7A -> latin++
            }
        }
        return when {
            hindi > latin && hindi >= gujarati -> "hi"
            gujarati > latin -> "gu"
            else -> "en"
        }
    }

    /**
     * Sets the voice for [text]: the saved English voice for English, and the best installed Hindi or Gujarati
     * voice when the text is in those scripts. Without a voice for that language the phone's default is used.
     */
    fun apply(tts: TextToSpeech, settings: Settings, text: String = "") {
        runCatching {
            when (val language = languageOf(text)) {
                "en" -> {
                    val all = available(tts)
                    val chosen = all.firstOrNull { it.name == settings.voiceName } ?: all.firstOrNull()
                    if (chosen != null) tts.voice = chosen else tts.language = Locale("en", "IN")
                }
                else -> {
                    val installed = tts.voices.orEmpty()
                        .filter { it.locale.language == language && "notInstalled" !in it.features }
                        .maxByOrNull { it.quality }
                    if (installed != null) tts.voice = installed else tts.language = Locale(language, "IN")
                }
            }
            tts.setSpeechRate(0.95f)
        }
    }
}
