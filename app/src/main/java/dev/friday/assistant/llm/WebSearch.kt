package dev.friday.assistant.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Asks Gemini with Google Search switched on, so the answer comes from the web today (prices, timings, links) and not from memory.
 * A separate request from the chat one: Gemini does not always allow search and the app's own tools in the same call.
 */
object WebSearch {
    /** Tavily: a search API built for assistants. It answers in a few sentences and lists the pages it used. */
    suspend fun tavily(apiKey: String, question: String): Result<String> {
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("Tavily key nahi hai"))
        val body = JSONObject().put("query", question).put("include_answer", true).put("max_results", 5).put("search_depth", "basic")
        return postJson("https://api.tavily.com/search", mapOf("Authorization" to "Bearer $apiKey"), body.toString(), readTimeoutMs = 25_000).mapCatching { raw ->
            val o = JSONObject(raw)
            val out = StringBuilder(o.optString("answer"))
            val results = o.optJSONArray("results")
            if (results != null) {
                out.append("\nSources:")
                for (i in 0 until minOf(results.length(), 5)) {
                    val r = results.getJSONObject(i)
                    out.append("\n- ").append(r.optString("title")).append(": ").append(r.optString("url"))
                    val snippet = r.optString("content").take(220)
                    if (snippet.isNotBlank()) out.append("\n  ").append(snippet.replace('\n', ' '))
                }
            }
            if (out.isBlank()) error("khali jawab")
            out.toString().trim()
        }
    }

    suspend fun search(apiKey: String, models: List<String>, question: String): Result<String> {
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("Gemini key nahi hai"))
        var last: Throwable = IllegalStateException("koi model nahi")
        for (model in models.take(6)) {
            if (ModelHealth.isResting("search:$model") || ModelHealth.isResting("Gemini $model")) continue
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", question)))))
                .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
            val result = postJson(url, mapOf("x-goog-api-key" to apiKey), body.toString(), readTimeoutMs = 25_000).mapCatching { raw ->
                val candidate = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                val parts = candidate.getJSONObject("content").getJSONArray("parts")
                val text = StringBuilder()
                for (i in 0 until parts.length()) text.append(parts.getJSONObject(i).optString("text"))
                val sources = StringBuilder()
                val chunks = candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
                if (chunks != null) {
                    for (i in 0 until minOf(chunks.length(), 5)) {
                        val web = chunks.getJSONObject(i).optJSONObject("web") ?: continue
                        sources.append("\n- ").append(web.optString("title")).append(": ").append(web.optString("uri"))
                    }
                }
                if (text.isBlank()) error("khali jawab")
                text.toString().trim() + if (sources.isNotEmpty()) "\nSources:$sources" else ""
            }
            if (result.isSuccess) return result
            last = result.exceptionOrNull() ?: last
            ModelHealth.failed("search:$model", last)
        }
        return Result.failure(last)
    }
}
