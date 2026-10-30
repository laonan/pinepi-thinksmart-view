package com.pinepi.thinksmart.launcher

object LocalHubAddress {
    fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } }) return false
        val numbers = parts.map { it.toIntOrNull() ?: return false }
        if (numbers.any { it !in 0..255 }) return false
        return numbers[0] == 10 || (numbers[0] == 192 && numbers[1] == 168) ||
            (numbers[0] == 172 && numbers[1] in 16..31)
    }
}

class RecentMessageIds(private val capacity: Int = 128) {
    private val ids = LinkedHashSet<String>()
    fun accept(id: String): Boolean {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}")) || !ids.add(id)) return false
        if (ids.size > capacity) ids.remove(ids.first())
        return true
    }
}
