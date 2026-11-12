package dev.friday.assistant.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Picks a model and runs the conversation turn.
 *
 * The first model call is a race: the first model is asked; if it fails, or has not answered
 * after [HEDGE_MS], the next one is asked as well, and whoever answers first wins. A slow or dead
 * model therefore costs a few seconds, not its whole timeout. Only the winner's reply is acted
 * on, so a tool can never run twice.
 *
 * It also runs the tool loop: when a model asks for a tool, [runTool] performs it and, unless the
 * result is already plain enough to show, the result goes back to the same model to put in words.
 */
class LlmRouter(providers: List<LlmProvider>) {
    private val configured = providers.filter { it.isConfigured }

    /**
     * [errors] lists why models failed, for showing on screen. [usedTools] says a tool ran.
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
        val items = history.toMutableList<ChatItem>()
        val errors = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()

        val (provider, first) = race(candidates, systemPrompt, items, tools, errors, failures)
            ?: return noAnswer(errors, failures)
        ModelHealth.succeeded(provider.name)
        val done = finish(provider, first, systemPrompt, items, tools, runTool)
            ?: return noAnswer(errors, failures)
        return Reply(done.text, provider.name, errors, done.usedTools, done.direct)
    }

    private fun noAnswer(errors: List<String>, failures: List<Throwable>): Reply {
        // Every model failing to even reach Google is the phone being offline, not five separate problems.
        if (failures.isNotEmpty() && failures.all { it is java.io.IOException }) {
            return Reply(
                "Internet nahi mil raha. Wi-Fi ya data check karke dobara try karo.",
                null,
                listOf("Network: ${failures.first().message}"),
            )
        }
        return Reply("Abhi koi bhi model jawab nahi de raha.", null, errors)
    }

    /** First successful reply wins; null if every model failed. */
    private suspend fun race(
        candidates: List<LlmProvider>,
        systemPrompt: String,
        items: List<ChatItem>,
        tools: List<ToolSpec>,
        errors: MutableList<String>,
        failures: MutableList<Throwable>,
    ): Pair<LlmProvider, ModelReply>? {
        val events = Channel<Pair<LlmProvider, Result<ModelReply>>>(Channel.UNLIMITED)
        val jobs = mutableListOf<Job>()
        var next = 0
        var running = 0
        val snapshot = items.toList() // later steps add to items; the racers must all see the same request

        fun launchNext() {
            val p = candidates[next++]
            running++
            jobs += scope.launch {
                val result = try {
                    p.chat(systemPrompt, snapshot, tools)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                events.trySend(p to result) // trySend: the channel may be closed by then, and that is fine
            }
        }

        launchNext()
        try {
            while (running > 0) {
                val event = if (next < candidates.size) {
                    withTimeoutOrNull(HEDGE_MS) { events.receive() }
                } else {
                    events.receive()
                }
                if (event == null) { // too slow: ask the next model as well
                    launchNext()
                    continue
                }
                running--
                val (p, result) = event
                result.getOrNull()?.let { return p to it }
                val failure = result.exceptionOrNull() ?: Exception("unknown error")
                ModelHealth.failed(p.name, failure)
                failures += failure
                errors += "${p.name}: ${failure.message}"
                if (next < candidates.size) launchNext()
            }
            return null
        } finally {
            // The slower ones are no longer needed. They run in their own scope, so nothing here
            // waits for a request that is still stuck on the network.
            jobs.forEach { it.cancel() }
            events.close()
        }
    }

    /** Carries on with the winner: runs its tools and, if needed, lets it put the results into words. */
    private suspend fun finish(
        p: LlmProvider,
        first: ModelReply,
        systemPrompt: String,
        items: MutableList<ChatItem>,
        tools: List<ToolSpec>,
        runTool: suspend (ToolCall) -> ToolOutput,
    ): Done? {
        val toolOutputs = mutableListOf<String>()
        var reply = first
        repeat(MAX_TOOL_STEPS) {
            if (reply.toolCalls.isEmpty()) {
                val text = reply.text.ifBlank { toolOutputs.joinToString(" ") }
                return if (text.isBlank()) null else Done(text, usedTools = toolOutputs.isNotEmpty(), direct = false)
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
            if (allFinal) return Done(stepOutputs.joinToString(" "), usedTools = true, direct = true)

            // Tools already acted on the phone. If the model cannot be reached to explain, report
            // what was done rather than asking another model to start over and run them again.
            reply = chatWithRetry(p, systemPrompt, items, tools).getOrElse {
                return Done(toolOutputs.joinToString(" "), usedTools = true, direct = false)
            }
        }
        // The model kept asking for tools; stop and report what happened.
        return Done(toolOutputs.joinToString(" "), usedTools = true, direct = false)
    }

    /** A 5xx means the provider is briefly overloaded; the same request is retried once after a pause. */
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
        /** How long a model gets before the next one is asked too. */
        const val HEDGE_MS = 3_000L

        val RETRY_PAUSES_MS = listOf(1_000L)

        /** Most tool rounds per question, so a confused model cannot loop forever. */
        const val MAX_TOOL_STEPS = 4

        /** Racing requests live here, apart from the screen, so a slow loser never holds anything up. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
