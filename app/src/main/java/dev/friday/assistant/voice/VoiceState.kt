package dev.friday.assistant.voice

import kotlinx.coroutines.flow.MutableStateFlow

/** What Friday is doing right now. The screen shows it; the voice service and the chat screen both set it. */
enum class Mode {
    /** Nothing going on. */
    IDLE,

    /** The wake word was just heard. */
    WAKE,

    /** Listening to the user; [VoiceState.level] follows the voice. */
    LISTENING,

    /** Waiting for the models. */
    THINKING,

    /** Speaking an answer. */
    SPEAKING,
}

object VoiceState {
    val mode = MutableStateFlow(Mode.IDLE)

    /** How loud the user is, 0 to 1, while listening. */
    val level = MutableStateFlow(0f)

    /** Speech engines report loudness in decibels, roughly -2 (silence) to 10 (loud). */
    fun hear(rmsDb: Float) {
        level.value = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
    }
}
