package dev.friday.assistant.llm

import org.json.JSONArray
import org.json.JSONObject

class GeminiProvider(
    private val apiKey: String,
    private val model: String,
) : LlmProvider {
    override val name = "Gemini $model"
    override val isConfigured get() = apiKey.isNotBlank()

    override suspend fun chat(systemPrompt: String, userText: String): Result<String> {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", parts(systemPrompt)))
            .put(
                "contents",
                JSONArray().put(JSONObject().put("role", "user").put("parts", parts(userText))),
            )
            .toString()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        return postJson(url, mapOf("x-goog-api-key" to apiKey), body).mapCatching { raw ->
            val partsArr = JSONObject(raw)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            buildString {
                for (i in 0 until partsArr.length()) append(partsArr.getJSONObject(i).optString("text"))
            }.trim().ifEmpty { error("Gemini returned an empty reply") }
        }
    }

    private fun parts(text: String) = JSONArray().put(JSONObject().put("text", text))
}
