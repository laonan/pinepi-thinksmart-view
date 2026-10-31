package com.pinepi.thinksmart.launcher

/** Display model shared by legacy records and the v2 message protocol. */
data class HomeMessage(
    val id: String,
    val kind: Kind,
    val source: String,
    val receivedAt: Long,
    val title: String = "",
    val text: String = "",
    val mediaUrl: String = "",
    val version: Int = 1,
    val createdAt: String = "",
    val textMode: TextMode = TextMode.LEGACY,
    val speechUrl: String = "",
    val mediaMime: String = "",
) {
    enum class Kind { TEXT, IMAGE, VIDEO, AUDIO }
    enum class TextMode { LEGACY, TTS, PRIVATE }
    fun isValid(): Boolean = id.matches(Regex("[A-Za-z0-9_-]{1,80}")) &&
        source.length <= 100 && receivedAt >= 0 && title.length <= 200 && text.length <= 8192 &&
        text.toByteArray(Charsets.UTF_8).size <= 32 * 1024 &&
        (version == 1 || version == 2) &&
        (version != 2 || (source.isNotBlank() && validCreatedAt(createdAt))) &&
        (version != 1 || (textMode == TextMode.LEGACY && speechUrl.isEmpty())) &&
        when (kind) {
            Kind.TEXT -> text.isNotBlank() && mediaUrl.isEmpty() && mediaMime.isEmpty() &&
                when (textMode) {
                    TextMode.LEGACY -> version == 1 && speechUrl.isEmpty()
                    TextMode.PRIVATE -> version == 2 && speechUrl.isEmpty()
                    TextMode.TTS -> version == 2 && HomeMediaUrl.allowed(speechUrl)
                }
            Kind.IMAGE, Kind.VIDEO, Kind.AUDIO -> text.isEmpty() && speechUrl.isEmpty() &&
                textMode == TextMode.LEGACY && HomeMediaUrl.allowed(mediaUrl) &&
                (version == 1 || mediaMime in when (kind) {
                    Kind.IMAGE -> setOf("image/jpeg", "image/png")
                    Kind.VIDEO -> setOf("video/mp4")
                    else -> setOf("audio/mpeg")
                })
        }

    companion object {
        fun validCreatedAt(value: String): Boolean = try {
            value.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?(?:Z|[+-]\\d{2}:\\d{2})")) &&
                java.time.OffsetDateTime.parse(value).year >= 1970
        } catch (_: Exception) { false }
    }
}

object HomeMediaUrl {
    fun allowed(value: String): Boolean {
        if (value.length !in 1..4096) return false
        return try {
            val uri = java.net.URI(value)
            uri.rawUserInfo == null && uri.host != null && uri.fragment == null && uri.port != 0 && uri.port <= 65535 &&
                (uri.scheme == "https" || (uri.scheme == "http" && LocalHubAddress.isPrivateIpv4(uri.host)))
        } catch (_: Exception) { false }
    }
}

/** Arrival order determines newest; a wrong device clock must not reorder messages. */
class MessageHistory {
    private val entries = mutableListOf<HomeMessage>()
    val messages: List<HomeMessage> get() = entries.toList()
    fun add(message: HomeMessage): Boolean {
        if (!message.isValid() || entries.any { it.id == message.id }) return false
        entries.add(0, message)
        while (entries.size > 10) entries.removeAt(entries.lastIndex)
        return true
    }
    fun clear() { entries.clear() }
}
