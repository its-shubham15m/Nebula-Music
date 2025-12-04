package com.shubhamgupta.nebula_player.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shubhamgupta.nebula_player.models.Song
import java.io.File

object StatsManager {
    private const val PREFS_STATS = "nebula_orbit_stats"
    private const val KEY_GENRE_COUNTS = "genre_counts"
    private const val KEY_ARTIST_COUNTS = "artist_counts"
    private const val KEY_SKIP_COUNTS = "skip_counts"
    private const val KEY_LAST_WATCHED = "last_watched_video"

    data class VideoStat(val path: String, val position: Long, val totalDuration: Long)

    // --- MUSIC LOGIC ---
    fun logPlay(context: Context, song: Song) {
        val prefs = context.getSharedPreferences(PREFS_STATS, Context.MODE_PRIVATE)

        // Echo Chamber (Genre)
        val genres = loadMapString(prefs, KEY_GENRE_COUNTS)
        val genre = song.genre ?: "Unknown"
        genres[genre] = (genres[genre] ?: 0) + 1
        saveMapString(prefs, KEY_GENRE_COUNTS, genres)

        // Stellar Connections (Artist)
        val artists = loadMapString(prefs, KEY_ARTIST_COUNTS)
        val artist = song.artist ?: "Unknown"
        artists[artist] = (artists[artist] ?: 0) + 1
        saveMapString(prefs, KEY_ARTIST_COUNTS, artists)

        // Black Hole (Reset skip count if played fully)
        val skips = loadMapLong(prefs, KEY_SKIP_COUNTS)
        if (skips.containsKey(song.id)) {
            skips.remove(song.id)
            saveMapLong(prefs, KEY_SKIP_COUNTS, skips)
        }
    }

    // Call this if user skips < 10 seconds
    fun logSkip(context: Context, songId: Long) {
        val prefs = context.getSharedPreferences(PREFS_STATS, Context.MODE_PRIVATE)
        val skips = loadMapLong(prefs, KEY_SKIP_COUNTS)
        skips[songId] = (skips[songId] ?: 0) + 1
        saveMapLong(prefs, KEY_SKIP_COUNTS, skips)
    }

    // --- VIDEO LOGIC ---
    fun logVideoWatch(context: Context, path: String, position: Long, duration: Long) {
        val prefs = context.getSharedPreferences(PREFS_STATS, Context.MODE_PRIVATE)
        val stat = VideoStat(path, position, duration)
        prefs.edit().putString(KEY_LAST_WATCHED, Gson().toJson(stat)).apply()
    }

    fun getLastWatched(context: Context): VideoStat? {
        val prefs = context.getSharedPreferences(PREFS_STATS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_LAST_WATCHED, null) ?: return null
        return Gson().fromJson(json, VideoStat::class.java)
    }

    // --- GETTERS FOR AI ---
    fun getUserTopGenre(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_STATS, Context.MODE_PRIVATE)
        return loadMapString(prefs, KEY_GENRE_COUNTS).entries.maxByOrNull { it.value }?.key ?: "Pop"
    }

    fun getUserTopArtist(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_STATS, Context.MODE_PRIVATE)
        return loadMapString(prefs, KEY_ARTIST_COUNTS).entries.maxByOrNull { it.value }?.key ?: "Unknown"
    }

    fun getBlackHoleCandidates(context: Context): List<Long> {
        val prefs = context.getSharedPreferences(PREFS_STATS, Context.MODE_PRIVATE)
        return loadMapLong(prefs, KEY_SKIP_COUNTS).filter { it.value >= 3 }.keys.toList()
    }

    private fun loadMapString(prefs: android.content.SharedPreferences, key: String): MutableMap<String, Int> {
        val json = prefs.getString(key, "{}")
        val type = object : TypeToken<MutableMap<String, Int>>() {}.type
        return Gson().fromJson(json, type) ?: mutableMapOf()
    }

    private fun saveMapString(prefs: android.content.SharedPreferences, key: String, map: Map<String, Int>) {
        prefs.edit().putString(key, Gson().toJson(map)).apply()
    }

    private fun loadMapLong(prefs: android.content.SharedPreferences, key: String): MutableMap<Long, Int> {
        val json = prefs.getString(key, "{}")
        val type = object : TypeToken<MutableMap<Long, Int>>() {}.type
        return Gson().fromJson(json, type) ?: mutableMapOf()
    }

    private fun saveMapLong(prefs: android.content.SharedPreferences, key: String, map: Map<Long, Int>) {
        prefs.edit().putString(key, Gson().toJson(map)).apply()
    }
}