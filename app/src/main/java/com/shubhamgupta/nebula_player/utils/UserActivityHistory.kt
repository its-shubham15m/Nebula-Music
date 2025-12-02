package com.shubhamgupta.nebula_player.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object UserActivityHistory {
    private const val PREFS_NAME = "nebula_activity_history"
    private const val KEY_DAILY_LOGS = "daily_usage_logs"

    data class ChartData(
        val label: String,
        val value: Long,
        val fullDate: String,
        val heightRatio: Float
    )

    fun logSessionDuration(context: Context, durationMs: Long) {
        if (durationMs <= 0) return
        val prefs = getPrefs(context)
        val logs = getLogs(prefs).toMutableMap()
        val todayKey = getTodayKey()
        logs[todayKey] = (logs[todayKey] ?: 0L) + durationMs
        saveLogs(prefs, logs)
    }

    /**
     * Get Last 7 Days (Daily View)
     */
    fun getLast7DaysStats(context: Context): List<ChartData> {
        val logs = getLogs(getPrefs(context))
        val stats = mutableListOf<ChartData>()
        val calendar = Calendar.getInstance()

        // Reset to start of today to ensure consistent dates
        calendar.set(Calendar.HOUR_OF_DAY, 0)

        calendar.add(Calendar.DAY_OF_YEAR, -6) // Start 6 days ago

        var maxVal = 1L
        val rawData = mutableListOf<Pair<String, Long>>()

        val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())

        for (i in 0..6) {
            val dateKey = getDateKey(calendar.time)
            val ms = logs[dateKey] ?: 0L
            if (ms > maxVal) maxVal = ms

            val label = if (i == 6) "Today" else dayFormat.format(calendar.time)
            rawData.add(Pair(label, ms))

            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        return rawData.map {
            ChartData(it.first, it.second, "", it.second.toFloat() / maxVal.toFloat())
        }
    }

    /**
     * Get Last 4 Weeks (Weekly View)
     * Groups daily logs into 7-day chunks.
     */
    fun getLast4WeeksStats(context: Context): List<ChartData> {
        val logs = getLogs(getPrefs(context))
        val stats = mutableListOf<ChartData>()
        val calendar = Calendar.getInstance()

        var maxVal = 1L
        val rawData = mutableListOf<Pair<String, Long>>()

        // Loop back 4 weeks
        for (i in 0 until 4) {
            var weeklyTotal = 0L
            // Sum up 7 days
            for (j in 0 until 7) {
                val dateKey = getDateKey(calendar.time)
                weeklyTotal += (logs[dateKey] ?: 0L)
                calendar.add(Calendar.DAY_OF_YEAR, -1)
            }

            if (weeklyTotal > maxVal) maxVal = weeklyTotal
            val label = if (i == 0) "This Week" else "${i}w Ago"
            rawData.add(0, Pair(label, weeklyTotal)) // Add to front to reverse order
        }

        return rawData.map {
            ChartData(it.first, it.second, "", it.second.toFloat() / maxVal.toFloat())
        }
    }

    fun getTodayUsage(context: Context): Long {
        val logs = getLogs(getPrefs(context))
        return logs[getTodayKey()] ?: 0L
    }

    // --- Formatting Helpers ---

    fun formatDuration(ms: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        if (hours == 0L && minutes == 0L && ms > 0) return "< 1m"
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    // --- Private ---

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getLogs(prefs: SharedPreferences): Map<String, Long> {
        val json = prefs.getString(KEY_DAILY_LOGS, null) ?: return emptyMap()
        return try {
            Gson().fromJson(json, object : TypeToken<Map<String, Long>>() {}.type)
        } catch (e: Exception) { emptyMap() }
    }

    private fun saveLogs(prefs: SharedPreferences, logs: Map<String, Long>) {
        prefs.edit().putString(KEY_DAILY_LOGS, Gson().toJson(logs)).apply()
    }

    private fun getTodayKey() = getDateKey(Date())
    private fun getDateKey(date: Date) = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
}