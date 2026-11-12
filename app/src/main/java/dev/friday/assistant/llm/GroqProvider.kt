package dev.friday.assistant.llm

import org.json.JSONArray
import org.json.JSONObject

/** Groq speaks the OpenAI-style chat completions format. */
class GroqProvider(
    private val apiKey: String,
    private val model: String,
    override val name: String = "Groq",
    private val url: String = "https://api.groq.com/openai/v1/chat/completions",
) : LlmProvider {
    override val isConfigured get() = apiKey.isNotBlank()

    override suspend fun chat(
        systemPrompt: String,
        items: List<ChatItem>,
        tools: List<ToolSpec>,
    ): Result<ModelReply> {
        val body = JSONObject().put("model", model).put("messages", messages(systemPrompt, items))
        if (tools.isNotEmpty()) {
            val specs = JSONArray()
            tools.forEach {
                specs.put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.parameters),
                    ),
                )
            }
            body.put("tools", specs)
        }

        return postJson(
            url,
            mapOf("Authorization" to "Bearer $apiKey"),
            body.toString(),
        ).mapCatching { raw ->
            val message = JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            // "content" is JSON null when the model only calls tools, and optString would turn that into "null".
            val text = if (message.isNull("content")) "" else message.getString("content").trim()
            val calls = mutableListOf<ToolCall>()
            val arr = message.optJSONArray("tool_calls")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val fn = c.getJSONObject("function")
                    calls += ToolCall(
                        id = c.optString("id").ifEmpty { "call_$i" },
                        name = fn.getString("name"),
                        args = runCatching { JSONObject(fn.optString("arguments", "{}")) }.getOrDefault(JSONObject()),
                    )
                }
            }
            if (text.isEmpty() && calls.isEmpty()) error("$name returned an empty reply")
            ModelReply(text, calls)
        }
    }

    private fun messages(systemPrompt: String, items: List<ChatItem>): JSONArray {
        val out = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
        var i = 0
        while (i < items.size) {
            when (val item = items[i]) {
                is ChatMessage -> {
                    out.put(JSONObject().put("role", if (item.fromUser) "user" else "assistant").put("content", item.text))
                    i++
                }
                is ToolCall -> {
                    val calls = JSONArray()
                    while (i < items.size && items[i] is ToolCall) {
                        val c = items[i] as ToolCall
                        calls.put(
                            JSONObject().put("id", c.id).put("type", "function").put(
                                "function",
                                JSONObject().put("name", c.name).put("arguments", c.args.toString()),
                            ),
                        )
                        i++
                    }
                    out.put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", calls))
                }
                is ToolResult -> {
                    out.put(JSONObject().put("role", "tool").put("tool_call_id", item.id).put("content", item.output))
                    i++
                }
            }
        }
        return out
    }
}
