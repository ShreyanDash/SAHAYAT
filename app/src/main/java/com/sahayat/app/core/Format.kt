package com.sahayat.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Format {
    private fun fmt(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault())

    fun date(ms: Long): String = fmt("dd MMM yyyy").format(Date(ms))
    fun time(ms: Long): String = fmt("hh:mm:ss a").format(Date(ms))
    fun shortTime(ms: Long): String = fmt("hh:mm a").format(Date(ms))
    fun dateTime(ms: Long): String = fmt("dd MMM yyyy, hh:mm a").format(Date(ms))

    fun duration(seconds: Long?): String? {
        if (seconds == null || seconds < 0) return null
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    /** "12s ago", "4m ago", "2h ago", or a date. */
    fun relative(ms: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - ms).coerceAtLeast(0)
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "${TimeUnit.MILLISECONDS.toSeconds(diff)}s ago"
            diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
            diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
            else -> dateTime(ms)
        }
    }

    /** RSSI (dBm) → 0..4 bars, using common Wi-Fi thresholds. */
    fun signalBars(rssi: Int): Int = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -75 -> 2
        rssi >= -85 -> 1
        else -> 0
    }
}
