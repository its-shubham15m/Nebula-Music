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

    private var sessionStartTime: Long = 0

    fun startSession(context: Context) {
        sessionStartTime = System.currentTimeMillis()
        updateStreak(context)
    }

    fun endSession(context: Context) {
        if (sessionStartTime == 0L) return

        val currentTime = System.currentTimeMillis()
        val sessionDuration = currentTime - sessionStartTime

        if (sessionDuration > 1000) { // Ignore sessions less than 1 second
            val prefs = getPrefs(context)

            // 1. Save Total Aggregate
            val previousTotal = prefs.getLong(KEY_TOTAL_USAGE_MS, 0L)
            prefs.edit().putLong(KEY_TOTAL_USAGE_MS, previousTotal + sessionDuration).apply()

            // 2. CRITICAL FIX: Log specifically to History for the Graph
            UserActivityHistory.logSessionDuration(context, sessionDuration)
        }

        sessionStartTime = 0
    }

    // --- Getters ---

    /**
     * Returns total historical time + current active session time
     */
    fun getCurrentTotalUsage(context: Context): Long {
        val prefs = getPrefs(context)
        val savedTotal = prefs.getLong(KEY_TOTAL_USAGE_MS, 0L)

        // If user is currently in the app, add the live session duration
        val currentSession = if (sessionStartTime > 0) {
            System.currentTimeMillis() - sessionStartTime
        } else 0L

        return savedTotal + currentSession
    }

    fun getStreak(context: Context): Int {
        return getPrefs(context).getInt(KEY_STREAK_COUNT, 1)
    }

    private fun updateStreak(context: Context) {
        val prefs = getPrefs(context)
        val lastOpenTime = prefs.getLong(KEY_LAST_OPEN_TIMESTAMP, 0L)
        val currentStreak = prefs.getInt(KEY_STREAK_COUNT, 0)

        val now = System.currentTimeMillis()
        val todayCalendar = getCalendar(now)
        val lastCalendar = getCalendar(lastOpenTime)

        if (lastOpenTime == 0L) {
            saveStreak(context, 1, now)
            return
        }

        if (isSameDay(todayCalendar, lastCalendar)) return

        if (isYesterday(todayCalendar, lastCalendar)) {
            saveStreak(context, currentStreak + 1, now)
        } else {
            saveStreak(context, 1, now)
        }
    }

    private fun saveStreak(context: Context, count: Int, timestamp: Long) {
        getPrefs(context).edit()
            .putInt(KEY_STREAK_COUNT, count)
            .putLong(KEY_LAST_OPEN_TIMESTAMP, timestamp)
            .apply()
    }

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
        val temp = today.clone() as Calendar
        temp.add(Calendar.DAY_OF_YEAR, -1)
        return isSameDay(temp, previous)
    }
}