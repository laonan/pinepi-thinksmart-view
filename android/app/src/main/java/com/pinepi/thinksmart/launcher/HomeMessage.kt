package com.pinepi.thinksmart.launcher

/** Terminal display model; not a frozen Hub wire protocol. */
data class HomeMessage(
    val id: String,
    val kind: Kind,
    val source: String,
    val receivedAt: Long,
    val title: String = "",
    val text: String = "",
    val mediaUrl: String = "",
) {
    enum class Kind { TEXT, IMAGE, VIDEO }
    fun isValid(): Boolean = id.matches(Regex("[A-Za-z0-9_-]{1,80}")) &&
        source.length <= 100 && receivedAt >= 0 && title.length <= 200 && text.length <= 8192 &&
        when (kind) {
            Kind.TEXT -> text.isNotBlank() && mediaUrl.isEmpty()
            Kind.IMAGE, Kind.VIDEO -> text.isEmpty() && title.isEmpty() && HomeMediaUrl.allowed(mediaUrl)
        }
}

object HomeMediaUrl {
    fun allowed(value: String): Boolean {
        if (value.length !in 1..4096) return false
        return try {
            val uri = java.net.URI(value)
            uri.rawUserInfo == null && uri.host != null && uri.fragment == null &&
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
