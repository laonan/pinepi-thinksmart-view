package com.pinepi.thinksmart.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PcmStartGuardTest {
    @Test
    fun removesFullScaleStartupImpulseAndPreservesLaterSignal() {
        val warmup = WavFile.SAMPLE_RATE / 5
        val fade = WavFile.SAMPLE_RATE / 50
        val source = ShortArray(warmup + 1600) { if (it < warmup) 32767 else 1000 }
        val count = PcmStartGuard().process(source, source.size)
        assertEquals(1600, count)
        assertEquals(0, source[0].toInt())
        assertEquals(1000, source[fade - 1].toInt())
        assertTrue(source.take(count).all { it in 0..1000 })
        assertTrue(source.sliceArray(fade until count).all { it == 1000.toShort() })
    }

    @Test
    fun outputIsIndependentOfAudioRecordReadChunkBoundaries() {
        val input = ShortArray(6800) { ((it * 73) % 65536 - 32768).toShort() }
        val expectedBuffer = input.copyOf()
        val expectedCount = PcmStartGuard().process(expectedBuffer, expectedBuffer.size)
        val guard = PcmStartGuard()
        val actual = ArrayList<Short>()
        var position = 0
        for (size in listOf(101, 1600, 800, 999, 700, 1600, 1000)) {
            val end = minOf(position + size, input.size)
            val buffer = input.copyOfRange(position, end)
            val count = guard.process(buffer, buffer.size)
            actual.addAll(buffer.take(count))
            position = end
        }
        assertEquals(input.size, position)
        assertArrayEquals(expectedBuffer.copyOf(expectedCount), actual.toShortArray())
        assertTrue(actual.all { abs(it.toInt()) <= 32768 })
    }

    @Test
    fun cancellationDuringWarmupProducesNoPcm() {
        val guard = PcmStartGuard()
        assertEquals(0, guard.process(ShortArray(1600) { -32768 }, 1600))
        assertEquals(0, guard.process(ShortArray(1600) { 32767 }, 1600))
    }
}
