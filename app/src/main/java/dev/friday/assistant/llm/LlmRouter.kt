package dev.friday.assistant.llm

import kotlinx.coroutines.delay

/**
 * Failover router: tries providers in order and returns the first success.
 * This is how free tiers stay usable - when Gemini answers 429 (rate limit),
 * the same question goes to the next model.
 *
 * It also runs the tool loop: when a model asks for a tool, [runTool] performs it,
 * the result goes back to the same model, and that continues until the model answers in words.
 */
class LlmRouter(providers: List<LlmProvider>) {
    private val active = providers.filter { it.isConfigured }

    /** [errors] lists why each earlier provider failed, for showing on screen. */
    data class Reply(val text: String, val provider: String?, val errors: List<String>)

    suspend fun ask(
        systemPrompt: String,
        history: List<ChatMessage>,
        tools: List<ToolSpec>,
        runTool: suspend (ToolCall) -> String,
    ): Reply {
        if (active.isEmpty()) {
            return Reply("API key nahi mili. Settings mein key daalo.", null, emptyList())
        }
        val errors = mutableListOf<String>()
        for (p in active) {
            val result = converse(p, systemPrompt, history, tools, runTool)
            result.onSuccess { return Reply(it, p.name, errors) }
            errors += "${p.name}: ${result.exceptionOrNull()?.message ?: "unknown error"}"
        }
        return Reply("Abhi koi bhi model jawab nahi de raha.", null, errors)
    }

    /** One provider, start to finish. The whole tool exchange stays with this one model. */
    private suspend fun converse(
        p: LlmProvider,
        systemPrompt: String,
        history: List<ChatMessage>,
        tools: List<ToolSpec>,
        runTool: suspend (ToolCall) -> String,
    ): Result<String> {
        val items = history.toMutableList<ChatItem>()
        val toolOutputs = mutableListOf<String>()
        repeat(MAX_TOOL_STEPS) {
            val result = chatWithRetry(p, systemPrompt, items, tools)
            val reply = result.getOrElse { e ->
                // Tools already acted on the phone. Asking another model to start over would
                // run them a second time, so report what was done instead.
                return if (toolOutputs.isNotEmpty()) Result.success(toolOutputs.joinToString(" ")) else Result.failure(e)
            }
            if (reply.toolCalls.isEmpty()) {
                val text = reply.text.ifBlank { toolOutputs.joinToString(" ") }
                return if (text.isBlank()) Result.failure(IllegalStateException("empty reply")) else Result.success(text)
            }
            items += reply.toolCalls
            for (call in reply.toolCalls) {
                val output = runTool(call)
                toolOutputs += output
                items += ToolResult(call.id, call.name, output)
            }
        }
        // The model kept asking for tools; stop and report what happened.
        return Result.success(toolOutputs.joinToString(" "))
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
        items: List<ChatItem>,
        tools: List<ToolSpec>,
    ): Result<ModelReply> {
        var result = p.chat(systemPrompt, items, tools)
        for (pauseMs in RETRY_PAUSES_MS) {
            val code = (result.exceptionOrNull() as? LlmHttpException)?.code ?: break
            if (code !in 500..599) break
            delay(pauseMs)
            result = p.chat(systemPrompt, items, tools)
        }
        return result
    }

    private companion object {
        // One short retry only: with several models in the chain, moving on is faster than waiting.
        val RETRY_PAUSES_MS = listOf(1_000L)

        /** Most tool rounds per question, so a confused model cannot loop forever. */
        const val MAX_TOOL_STEPS = 4
    }
}
