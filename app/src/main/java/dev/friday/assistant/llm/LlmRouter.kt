package dev.friday.assistant.llm

import kotlinx.coroutines.delay

/**
 * Failover router: tries models in order and returns the first success. This is how free tiers
 * stay usable - when one model answers 429 (quota) the same question goes to the next.
 *
 * It also runs the tool loop: when a model asks for a tool, [runTool] performs it and, unless the
 * result is already plain enough to show, the result goes back to the same model to put in words.
 */
class LlmRouter(providers: List<LlmProvider>) {
    private val configured = providers.filter { it.isConfigured }

    /**
     * [errors] lists why earlier models failed, for showing on screen. [usedTools] says a tool ran.
     * [direct] says [text] is a tool's own result shown as it is, with no model call after it.
     */
    data class Reply(
        val text: String,
        val provider: String?,
        val errors: List<String>,
        val usedTools: Boolean = false,
        val direct: Boolean = false,
    )

    private data class Done(val text: String, val usedTools: Boolean, val direct: Boolean)

    suspend fun ask(
        systemPrompt: String,
        history: List<ChatMessage>,
        tools: List<ToolSpec>,
        runTool: suspend (ToolCall) -> ToolOutput,
    ): Reply {
        if (configured.isEmpty()) {
            return Reply("API key nahi mili. Settings mein key daalo.", null, emptyList())
        }
        // Models that just failed in a way that will not clear in seconds are skipped, so their
        // failure is not waited on again for every message. If all of them are resting, try them all.
        val candidates = configured.filterNot { ModelHealth.isResting(it.name) }.ifEmpty { configured }
        val errors = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        for ((i, p) in candidates.withIndex()) {
            // Waiting to retry only pays off when there is no other model to move on to.
            val result = converse(p, systemPrompt, history, tools, runTool, retry = i == candidates.lastIndex)
            result.onSuccess {
                ModelHealth.succeeded(p.name)
                return Reply(it.text, p.name, errors, it.usedTools, it.direct)
            }
            val failure = result.exceptionOrNull() ?: Exception("unknown error")
            ModelHealth.failed(p.name, failure)
            failures += failure
            errors += "${p.name}: ${failure.message}"
        }
        // Every model failing to even reach Google is the phone being offline, not five separate problems.
        if (failures.all { it is java.io.IOException }) {
            return Reply(
                "Internet nahi mil raha. Wi-Fi ya data check karke dobara try karo.",
                null,
                listOf("Network: ${failures.first().message}"),
            )
        }
        return Reply("Abhi koi bhi model jawab nahi de raha.", null, errors)
    }

    /** One model, start to finish. The whole tool exchange stays with this one model. */
    private suspend fun converse(
        p: LlmProvider,
        systemPrompt: String,
        history: List<ChatMessage>,
        tools: List<ToolSpec>,
        runTool: suspend (ToolCall) -> ToolOutput,
        retry: Boolean,
    ): Result<Done> {
        val items = history.toMutableList<ChatItem>()
        val toolOutputs = mutableListOf<String>()
        repeat(MAX_TOOL_STEPS) {
            val result = chatWithRetry(p, systemPrompt, items, tools, retry)
            val reply = result.getOrElse { e ->
                // Tools already acted on the phone. Asking another model to start over would
                // run them a second time, so report what was done instead.
                return if (toolOutputs.isNotEmpty()) {
                    Result.success(Done(toolOutputs.joinToString(" "), usedTools = true, direct = false))
                } else {
                    Result.failure(e)
                }
            }
            if (reply.toolCalls.isEmpty()) {
                val text = reply.text.ifBlank { toolOutputs.joinToString(" ") }
                return if (text.isBlank()) {
                    Result.failure(IllegalStateException("empty reply"))
                } else {
                    Result.success(Done(text, usedTools = toolOutputs.isNotEmpty(), direct = false))
                }
            }
            items += reply.toolCalls
            val stepOutputs = mutableListOf<String>()
            var allFinal = true
            for (call in reply.toolCalls) {
                val output = runTool(call)
                toolOutputs += output.text
                stepOutputs += output.text
                allFinal = allFinal && output.final
                items += ToolResult(call.id, call.name, output.text)
            }
            // The tool's own sentence says it all, so skip the second model call: it is both
            // faster and impossible for the model to misreport.
            if (allFinal) return Result.success(Done(stepOutputs.joinToString(" "), usedTools = true, direct = true))
        }
        // The model kept asking for tools; stop and report what happened.
        return Result.success(Done(toolOutputs.joinToString(" "), usedTools = true, direct = false))
    }

    /**
     * A 5xx means the provider itself is overloaded or briefly down, which usually clears in
     * seconds, so the same request can be retried once after a pause. Other failures (bad key,
     * unknown model, rate limit) will not fix themselves.
     */
    private suspend fun chatWithRetry(
        p: LlmProvider,
        systemPrompt: String,
        items: List<ChatItem>,
        tools: List<ToolSpec>,
        retry: Boolean,
    ): Result<ModelReply> {
        var result = p.chat(systemPrompt, items, tools)
        if (!retry) return result
        for (pauseMs in RETRY_PAUSES_MS) {
            val code = (result.exceptionOrNull() as? LlmHttpException)?.code ?: break
            if (code !in 500..599) break
            delay(pauseMs)
            result = p.chat(systemPrompt, items, tools)
        }
        return result
    }

    private companion object {
        val RETRY_PAUSES_MS = listOf(1_000L)

        /** Most tool rounds per question, so a confused model cannot loop forever. */
        const val MAX_TOOL_STEPS = 4
    }
}
