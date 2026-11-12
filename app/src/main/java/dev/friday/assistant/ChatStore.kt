package dev.friday.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One saved line of a chat. [kind] is "user", "friday" or "tool".
 * [viaTool] marks the lines of a turn in which Friday used a tool; those are kept for the screen
 * but never sent back to the model as history (see MainActivity.historyForModel).
 */
data class StoredBubble(val kind: String, val text: String, val viaTool: Boolean = false)

/**
 * Saved conversations, one small JSON file per chat in the app's private storage.
 * Private storage needs no permission, other apps cannot read it, and it is removed
 * when the app is uninstalled.
 */
class ChatStore(context: Context) {
    private val dir = File(context.filesDir, "chats").apply { mkdirs() }

    data class Summary(val id: String, val title: String, val updatedAt: Long)

    fun newId(): String = System.currentTimeMillis().toString()

    fun save(id: String, lines: List<StoredBubble>) {
        if (lines.isEmpty()) return
        val json = JSONObject()
            .put("updatedAt", System.currentTimeMillis())
            .put("messages", JSONArray().apply {
                lines.forEach {
                    put(JSONObject().put("kind", it.kind).put("text", it.text).put("viaTool", it.viaTool))
                }
            })
        // Write to a temp file and rename, so a crash mid-write cannot leave half a chat.
        val tmp = File(dir, "$id.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file(id))
    }

    fun load(id: String): List<StoredBubble> = runCatching {
        val arr = JSONObject(file(id).readText()).getJSONArray("messages")
        List(arr.length()) { i -> parse(arr.getJSONObject(i)) }
    }.getOrDefault(emptyList())

    /** Newest first. The title is the first thing the user said in that chat. */
    fun list(): List<Summary> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f ->
            runCatching {
                val json = JSONObject(f.readText())
                val arr = json.getJSONArray("messages")
                val first = (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
                    .firstOrNull { it.kind == "user" }?.text ?: "Chat"
                Summary(f.name.removeSuffix(".json"), first.take(60), json.optLong("updatedAt", f.lastModified()))
            }.getOrNull() // one unreadable file must not hide the rest
        }
        .sortedByDescending { it.updatedAt }

    fun delete(id: String) {
        file(id).delete()
    }

    private fun parse(o: JSONObject): StoredBubble =
        if (o.has("kind")) {
            StoredBubble(o.getString("kind"), o.getString("text"), o.optBoolean("viaTool", false))
        } else {
            // Saved by an older build, which did not record tool use and whose "done" replies
            // may never have run a tool. Showing them is fine; teaching the model from them is not.
            StoredBubble(if (o.getBoolean("user")) "user" else "friday", o.getString("text"), viaTool = true)
        }

    private fun file(id: String) = File(dir, "$id.json")
}
