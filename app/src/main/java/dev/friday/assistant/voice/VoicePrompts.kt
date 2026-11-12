package dev.friday.assistant.voice

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.friday.assistant.tools.UserPrompts
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The same questions the screen asks (pick from a list, confirm before removing something),
 * asked out loud and answered by voice, for when no screen is open.
 *
 * Doubt always resolves to "do nothing": an answer that cannot be understood cancels the action.
 * Permissions cannot be asked for here, so they must have been granted from the app beforehand.
 */
class VoicePrompts(
    private val context: Context,
    private val listener: Listener,
    private val speaker: Speaker,
    private val english: Boolean = false,
) : UserPrompts {

    override suspend fun ensureContacts() = granted(android.Manifest.permission.READ_CONTACTS)

    override suspend fun ensureCallPhone() = granted(android.Manifest.permission.CALL_PHONE)

    override suspend fun choose(title: String, options: List<String>): Int? {
        val names = if (english) ORDINALS_EN else ORDINALS_HI
        val shown = options.take(names.size)
        val list = shown.mapIndexed { i, name -> "${names[i]}: ${Speaker.speakable(name)}" }.joinToString(". ")
        repeat(2) { attempt ->
            speaker.speak(if (attempt == 0) "$title $list" else (if (english) "Sorry, I did not catch that. " else "Samajh nahi aaya. ") + list)
            val heard = hear() ?: return@repeat
            pick(heard, shown)?.let { return it }
        }
        return null
    }

    override suspend fun confirm(message: String, yes: String): Boolean {
        speaker.speak(message)
        val words = words(hear() ?: return false)
        // An answer with any "no" in it is a no, whatever else it says.
        return words.none { it in NO } && words.any { it in YES }
    }

    private suspend fun hear(): String? {
        delay(250) // let the end of our own speech die away before listening
        return withTimeoutOrNull(LISTEN_MS) { listener.listen() }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun words(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** A spoken position ("doosra", "2") wins; otherwise the answer must name exactly one option. */
    private fun pick(heard: String, options: List<String>): Int? {
        val spoken = words(heard)
        val byPosition = spoken.mapNotNull { POSITIONS[it] }.distinct()
        if (byPosition.size == 1 && byPosition[0] < options.size) return byPosition[0]

        val said = squash(heard)
        val named = options.indices.filter { i ->
            val name = squash(options[i])
            name.length >= 3 && said.isNotEmpty() && (name.contains(said) || said.contains(name))
        }
        return named.singleOrNull()
    }

    /** Letters and digits only, lowercase, doubled letters collapsed, as when matching contact names. */
    private fun squash(s: String): String {
        val sb = StringBuilder()
        s.lowercase().codePoints().forEach { cp ->
            if (Character.isLetterOrDigit(cp) && (sb.isEmpty() || sb.codePointBefore(sb.length) != cp)) sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private companion object {
        const val LISTEN_MS = 12_000L
        val ORDINALS_HI = listOf("Pehla", "Doosra", "Teesra", "Chautha", "Paanchva", "Chhatha")
        val ORDINALS_EN = listOf("First", "Second", "Third", "Fourth", "Fifth", "Sixth")

        val POSITIONS: Map<String, Int> = buildMap {
            listOf("pehla", "pehle", "pahla", "first", "ek", "one", "1").forEach { put(it, 0) }
            listOf("doosra", "dusra", "doosre", "dusre", "second", "do", "two", "2").forEach { put(it, 1) }
            listOf("teesra", "tisra", "third", "teen", "three", "3").forEach { put(it, 2) }
            listOf("chautha", "chauthe", "fourth", "char", "four", "4").forEach { put(it, 3) }
            listOf("paanchva", "panchva", "fifth", "paanch", "panch", "five", "5").forEach { put(it, 4) }
            listOf("chhatha", "chhatwa", "sixth", "chhe", "six", "6").forEach { put(it, 5) }
        }

        val YES = setOf("haan", "han", "ha", "haa", "yes", "yeah", "yep", "ok", "okay", "theek", "thik", "sahi", "kar", "karo", "hata")
        val NO = setOf("nahi", "nahin", "nai", "no", "mat", "ruko", "cancel", "na", "nope", "stop")
    }
}
