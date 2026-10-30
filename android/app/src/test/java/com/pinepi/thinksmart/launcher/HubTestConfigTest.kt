package com.pinepi.thinksmart.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubTestConfigTest {
    @Test fun cleartextAddressesAreRestrictedToPrivateIpv4() {
        for (host in listOf("10.0.0.1", "172.16.1.2", "172.31.255.254", "192.168.2.109")) {
            assertTrue(host, LocalHubAddress.isPrivateIpv4(host))
        }
        for (host in listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "127.0.0.1", "0.0.0.0", "example.com",
            "192.168.2.256", "192.168.2", "192.168.2.-1", "192.168.2.1.evil", "192.168.2.１", "")) {
            assertFalse(host, LocalHubAddress.isPrivateIpv4(host))
        }
    }

    @Test fun duplicateMessagesAreConsumedOnceAndMemoryIsBounded() {
        val ids = RecentMessageIds(2)
        assertTrue(ids.accept("a"))
        assertFalse(ids.accept("a"))
        assertTrue(ids.accept("b"))
        assertTrue(ids.accept("c"))
        assertFalse(ids.accept("b"))
        assertTrue(ids.accept("a"))
    }

    @Test fun invalidIdsCannotTriggerANewMessage() {
        val ids = RecentMessageIds()
        assertFalse(ids.accept(""))
        assertFalse(ids.accept("x".repeat(81)))
        assertFalse(ids.accept("id\ntext"))
        assertTrue(ids.accept("id_123-456"))
    }
}
