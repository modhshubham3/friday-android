package dev.friday.assistant.llm

import org.json.JSONArray
import org.json.JSONObject

/** Groq speaks the OpenAI-style chat completions format. */
class GroqProvider(
    private val apiKey: String,
    private val model: String,
) : LlmProvider {
    override val name = "Groq"
    override val isConfigured get() = apiKey.isNotBlank()

    override suspend fun chat(systemPrompt: String, userText: String): Result<String> {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userText))
        val body = JSONObject().put("model", model).put("messages", messages).toString()

        return postJson(
            "https://api.groq.com/openai/v1/chat/completions",
            mapOf("Authorization" to "Bearer $apiKey"),
            body,
        ).mapCatching { raw ->
            JSONObject(raw)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
                .trim().ifEmpty { error("Groq returned an empty reply") }
        }
    }
}
