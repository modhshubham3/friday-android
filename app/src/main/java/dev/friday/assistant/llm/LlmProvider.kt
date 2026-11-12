package dev.friday.assistant.llm

/**
 * One LLM backend (Gemini, Groq, later Claude/GPT).
 * The rest of the app only knows this interface, so adding a provider
 * never touches the UI or the router.
 */
interface LlmProvider {
    val name: String

    /** False when no API key is set, so the router can skip it. */
    val isConfigured: Boolean

    /** Never throws: failures (429, timeout, bad key) come back as Result.failure. */
    suspend fun chat(systemPrompt: String, userText: String): Result<String>
}

class LlmHttpException(val code: Int, body: String) :
    Exception("HTTP $code: ${body.take(300)}")
