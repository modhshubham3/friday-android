package dev.friday.assistant.llm

import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * What the app has learned about each model during this run, so a model that just failed
 * is not asked again (and waited on) for every single message.
 */
object ModelHealth {
    private val restingUntil = ConcurrentHashMap<String, Long>()
    private val refusesThinkingOff = ConcurrentHashMap.newKeySet<String>()

    fun isResting(name: String) = (restingUntil[name] ?: 0L) > System.currentTimeMillis()

    fun succeeded(name: String) {
        restingUntil.remove(name)
    }

    /**
     * Rest a model for as long as its failure is likely to last: a used-up quota or a retired
     * model will not fix itself in seconds, an overloaded one often clears within a minute.
     * No network, a bad key or a bad request say nothing about this model, so they rest nothing.
     */
    fun failed(name: String, error: Throwable) {
        val ms = when {
            error is LlmHttpException && error.code == 429 -> 5 * 60_000L
            error is LlmHttpException && error.code == 404 -> 30 * 60_000L
            error is LlmHttpException && error.code in 500..599 -> 60_000L
            error is SocketTimeoutException -> 60_000L
            else -> 0L
        }
        if (ms > 0) restingUntil[name] = System.currentTimeMillis() + ms
    }

    fun rejectsThinkingOff(model: String) = model in refusesThinkingOff
    fun markRejectsThinkingOff(model: String) {
        refusesThinkingOff += model
    }
}
