package com.pinepi.thinksmart.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PcmDiagnosticsTest {
    @Test
    fun startupImpulseDoesNotHideAQuietRecording() {
        val meter = PcmLevelMeter(skipSamples = 3)
        meter.add(shortArrayOf(32767, -32768, 32767, 0, 0, 0), 6)
        assertEquals(-96.0, meter.dbFs, 0.0)
    }

    @Test
    fun measuresHalfScaleSignalInDecibels() {
        val meter = PcmLevelMeter(skipSamples = 0)
        meter.add(shortArrayOf(16384, -16384, 16384, -16384), 4)
        assertEquals(-6.0206, meter.dbFs, 0.0001)
    }

    @Test
    fun speakerProbeHasThreeAudibleBurstsAndSilentGaps() {
        val pcm = SpeakerTestSignal.pcm()
        assertEquals(WavFile.SAMPLE_RATE * 2, pcm.size)
        val samples = ShortArray(pcm.size / 2) { index ->
            ((pcm[index * 2].toInt() and 255) or (pcm[index * 2 + 1].toInt() shl 8)).toShort()
        }
        fun peak(fromMs: Int, toMs: Int) = samples
            .sliceArray(fromMs * 16 until toMs * 16).maxOf { abs(it.toInt()) }
        for (start in listOf(50, 400, 750)) assertTrue(peak(start, start + 80) > 6000)
        for (start in listOf(220, 570, 920)) assertEquals(0, peak(start, start + 50))
        assertTrue(samples.all { abs(it.toInt()) <= 6553 })
        assertEquals(0, samples.first().toInt())
        assertEquals(0, samples.last().toInt())
    }
}
