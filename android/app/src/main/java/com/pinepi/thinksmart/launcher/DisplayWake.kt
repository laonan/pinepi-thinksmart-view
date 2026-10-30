package com.pinepi.thinksmart.launcher

import android.content.Context
import android.os.PowerManager

object DisplayWake {
    enum class Result { ALREADY_ON, REQUESTED, FAILED }

    @Suppress("DEPRECATION") // Dedicated API 27 appliance; no Activity launch or permanent screen lock.
    fun request(context: Context, tag: String): Result {
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isInteractive) return Result.ALREADY_ON
        return try {
            val lock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "PinePi:$tag",
            )
            lock.setReferenceCounted(false)
            try { lock.acquire(1000L) } finally { if (lock.isHeld) lock.release() }
            Result.REQUESTED
        } catch (_: RuntimeException) { Result.FAILED }
    }
}
