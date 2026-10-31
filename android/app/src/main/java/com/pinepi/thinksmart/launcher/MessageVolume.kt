package com.pinepi.thinksmart.launcher

/** A temporary media-volume floor. All calls belong to the playback/main thread. */
internal class MessageVolume(
    private val read: () -> Int,
    private val maximum: () -> Int,
    private val write: (Int) -> Unit,
) {
    private var original: Int? = null
    private var raised: Int? = null
    private var overridden = false

    fun begin(step: Int) {
        finish()
        val current = read()
        val target = step.coerceIn(0, maximum().coerceAtLeast(0))
        if (current >= target) return
        original = current
        raised = target
        overridden = false
        write(target)
    }

    fun observe() {
        raised?.let { if (read() != it) overridden = true }
    }

    fun userAdjusted() { if (raised != null) overridden = true }

    fun finish() {
        val previous = original
        val applied = raised
        original = null
        raised = null
        if (previous != null && applied != null && !overridden && read() == applied) write(previous)
        overridden = false
    }

}
