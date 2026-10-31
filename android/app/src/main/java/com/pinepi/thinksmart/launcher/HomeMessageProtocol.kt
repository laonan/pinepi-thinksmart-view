package com.pinepi.thinksmart.launcher

import org.json.JSONObject

/** Strict v2 decoder. No coercion, implicit TTS, or network/provider side effects. */
object HomeMessageProtocol {
    fun decode(json: JSONObject, now: Long = System.currentTimeMillis()): HomeMessage? = try {
        require(json.opt("type") == "home.message" && json.opt("version") == 2)
        fun string(key: String) = json.get(key) as? String ?: error("invalid string")
        val kind = HomeMessage.Kind.valueOf(string("kind"))
        var allowed = setOf("type", "version", "id", "source", "created_at", "kind", "title")
        var text = ""
        var mode = HomeMessage.TextMode.LEGACY
        var mediaUrl = ""
        var mediaMime = ""
        var speechUrl = ""
        fun asset(key: String, mimes: Set<String>): Pair<String, String> {
            val asset = json.getJSONObject(key)
            require(asset.keys().asSequence().toSet() == setOf("url", "mime_type"))
            val url = asset.get("url") as? String ?: error("invalid url")
            val mime = asset.get("mime_type") as? String ?: error("invalid mime")
            require(HomeMediaUrl.allowed(url) && mime in mimes)
            return url to mime
        }
        if (kind == HomeMessage.Kind.TEXT) {
            allowed = allowed + setOf("text", "text_mode", "speech")
            text = string("text")
            mode = HomeMessage.TextMode.valueOf(string("text_mode"))
            require(mode != HomeMessage.TextMode.LEGACY)
            if (mode == HomeMessage.TextMode.PRIVATE) require(!json.has("speech"))
            else speechUrl = asset("speech", setOf("audio/mpeg")).first
        } else {
            allowed = allowed + "media"
            val (url, mime) = asset("media", when (kind) {
                HomeMessage.Kind.IMAGE -> setOf("image/jpeg", "image/png")
                HomeMessage.Kind.VIDEO -> setOf("video/mp4")
                else -> setOf("audio/mpeg")
            })
            mediaUrl = url
            mediaMime = mime
        }
        require(json.keys().asSequence().all { it in allowed })
        val timestamp = string("created_at")
        require(HomeMessage.validCreatedAt(timestamp))
        HomeMessage(string("id"), kind, string("source"), now,
            if (json.has("title")) string("title") else "", text, mediaUrl,
            2, timestamp, mode, speechUrl, mediaMime).also { require(it.isValid()) }
    } catch (_: Exception) { null }
}
