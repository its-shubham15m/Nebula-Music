package com.shubhamgupta.nebula_player.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Manages the historical data of user activity (Daily Usage, Streaks).
 */
object UserActivityHistory {
    private const val PREFS_NAME = "nebula_activity_history"
    private const val KEY_DAILY_LOGS = "daily_usage_logs" // Map<String, Long> (Date -> Millis)

    // Helper data class for graph plotting
    data class DailyStat(
        val dateLabel: String, // e.g., "Mon"
        val fullDate: String,  // e.g., "2023-10-25"
        val durationMs: Long,
        val relativeHeight: Float // 0.0 to 1.0 for bar graphs
    )

    /**
     * Called by UserActivityManager or directly when a session ends.
     * Log the duration for Today's date.
     */
    fun logSessionDuration(context: Context, durationMs: Long) {
        if (durationMs <= 0) return

        val prefs = getPrefs(context)
        val logs = getLogs(prefs).toMutableMap()

        val todayKey = getTodayKey()
        val currentDailyTotal = logs[todayKey] ?: 0L

        logs[todayKey] = currentDailyTotal + durationMs

        saveLogs(prefs, logs)
    }

    /**
     * Returns a list of stats for the past 7 days (including today).
     * Used for drawing charts.
     */
    fun getLast7DaysStats(context: Context): List<DailyStat> {
        val prefs = getPrefs(context)
        val logs = getLogs(prefs)

        val stats = mutableListOf<DailyStat>()
        val calendar = Calendar.getInstance()

        // Go back 6 days to start from (Today - 6)
        calendar.add(Calendar.DAY_OF_YEAR, -6)

        var maxDuration = 1L // Avoid divide by zero

        // 1. Collect raw data
        val tempStats = mutableListOf<Pair<Calendar, Long>>()
        for (i in 0..6) {
            val dateKey = getDateKey(calendar.time)
            val duration = logs[dateKey] ?: 0L
            if (duration > maxDuration) maxDuration = duration

            tempStats.add(Pair(calendar.clone() as Calendar, duration))
            calendar.add(Calendar.DAY_OF_YEAR, 1) // Move to next day
        }

        // 2. Format for UI
        val dayFormat = SimpleDateFormat("EEE", Locale.getDefault()) // "Mon", "Tue"

        for ((cal, duration) in tempStats) {
            stats.add(
                DailyStat(
                    dateLabel = dayFormat.format(cal.time),
                    fullDate = getDateKey(cal.time),
                    durationMs = duration,
                    relativeHeight = duration.toFloat() / maxDuration.toFloat()
                )
            )
        }

        return stats
    }

    /**
     * Get total usage all time formatted string
     */
    fun getTotalUsageFormatted(context: Context): String {
        val logs = getLogs(getPrefs(context))
        var totalMs = 0L
        logs.values.forEach { totalMs += it }
        return formatDuration(totalMs)
    }

    /**
     * Get usage for specifically today
     */
    fun getTodayUsageFormatted(context: Context): String {
        val logs = getLogs(getPrefs(context))
        val ms = logs[getTodayKey()] ?: 0L
        return formatDuration(ms)
    }

    // --- Private Helpers ---

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getLogs(prefs: SharedPreferences): Map<String, Long> {
        val json = prefs.getString(KEY_DAILY_LOGS, null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, Long>>() {}.type
        return try {
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveLogs(prefs: SharedPreferences, logs: Map<String, Long>) {
        val json = Gson().toJson(logs)
        prefs.edit().putString(KEY_DAILY_LOGS, json).apply()
    }

    private fun getTodayKey(): String {
        return getDateKey(Date())
    }

    private fun getDateKey(date: Date): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
    }

    private fun formatDuration(ms: Long): String {
        val hours = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return "${hours}h ${minutes}m"
    }
}