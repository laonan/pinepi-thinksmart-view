package com.pinepi.thinksmart.launcher

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Signal magnitude only. Does not identify speech or log sample content. */
class PcmLevelMeter(private val skipSamples: Int = WavFile.SAMPLE_RATE / 5) {
    private var seen = 0L
    private var measured = 0L
    private var squares = 0.0

    fun add(samples: ShortArray, count: Int) {
        for (index in 0 until count) {
            if (seen++ < skipSamples) continue
            val value = samples[index].toDouble()
            squares += value * value
            measured++
        }
    }

    val dbFs: Double
        get() = if (measured == 0L || squares == 0.0) -96.0
        else maxOf(-96.0, 20 * log10(sqrt(squares / measured) / 32768))
}

/** Three short, faded tones separated by silence; no microphone access. */
object SpeakerTestSignal {
    fun pcm(): ByteArray {
        val result = ByteArray(WavFile.SAMPLE_RATE * 2)
        for (index in 0 until WavFile.SAMPLE_RATE) {
            val slot = index * 1000 / WavFile.SAMPLE_RATE % 350
            val envelope = when {
                slot >= 200 -> 0.0
                slot < 15 -> slot / 15.0
                slot > 185 -> (200 - slot) / 15.0
                else -> 1.0
            }
            val value = (sin(2 * PI * 660 * index / WavFile.SAMPLE_RATE) * 6553 * envelope).toInt()
            result[index * 2] = value.toByte()
            result[index * 2 + 1] = (value shr 8).toByte()
        }
        return result
    }
}
