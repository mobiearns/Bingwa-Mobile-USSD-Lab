package com.bingwa.mobile

import android.content.Context
import android.os.Build
import java.util.Locale

/**
 * Lightweight per-device, per-popup Send-method learning.
 * Only confirmed prompt transitions are samples; carrier wait is excluded.
 */
internal class UssdAdaptiveSendMemory(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "ussd_adaptive_send_memory",
        Context.MODE_PRIVATE
    )
    private val devicePrefix = buildString {
        append("device.")
        append(Build.MANUFACTURER.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_"))
        append(".")
        append(Build.MODEL.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_"))
        append(".sdk")
        append(Build.VERSION.SDK_INT)
        append(".")
    }

    @Synchronized
    fun preferredMethod(popupSignature: String): String {
        val prefix = layoutPrefix(popupSignature)
        val ranked = METHODS.mapNotNull { method ->
            val count = preferences.getLong(key(prefix, method, "count"), 0L)
            if (count <= 0L) return@mapNotNull null
            method to preferences.getLong(key(prefix, method, "avg_us"), Long.MAX_VALUE)
        }
        // New popup signatures start with IME; known layouts use their own learned winner.
        return ranked.minByOrNull { it.second }?.first ?: METHOD_IME
    }

    @Synchronized
    fun recordConfirmed(method: String, localDurationUs: Long, popupSignature: String) {
        if (method !in METHODS) return
        val sample = localDurationUs.coerceAtLeast(0L)
        updateAverage(devicePrefix, method, sample)
        updateAverage(layoutPrefix(popupSignature), method, sample)
    }

    private fun updateAverage(prefix: String, method: String, sampleUs: Long) {
        val countKey = key(prefix, method, "count")
        val averageKey = key(prefix, method, "avg_us")
        val count = preferences.getLong(countKey, 0L)
        val previous = preferences.getLong(averageKey, sampleUs)
        // Exponential moving average adapts when keyboard or popup behaviour changes.
        val updated = if (count == 0L) sampleUs else (previous * 3L + sampleUs) / 4L
        preferences.edit()
            .putLong(countKey, count + 1L)
            .putLong(averageKey, updated)
            .apply()
    }

    fun buildReportSummary(): String = buildString {
        appendLine("Cold-start default: $METHOD_IME")
        METHODS.forEach { method ->
            val count = preferences.getLong(key(devicePrefix, method, "count"), 0L)
            val average = preferences.getLong(key(devicePrefix, method, "avg_us"), 0L)
            appendLine("$method confirmed=$count average_local_action_us=$average")
        }
        append("Only confirmed prompt transitions count; carrier/network wait is excluded.")
    }

    private fun layoutPrefix(signature: String): String =
        devicePrefix + "layout." + Integer.toHexString(signature.hashCode()) + "."

    private fun key(prefix: String, method: String, metric: String): String = "$prefix$method.$metric"

    private companion object {
        const val METHOD_IME = "ime"
        val METHODS = setOf("ime", "resource_id", "tree_scan")
    }
}
