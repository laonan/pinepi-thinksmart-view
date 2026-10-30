package com.pinepi.thinksmart.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityWakeGateTest {
    @Test
    fun initialNearAndRepeatedNearDoNotWake() {
        val gate = ProximityWakeGate()
        assertFalse(gate.shouldWake(0f, 5f, false, 0))
        assertFalse(gate.shouldWake(0f, 5f, false, 100))
        assertFalse(gate.shouldWake(5f, 5f, false, 200))
        assertTrue(gate.shouldWake(0f, 5f, false, 300))
        assertFalse(gate.shouldWake(0f, 5f, false, 3000))
    }

    @Test
    fun movingAwayRearmsTheNextApproach() {
        val gate = ProximityWakeGate()
        assertFalse(gate.shouldWake(5f, 5f, false, 0))
        assertTrue(gate.shouldWake(0f, 5f, false, 100))
        assertFalse(gate.shouldWake(5f, 5f, false, 1500))
        assertTrue(gate.shouldWake(0f, 5f, false, 1600))
    }

    @Test
    fun approachWhileScreenIsOnCannotWakeItLaterUnderPersistentCover() {
        val gate = ProximityWakeGate()
        assertFalse(gate.shouldWake(5f, 5f, true, 0))
        assertFalse(gate.shouldWake(0f, 5f, true, 100))
        assertFalse(gate.shouldWake(0f, 5f, false, 2000))
        assertFalse(gate.shouldWake(5f, 5f, false, 2100))
        assertTrue(gate.shouldWake(0f, 5f, false, 2200))
    }

    @Test
    fun cooldownRejectsFastEdgesWithoutQueuingALaterWake() {
        val gate = ProximityWakeGate()
        gate.shouldWake(5f, 5f, false, 0)
        assertTrue(gate.shouldWake(0f, 5f, false, 100))
        gate.shouldWake(5f, 5f, false, 200)
        assertFalse(gate.shouldWake(0f, 5f, false, 300))
        assertFalse(gate.shouldWake(0f, 5f, false, 1700))
        gate.shouldWake(5f, 5f, false, 1800)
        assertTrue(gate.shouldWake(0f, 5f, false, 1900))
    }

    @Test
    fun invalidSamplesAndRangesDoNotCreateOrPreserveArming() {
        val invalid = listOf(
            Float.NaN to 5f, Float.POSITIVE_INFINITY to 5f,
            Float.NEGATIVE_INFINITY to 5f, -1f to 5f, 6f to 5f,
            0f to Float.NaN, 0f to Float.POSITIVE_INFINITY,
            0f to Float.NEGATIVE_INFINITY, 0f to 0f, 0f to -1f,
        )
        for ((value, range) in invalid) {
            val gate = ProximityWakeGate()
            assertFalse(gate.shouldWake(value, range, false, 0))
            assertFalse(gate.shouldWake(0f, 5f, false, 100))
            gate.shouldWake(5f, 5f, false, 200)
            assertFalse(gate.shouldWake(value, range, false, 300))
            assertFalse(gate.shouldWake(0f, 5f, false, 400))
            gate.shouldWake(5f, 5f, false, 500)
            assertTrue(gate.shouldWake(0f, 5f, false, 600))
        }
    }

    @Test
    fun smallRangeUsesItsMaximumAsFarAndLargeRangeUsesFive() {
        val small = ProximityWakeGate()
        assertFalse(small.shouldWake(1f, 1f, false, 0))
        assertTrue(small.shouldWake(0.9f, 1f, false, 100))
        val large = ProximityWakeGate()
        assertFalse(large.shouldWake(5f, 10f, false, 0))
        assertTrue(large.shouldWake(4.9f, 10f, false, 100))
    }

    @Test
    fun clockMovingBackwardDoesNotBypassCooldown() {
        val gate = ProximityWakeGate()
        gate.shouldWake(5f, 5f, false, 1000)
        assertTrue(gate.shouldWake(0f, 5f, false, 1100))
        gate.shouldWake(5f, 5f, false, 1200)
        assertFalse(gate.shouldWake(0f, 5f, false, 1000))
    }
}
