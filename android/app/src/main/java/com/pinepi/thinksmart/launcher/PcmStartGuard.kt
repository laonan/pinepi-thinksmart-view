package com.pinepi.thinksmart.launcher

/** Drop microphone startup transients, then ramp the first usable 20 ms from zero. */
class PcmStartGuard(
    warmupSamples: Int = WavFile.SAMPLE_RATE / 5,
    private val fadeSamples: Int = WavFile.SAMPLE_RATE / 50,
) {
    private var remainingWarmup = warmupSamples
    private var emitted = 0L

    init {
        require(warmupSamples >= 0 && fadeSamples > 1)
    }

    /** Compacts valid PCM into the front of [samples]; returns its sample count. */
    fun process(samples: ShortArray, count: Int): Int {
        require(count in 0..samples.size)
        val dropped = minOf(remainingWarmup, count)
        remainingWarmup -= dropped
        val kept = count - dropped
        if (kept > 0 && dropped > 0) System.arraycopy(samples, dropped, samples, 0, kept)
        for (index in 0 until kept) {
            if (emitted < fadeSamples) {
                samples[index] = (samples[index].toLong() * emitted / (fadeSamples - 1)).toShort()
            }
            emitted++
        }
        return kept
    }
}
