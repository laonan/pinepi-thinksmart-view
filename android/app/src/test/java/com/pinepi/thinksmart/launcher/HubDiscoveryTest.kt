package com.pinepi.thinksmart.launcher

import org.junit.Assert.*
import org.junit.Test

class HubDiscoveryTest {
    private val id = "22532e9a-a842-4f44-b4c2-60a271b71c84"
    private fun hub(host: String = "192.168.2.109", uuid: String = id, port: Int = 8766,
                    version: String = "1", path: String = "/_test/device", auth: String = "none") =
        DiscoveredHub.validated(uuid, "PinePi", host, port, version, path, auth)

    @Test fun changingIpKeepsRememberedIdentityButNotEndpoint() {
        val old = hub()!!
        val migrated = hub("192.168.2.110")!!
        assertEquals(old.id, migrated.id)
        assertNotEquals(old.url, migrated.url)
        assertEquals("ws://192.168.2.110:8766/_test/device", migrated.url)
    }
    @Test fun discoveryNeverConnectsBeforeConsentOrSwitchesToAnotherHub() {
        val known = hub()!!
        val other = hub(uuid = "32532e9a-a842-4f44-b4c2-60a271b71c84")!!
        assertTrue(HubConsent.eligible(listOf(known, other), null).isEmpty())
        assertEquals(listOf(known), HubConsent.eligible(listOf(known, other), id))
        assertTrue(HubConsent.eligible(listOf(other), id).isEmpty())
        assertEquals(listOf(hub("192.168.2.110")!!),
            HubConsent.eligible(listOf(hub("192.168.2.110")!!, other), id))
    }
    @Test fun malformedOrIncompatibleAdvertisementsAreRejected() {
        assertNull(hub(uuid = "1-1-1-1-1"))
        assertNull(hub(host = "8.8.8.8"))
        assertNull(hub(port = 0))
        assertNull(hub(version = "2"))
        assertNull(hub(path = "/other"))
        assertNull(hub(auth = "token"))
    }
}
