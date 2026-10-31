package com.pinepi.thinksmart.launcher

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Private bounded display history. No credentials, audio or chat context. Main-thread owner. */
class HomeMessageStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "home-messages.json"))
    private val history = MessageHistory()
    private val seen = linkedSetOf<String>()
    var onChanged: (() -> Unit)? = null
    val messages get() = history.messages
    var saved = true
        private set

    init {
        try {
            file.openRead().use { input ->
                val bytes = input.readBytesBounded(512 * 1024)
                val raw = bytes.toString(Charsets.UTF_8)
                val state = if (raw.trimStart().startsWith("{")) JSONObject(raw) else null
                val rows = state?.getJSONArray("messages") ?: JSONArray(raw)
                state?.optJSONArray("seen")?.let { ids ->
                    require(ids.length() <= 256)
                    for (i in 0 until ids.length()) {
                        val id = ids.getString(i)
                        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
                        seen.add(id)
                    }
                }
                require(rows.length() <= 10)
                for (i in rows.length() - 1 downTo 0) {
                    val row = rows.getJSONObject(i)
                    history.add(HomeMessage(row.getString("id"), HomeMessage.Kind.valueOf(row.getString("kind")),
                        row.getString("source"), row.getLong("receivedAt"), row.optString("title"),
                        row.optString("text"), row.optString("mediaUrl"), row.optInt("version", 1),
                        row.optString("createdAt"), HomeMessage.TextMode.valueOf(row.optString("textMode", "LEGACY")),
                        row.optString("speechUrl"), row.optString("mediaMime")))
                }
                history.messages.forEach { seen.add(it.id) }
                while (seen.size > 256) seen.remove(seen.first())
            }
        } catch (_: Exception) { history.clear(); seen.clear() }
    }

    fun contains(id: String): Boolean = id in seen || history.messages.any { it.id == id }
    fun add(message: HomeMessage): Boolean {
        if (contains(message.id)) return false
        if (!history.add(message)) return false
        seen.add(message.id)
        while (seen.size > 256) seen.remove(seen.first())
        persist()
        onChanged?.invoke()
        return true
    }
    fun clear(): Boolean {
        history.clear()
        persist()
        onChanged?.invoke()
        return saved
    }
    fun flush(): Boolean { persist(); return saved }

    private fun persist() {
        val rows = JSONArray()
        history.messages.forEach { message ->
            rows.put(JSONObject().put("id", message.id).put("kind", message.kind.name)
                .put("source", message.source).put("receivedAt", message.receivedAt)
                .put("title", message.title).put("text", message.text).put("mediaUrl", message.mediaUrl)
                .put("version", message.version).put("createdAt", message.createdAt).put("textMode", message.textMode.name)
                .put("speechUrl", message.speechUrl).put("mediaMime", message.mediaMime))
        }
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(JSONObject().put("messages", rows).put("seen", JSONArray(seen.toList())).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            saved = true
        } catch (_: Exception) {
            if (output != null) file.failWrite(output)
            saved = false
        }
    }
}

fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val count = read(chunk)
        if (count < 0) break
        require(buffer.size() + count <= limit)
        buffer.write(chunk, 0, count)
    }
    return buffer.toByteArray()
}
