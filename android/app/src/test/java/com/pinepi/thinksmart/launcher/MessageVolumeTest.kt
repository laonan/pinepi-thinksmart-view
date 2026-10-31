package com.pinepi.thinksmart.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageVolumeTest {
    private class Device(var level: Int = 3) {
        val changes = mutableListOf<Int>()
        val volume = MessageVolume({ level }, { 15 }, { level = it; changes += it })
    }

    @Test fun quietMusicIsRaisedAndRestored() {
        val d = Device()
        d.volume.begin(14)
        assertEquals(14, d.level)
        d.volume.finish()
        assertEquals(listOf(14, 3), d.changes)
        d.volume.finish()
        assertEquals(listOf(14, 3), d.changes)
    }

    @Test fun louderMusicIsNeverLowered() {
        val d = Device(15)
        d.volume.begin(8)
        d.volume.finish()
        assertEquals(emptyList<Int>(), d.changes)
    }

    @Test fun manualChangeSurvivesCompletionEvenWithoutPolling() {
        val d = Device()
        d.volume.begin(14)
        d.level = 8
        d.volume.finish()
        assertEquals(8, d.level)
    }

    @Test fun observedManualChangeBackToRaisedLevelStillWins() {
        val d = Device()
        d.volume.begin(14)
        d.level = 8
        d.volume.observe()
        d.level = 14
        d.volume.finish()
        assertEquals(14, d.level)
    }

    @Test fun volumeKeyDuringPlaybackRetainsUsersIntent() {
        val d = Device()
        d.volume.begin(14)
        d.volume.userAdjusted()
        d.volume.finish()
        assertEquals(14, d.level)
    }

    @Test fun replacementRestoresBeforeStartingNewSession() {
        val d = Device()
        d.volume.begin(14)
        d.volume.begin(8)
        assertEquals(8, d.level)
        d.volume.finish()
        assertEquals(listOf(14, 3, 8, 3), d.changes)
    }

    @Test fun absentOrInvalidHardwareRangeNeverRaises() {
        val d = Device(0)
        d.volume.begin(0)
        d.volume.finish()
        assertEquals(emptyList<Int>(), d.changes)
        val unavailable = MessageVolume({ 0 }, { 0 }, { error("Must not raise") })
        unavailable.begin(14)
        unavailable.finish()
        d.volume.begin(99)
        assertEquals(15, d.level)
        d.volume.finish()
        assertEquals(0, d.level)
    }
}
