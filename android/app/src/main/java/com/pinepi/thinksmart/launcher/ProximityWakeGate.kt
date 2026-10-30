package com.pinepi.thinksmart.launcher

/** One wake per observed far-to-near edge; call with a monotonic clock. */
class ProximityWakeGate(private val cooldownMs: Long = 1500L) {
    private var armed = false
    private var lastWakeMs: Long? = null

    init {
        require(cooldownMs >= 0)
    }

    fun shouldWake(value: Float, maxRange: Float, interactive: Boolean, nowMs: Long): Boolean {
        if (!value.isFinite() || !maxRange.isFinite() || maxRange <= 0f ||
            value < 0f || value > maxRange || nowMs < 0L) {
            // A broken sample must not establish or preserve a trustworthy edge.
            armed = false
            return false
        }
        if (value >= minOf(maxRange, 5f)) {
            armed = true
            return false
        }
        val wasArmed = armed
        // Consume the edge even while lit or cooling down. Holding a hand near
        // must not turn a later screen-off into another wake request.
        armed = false
        if (!wasArmed || interactive) return false
        val previous = lastWakeMs
        if (previous != null && (nowMs < previous || nowMs - previous < cooldownMs)) return false
        lastWakeMs = nowMs
        return true
    }
}
