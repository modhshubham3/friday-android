package dev.friday.assistant.voice

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small on-phone log of what the voice side heard and did. The voice service runs with the screen
 * off, so there is nowhere else to look when it does not work; Settings shows this file.
 */
object VoiceLog {
    private const val MAX_LINES = 200
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(context: Context, line: String) {
        runCatching {
            val file = file(context)
            val lines = (if (file.exists()) file.readLines() else emptyList()) + "${clock.format(Date())}  $line"
            file.writeText(lines.takeLast(MAX_LINES).joinToString("\n"))
        }
    }

    @Synchronized
    fun read(context: Context): String =
        runCatching { file(context).readText().ifBlank { null } }.getOrNull() ?: "(abhi kuch nahi hai)"

    @Synchronized
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.filesDir, "voice.log")
}
