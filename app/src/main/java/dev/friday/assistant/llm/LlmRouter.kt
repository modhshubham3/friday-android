package dev.friday.assistant.llm

import kotlinx.coroutines.delay

/**
 * Failover router: tries providers in order and returns the first success.
 * This is how free tiers stay usable - when Gemini answers 429 (rate limit),
 * the same question goes to Groq.
 */
class LlmRouter(providers: List<LlmProvider>) {
    private val active = providers.filter { it.isConfigured }

    /** [errors] lists why each earlier provider failed, for showing on screen. */
    data class Reply(val text: String, val provider: String?, val errors: List<String>)

    suspend fun ask(systemPrompt: String, history: List<ChatMessage>): Reply {
        if (active.isEmpty()) {
            return Reply("API key nahi mili. Settings mein key daalo.", null, emptyList())
        }
        val errors = mutableListOf<String>()
        for (p in active) {
            val result = chatWithRetry(p, systemPrompt, history)
            result.onSuccess { return Reply(it, p.name, errors) }
            errors += "${p.name}: ${result.exceptionOrNull()?.message ?: "unknown error"}"
        }
        return Reply("Abhi koi bhi model jawab nahi de raha.", null, errors)
    }

    /**
     * A 5xx means the provider itself is overloaded or briefly down, which usually
     * clears in seconds, so the same request is retried once after a pause.
     * Other failures (bad key, unknown model, rate limit) will not fix themselves,
     * so they fall through to the next provider at once.
     */
    private suspend fun chatWithRetry(
        p: LlmProvider,
        systemPrompt: String,
        history: List<ChatMessage>,
    ): Result<String> {
        var result = p.chat(systemPrompt, history)
        for (pauseMs in RETRY_PAUSES_MS) {
            val code = (result.exceptionOrNull() as? LlmHttpException)?.code ?: break
            if (code !in 500..599) break
            delay(pauseMs)
            result = p.chat(systemPrompt, history)
        }
        return result
    }

    private companion object {
        // One short retry only: with several models in the chain, moving on is faster than waiting.
        val RETRY_PAUSES_MS = listOf(1_000L)
    }
}
