package dev.friday.assistant

import android.content.Context
import dev.friday.assistant.llm.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saved conversations, one small JSON file per chat in the app's private storage.
 * Private storage needs no permission, other apps cannot read it, and it is removed
 * when the app is uninstalled.
 */
class ChatStore(context: Context) {
    private val dir = File(context.filesDir, "chats").apply { mkdirs() }

    data class Summary(val id: String, val title: String, val updatedAt: Long)

    fun newId(): String = System.currentTimeMillis().toString()

    fun save(id: String, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val json = JSONObject()
            .put("updatedAt", System.currentTimeMillis())
            .put("messages", JSONArray().apply {
                messages.forEach { put(JSONObject().put("user", it.fromUser).put("text", it.text)) }
            })
        // Write to a temp file and rename, so a crash mid-write cannot leave half a chat.
        val tmp = File(dir, "$id.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file(id))
    }

    fun load(id: String): List<ChatMessage> = runCatching {
        val arr = JSONObject(file(id).readText()).getJSONArray("messages")
        List(arr.length()) { i ->
            arr.getJSONObject(i).let { ChatMessage(it.getBoolean("user"), it.getString("text")) }
        }
    }.getOrDefault(emptyList())

    /** Newest first. The title is the first thing the user said in that chat. */
    fun list(): List<Summary> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f ->
            runCatching {
                val json = JSONObject(f.readText())
                val arr = json.getJSONArray("messages")
                val first = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { it.getBoolean("user") }?.getString("text") ?: "Chat"
                Summary(f.name.removeSuffix(".json"), first.take(60), json.optLong("updatedAt", f.lastModified()))
            }.getOrNull() // one unreadable file must not hide the rest
        }
        .sortedByDescending { it.updatedAt }

    fun delete(id: String) {
        file(id).delete()
    }

    private fun file(id: String) = File(dir, "$id.json")
}
