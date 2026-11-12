package dev.friday.assistant.llm

import org.json.JSONArray
import org.json.JSONObject

class GeminiProvider(
    private val apiKey: String,
    private val model: String,
) : LlmProvider {
    override val name = "Gemini $model"
    override val isConfigured get() = apiKey.isNotBlank()

    override suspend fun chat(
        systemPrompt: String,
        items: List<ChatItem>,
        tools: List<ToolSpec>,
    ): Result<ModelReply> {
        // Switching "thinking" off makes replies much faster. Some models refuse that setting,
        // so they are asked again the normal way, and the app remembers not to try it on them.
        val thinkingOff = !ModelHealth.rejectsThinkingOff(model)
        val first = request(systemPrompt, items, tools, thinkingOff)
        val failure = first.exceptionOrNull()
        if (thinkingOff && failure is LlmHttpException && failure.code == 400) {
            ModelHealth.markRejectsThinkingOff(model)
            return request(systemPrompt, items, tools, thinkingOff = false)
        }
        return first
    }

    private suspend fun request(
        systemPrompt: String,
        items: List<ChatItem>,
        tools: List<ToolSpec>,
        thinkingOff: Boolean,
    ): Result<ModelReply> {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(textPart(systemPrompt))))
            .put("contents", contents(items))
        if (tools.isNotEmpty()) {
            val declarations = JSONArray()
            tools.forEach {
                declarations.put(
                    JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.parameters),
                )
            }
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
        }

        if (thinkingOff) {
            body.put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        return postJson(url, mapOf("x-goog-api-key" to apiKey), body.toString()).mapCatching { raw ->
            val parts = JSONObject(raw)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val text = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val fc = part.optJSONObject("functionCall")
                if (fc != null) {
                    calls += ToolCall(
                        id = fc.optString("id").ifEmpty { "call_$i" },
                        name = fc.getString("name"),
                        args = fc.optJSONObject("args") ?: JSONObject(),
                        // Gemini 3 attaches a signature to the part; it must be sent back unchanged.
                        raw = part.toString(),
                    )
                } else if (!part.optBoolean("thought", false)) {
                    text.append(part.optString("text"))
                }
            }
            if (text.isBlank() && calls.isEmpty()) error("Gemini returned an empty reply")
            ModelReply(text.toString().trim(), calls)
        }
    }

    /**
     * Gemini wants calls and results grouped: all calls of one step in a single "model"
     * turn, all their results in a single "user" turn.
     */
    private fun contents(items: List<ChatItem>): JSONArray {
        val out = JSONArray()
        var i = 0
        while (i < items.size) {
            val item = items[i]
            val parts = JSONArray()
            val role: String
            when (item) {
                is ChatMessage -> {
                    role = if (item.fromUser) "user" else "model"
                    parts.put(textPart(item.text)); i++
                }
                is ToolCall -> {
                    role = "model"
                    while (i < items.size && items[i] is ToolCall) {
                        val c = items[i] as ToolCall
                        parts.put(
                            c.raw?.let { JSONObject(it) }
                                ?: JSONObject().put("functionCall", JSONObject().put("name", c.name).put("args", c.args)),
                        )
                        i++
                    }
                }
                is ToolResult -> {
                    role = "user"
                    while (i < items.size && items[i] is ToolResult) {
                        val r = items[i] as ToolResult
                        parts.put(
                            JSONObject().put(
                                "functionResponse",
                                JSONObject().put("name", r.name).put("response", JSONObject().put("result", r.output)),
                            ),
                        )
                        i++
                    }
                }
            }
            out.put(JSONObject().put("role", role).put("parts", parts))
        }
        return out
    }

    private fun textPart(text: String) = JSONObject().put("text", text)
}
