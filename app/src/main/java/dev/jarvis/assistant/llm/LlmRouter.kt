package dev.jarvis.assistant.llm

import android.util.Log

/**
 * Failover router: tries providers in order and returns the first success.
 * This is how free tiers stay usable - when Gemini answers 429 (rate limit),
 * the same question goes to Groq.
 */
class LlmRouter(providers: List<LlmProvider>) {
    private val active = providers.filter { it.isConfigured }

    data class Reply(val text: String, val provider: String?)

    suspend fun ask(systemPrompt: String, userText: String): Reply {
        if (active.isEmpty()) {
            return Reply("API key nahi mili. local.properties mein key daalo aur app rebuild karo.", null)
        }
        for (p in active) {
            val result = p.chat(systemPrompt, userText)
            result.onSuccess { return Reply(it, p.name) }
            Log.w("LlmRouter", "${p.name} failed, trying next", result.exceptionOrNull())
        }
        return Reply("Abhi koi bhi model jawab nahi de raha. Thodi der baad try karo.", null)
    }
}
