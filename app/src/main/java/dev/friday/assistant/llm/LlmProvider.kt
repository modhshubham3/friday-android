package dev.friday.assistant.llm

import org.json.JSONObject

/** One thing in a conversation: a text turn, a tool the model asked for, or that tool's result. */
sealed interface ChatItem

/** A text turn. [fromUser] false means Friday said it. */
data class ChatMessage(val fromUser: Boolean, val text: String) : ChatItem

/** The model asking the app to run a tool. [raw] is the provider's own JSON for it, replayed verbatim. */
data class ToolCall(val id: String, val name: String, val args: JSONObject, val raw: String? = null) : ChatItem

/** What the tool did, sent back so the model can tell the user. */
data class ToolResult(val id: String, val name: String, val output: String) : ChatItem

/** A tool the model may ask for. [parameters] is a JSON Schema object. */
class ToolSpec(val name: String, val description: String, val parameters: JSONObject)

/** What one model call returned: some text, some tool calls, or both. */
data class ModelReply(val text: String, val toolCalls: List<ToolCall>)

interface LlmProvider {
    val name: String

    /** False when no API key is set, so the router can skip it. */
    val isConfigured: Boolean

    /**
     * [items] is the whole conversation so far, oldest first. The model is stateless:
     * it only "remembers" what is sent here. Never throws: failures (429, timeout,
     * bad key) come back as Result.failure.
     */
    suspend fun chat(systemPrompt: String, items: List<ChatItem>, tools: List<ToolSpec>): Result<ModelReply>
}

class LlmHttpException(val code: Int, body: String) : Exception("HTTP $code: ${summarize(body)}") {
    private companion object {
        /** Providers answer with a long JSON error; the first line of its message is what matters. */
        fun summarize(body: String): String =
            runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
                ?.lineSequence()?.firstOrNull()?.take(110)
                ?: body.take(160)
    }
}
