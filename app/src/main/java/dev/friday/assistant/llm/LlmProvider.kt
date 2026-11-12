package dev.friday.assistant.llm

/**
 * One LLM backend (Gemini, Groq, later Claude/GPT).
 * The rest of the app only knows this interface, so adding a provider
 * never touches the UI or the router.
 */
/** One turn of the conversation. [fromUser] false means Friday said it. */
data class ChatMessage(val fromUser: Boolean, val text: String)

interface LlmProvider {
    val name: String

    /** False when no API key is set, so the router can skip it. */
    val isConfigured: Boolean

    /** Never throws: failures (429, timeout, bad key) come back as Result.failure. */
    /**
     * [history] is the whole conversation so far, oldest first, ending with the
     * newest user message. The model is stateless: it only "remembers" what is sent here.
     */
    suspend fun chat(systemPrompt: String, history: List<ChatMessage>): Result<String>
}

class LlmHttpException(val code: Int, body: String) :
    Exception("HTTP $code: ${body.take(300)}")
