package dev.jarvis.assistant.llm

/**
 * Failover router: tries providers in order and returns the first success.
 * This is how free tiers stay usable - when Gemini answers 429 (rate limit),
 * the same question goes to Groq.
 */
class LlmRouter(providers: List<LlmProvider>) {
    private val active = providers.filter { it.isConfigured }

    /** [errors] lists why each earlier provider failed, for showing on screen. */
    data class Reply(val text: String, val provider: String?, val errors: List<String>)

    suspend fun ask(systemPrompt: String, userText: String): Reply {
        if (active.isEmpty()) {
            return Reply("API key nahi mili. Settings mein key daalo.", null, emptyList())
        }
        val errors = mutableListOf<String>()
        for (p in active) {
            val result = p.chat(systemPrompt, userText)
            result.onSuccess { return Reply(it, p.name, errors) }
            errors += "${p.name}: ${result.exceptionOrNull()?.message ?: "unknown error"}"
        }
        return Reply("Abhi koi bhi model jawab nahi de raha.", null, errors)
    }
}
