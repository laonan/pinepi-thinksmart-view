package com.pinepi.thinksmart.launcher

import org.junit.Assert.*
import org.junit.Test

class MessageHistoryTest {
    private fun message(id: String, time: Long = 100) = HomeMessage(id, HomeMessage.Kind.TEXT, "Hub", time, text = "message $id")

    @Test fun newestTenUseArrivalOrderAndDuplicatesDoNotBumpHistory() {
        val history = MessageHistory()
        for (i in 0..11) assertTrue(history.add(message("id_$i", 1000L - i)))
        assertEquals((11 downTo 2).map { "id_$it" }, history.messages.map { it.id })
        assertFalse(history.add(message("id_4")))
        assertEquals("id_11", history.messages.first().id)
    }
    @Test fun mediaTypesCannotAlsoCarryText() {
        val history = MessageHistory()
        assertFalse(history.add(HomeMessage("mixed", HomeMessage.Kind.IMAGE, "Hub", 1, text = "text", mediaUrl = "https://example.com/a.png")))
        assertTrue(history.add(HomeMessage("picture", HomeMessage.Kind.IMAGE, "Hub", 1, mediaUrl = "https://example.com/a.png")))
        assertTrue(history.add(HomeMessage("video", HomeMessage.Kind.VIDEO, "Hub", 1, mediaUrl = "http://192.168.2.109/a.mp4")))
    }
    @Test fun clearRemovesAllAndNewArrivalsStillWork() {
        val history = MessageHistory()
        history.add(message("before")); history.clear()
        assertTrue(history.messages.isEmpty())
        assertTrue(history.add(message("after")))
        assertEquals(listOf("after"), history.messages.map { it.id })
    }
    @Test fun malformedAndOversizedMessagesNeverEnterHistory() {
        val history = MessageHistory()
        assertFalse(history.add(message("bad id")))
        assertFalse(history.add(message("empty").copy(text = "")))
        assertFalse(history.add(message("large").copy(text = "x".repeat(8193))))
        assertTrue(history.messages.isEmpty())
    }
    @Test fun mediaAcceptsPrivateHttpOrHttpsWithoutCredentials() {
        assertTrue(HomeMediaUrl.allowed("https://example.com/image.png?version=1"))
        assertTrue(HomeMediaUrl.allowed("http://192.168.1.50:8766/a.png"))
        for (url in listOf("file:///sdcard/a", "content://anything", "http://example.com/a", "https://user:secret@example.com/a", "http://127.0.0.1/a", "https://example.com/a#fragment", "javascript:alert(1)")) {
            assertFalse(url, HomeMediaUrl.allowed(url))
        }
    }
}
