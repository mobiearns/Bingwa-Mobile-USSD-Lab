package com.bingwa.mobile

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Manages CPU wake locks during active USSD sessions and incoming transaction dispatches.
 * Prevents device sleep/doze from interrupting accessibility navigation and socket communication
 * when triggered by background SMS while screen is locked or idle.
 */
internal object UssdWakelock {
    private const val TAG = "UssdWakelock"
    private var wakeLock: PowerManager.WakeLock? = null
    private val lock = Any()

    fun acquire(context: Context, timeoutMs: Long = 90_000L) {
        synchronized(lock) {
            try {
                if (wakeLock == null) {
                    val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bingwa:ussd_session_wakelock")?.apply {
                        setReferenceCounted(false)
                    }
                }
                wakeLock?.acquire(timeoutMs)
                Log.d(TAG, "WakeLock acquired for USSD session (timeout: ${timeoutMs}ms)")
            } catch (e: Exception) {
                Log.w(TAG, "Could not acquire WakeLock: ${e.message}")
            }
        }
    }

    fun release() {
        synchronized(lock) {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                    Log.d(TAG, "WakeLock released")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing WakeLock: ${e.message}")
            }
        }
    }
}
