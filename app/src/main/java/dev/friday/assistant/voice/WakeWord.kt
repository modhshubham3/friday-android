package dev.friday.assistant.voice

import android.content.Context
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import dev.friday.assistant.Settings

/**
 * The always-on listener. Picovoice Porcupine runs on the phone and only looks for one phrase;
 * nothing is recorded or sent anywhere until it hears it.
 */
class WakeWord(private val context: Context, private val settings: Settings) {
    private var manager: PorcupineManager? = null

    /** Starts listening. Returns null on success, or a plain-words reason it could not. */
    fun start(onWake: () -> Unit): String? {
        release()
        if (settings.picovoiceKey.isBlank()) return "Picovoice AccessKey nahi daali"
        return try {
            val builder = PorcupineManager.Builder()
                .setAccessKey(settings.picovoiceKey)
                .setSensitivity(SENSITIVITY)
                .setErrorCallback { VoiceLog.add(context, "Wake word error: ${it.message}") }
            val file = settings.wakeFile(context)
            if (settings.wakeSource == "custom" && file.exists()) {
                builder.setKeywordPath(file.absolutePath)
            } else {
                builder.setKeyword(Porcupine.BuiltInKeyword.JARVIS)
            }
            val created = builder.build(context, PorcupineManagerCallback { onWake() })
            created.start()
            manager = created
            null
        } catch (e: Exception) {
            manager = null
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Lets go of the microphone so something else can use it, without losing the loaded model. */
    fun pause() {
        runCatching { manager?.stop() }
    }

    fun resume() {
        runCatching { manager?.start() }.onFailure { VoiceLog.add(context, "Wake word resume fail: ${it.message}") }
    }

    fun release() {
        runCatching { manager?.stop() }
        runCatching { manager?.delete() }
        manager = null
    }

    private companion object {
        /** 0 to 1. Higher hears the phrase more easily but also mistakes other sounds for it. */
        const val SENSITIVITY = 0.6f
    }
}
