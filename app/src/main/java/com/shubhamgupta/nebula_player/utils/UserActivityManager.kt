package com.shubhamgupta.nebula_player.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar
import java.util.concurrent.TimeUnit

object UserActivityManager {
    private const val PREFS_NAME = "nebula_activity_prefs"
    private const val KEY_TOTAL_USAGE_MS = "total_usage_ms"
    private const val KEY_LAST_OPEN_TIMESTAMP = "last_open_timestamp"
    private const val KEY_STREAK_COUNT = "streak_count"

    // Session tracking
    private var sessionStartTime: Long = 0

    /**
     * Call this in onResume()
     * Starts the timer and updates the daily streak
     */
    fun startSession(context: Context) {
        sessionStartTime = System.currentTimeMillis()
        updateStreak(context)
    }

    /**
     * Call this in onPause()
     * Calculates the time spent in this session and saves it
     */
    fun endSession(context: Context) {
        if (sessionStartTime == 0L) return

        val currentTime = System.currentTimeMillis()
        val sessionDuration = currentTime - sessionStartTime

        // Prevent negative or tiny glitches
        if (sessionDuration > 0) {
            val prefs = getPrefs(context)
            val previousTotal = prefs.getLong(KEY_TOTAL_USAGE_MS, 0L)
            prefs.edit().putLong(KEY_TOTAL_USAGE_MS, previousTotal + sessionDuration).apply()
        }

        sessionStartTime = 0 // Reset
    }

    /**
     * Logic to calculate daily streaks
     */
    private fun updateStreak(context: Context) {
        val prefs = getPrefs(context)
        val lastOpenTime = prefs.getLong(KEY_LAST_OPEN_TIMESTAMP, 0L)
        val currentStreak = prefs.getInt(KEY_STREAK_COUNT, 0)

        val now = System.currentTimeMillis()
        val todayCalendar = getCalendar(now)
        val lastCalendar = getCalendar(lastOpenTime)

        if (lastOpenTime == 0L) {
            // First time ever opening app
            saveStreak(context, 1, now)
            return
        }

        if (isSameDay(todayCalendar, lastCalendar)) {
            // Already opened today, do nothing to streak
            return
        }

        if (isYesterday(todayCalendar, lastCalendar)) {
            // Opened yesterday, increment streak
            saveStreak(context, currentStreak + 1, now)
        } else {
            // Missed a day (or more), reset streak to 1
            saveStreak(context, 1, now)
        }
    }

    private fun saveStreak(context: Context, count: Int, timestamp: Long) {
        getPrefs(context).edit()
            .putInt(KEY_STREAK_COUNT, count)
            .putLong(KEY_LAST_OPEN_TIMESTAMP, timestamp)
            .apply()
    }

    // --- Getters for UI ---

    fun getStreak(context: Context): Int {
        return getPrefs(context).getInt(KEY_STREAK_COUNT, 1) // Default to 1 if new
    }

    fun getFormattedUsageTime(context: Context): String {
        val prefs = getPrefs(context)
        val totalMs = prefs.getLong(KEY_TOTAL_USAGE_MS, 0L)

        val hours = TimeUnit.MILLISECONDS.toHours(totalMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(totalMs) % 60

        return if (hours > 0) {
            "${hours}.${(minutes / 6).toString().take(1)}h played" // e.g., "12.5h played"
        } else {
            "${minutes}m played" // e.g., "45m played"
        }
    }

    // --- Helpers ---

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getCalendar(millis: Long): Calendar {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        return cal
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(today: Calendar, previous: Calendar): Boolean {
        // Clone today so we don't modify the object reference
        val temp = today.clone() as Calendar
        temp.add(Calendar.DAY_OF_YEAR, -1)
        return isSameDay(temp, previous)
    }
}