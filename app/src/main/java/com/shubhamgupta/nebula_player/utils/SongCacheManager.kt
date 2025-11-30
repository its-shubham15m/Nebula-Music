package com.shubhamgupta.nebula_player.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.repository.SongRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object SongCacheManager {
    private const val TAG = "SongCacheManager"
    private const val SONG_CACHE_FILENAME = "song_cache.json"

    // The single source of truth for the app
    private val _allSongs = MutableStateFlow<List<Song>>(emptyList())
    val allSongs: StateFlow<List<Song>> = _allSongs.asStateFlow()

    private val songsMap = ConcurrentHashMap<Long, Song>()

    @Volatile
    private var isCacheInitialized = false

    // Properties for time-based cache updates
    @Volatile
    private var lastCacheUpdateTime = 0L
    private const val CACHE_UPDATE_INTERVAL = 5 * 60 * 1000L // 5 minutes

    // --- Gson Setup with Uri Adapter ---
    // This single instance MUST be used for both Reading and Writing
    private val gson: Gson by lazy {
        GsonBuilder()
            .registerTypeAdapter(Uri::class.java, UriTypeAdapter())
            .create()
    }

    private class UriTypeAdapter : TypeAdapter<Uri>() {
        override fun write(out: JsonWriter, value: Uri?) {
            out.value(value?.toString())
        }

        override fun read(input: JsonReader): Uri? {
            val s = input.nextString()
            return if (s.isNullOrBlank()) null else Uri.parse(s)
        }
    }
    // -----------------------------------

    fun initializeCache(context: Context) {
        if (isCacheInitialized) return

        CoroutineScope(Dispatchers.IO).launch {
            // Load JSON file immediately for instant UI
            val songsFromFile = loadCacheFromFile(context.applicationContext)
            if (!songsFromFile.isNullOrEmpty()) {
                Log.d(TAG, "Cache initialized from file with ${songsFromFile.size} songs.")
                updateMemoryCache(songsFromFile)
                // Set last update time to now to prevent immediate re-scan if file is fresh
                lastCacheUpdateTime = System.currentTimeMillis()
            }
            isCacheInitialized = true
        }
    }

    fun shouldUpdateCache(): Boolean {
        return !isCacheInitialized || (System.currentTimeMillis() - lastCacheUpdateTime > CACHE_UPDATE_INTERVAL)
    }

    fun refreshCache(context: Context) {
        SongRepository.refreshSongs(context)
    }

    suspend fun updateCache(context: Context, newSongs: List<Song>) {
        if (areSongListsDifferent(_allSongs.value, newSongs)) {
            Log.d(TAG, "Diff detected. Updating cache and saving to disk.")
            updateMemoryCache(newSongs)
            saveCacheToFile(context.applicationContext, newSongs)
            lastCacheUpdateTime = System.currentTimeMillis()
        } else {
            Log.d(TAG, "No changes detected. Skipping update.")
        }
    }

    private suspend fun updateMemoryCache(songs: List<Song>) {
        val newMap = ConcurrentHashMap<Long, Song>()
        songs.forEach { song -> newMap[song.id] = song }

        songsMap.clear()
        songsMap.putAll(newMap)
        _allSongs.emit(songs)
    }

    private fun areSongListsDifferent(old: List<Song>, new: List<Song>): Boolean {
        if (old.size != new.size) return true
        val newIds = new.map { it.id }.toSet()
        val oldIds = old.map { it.id }.toSet()
        return newIds != oldIds
    }

    private fun saveCacheToFile(context: Context, songs: List<Song>) {
        try {
            // FIX: Use the class-level 'gson' instance (with Uri adapter).
            // Do NOT use 'Gson()' constructor directly here.
            val jsonString = gson.toJson(songs)
            val cacheFile = File(context.filesDir, SONG_CACHE_FILENAME)
            cacheFile.writeText(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving cache to file", e)
        }
    }

    private fun loadCacheFromFile(context: Context): List<Song>? {
        val cacheFile = File(context.filesDir, SONG_CACHE_FILENAME)
        try {
            if (!cacheFile.exists() || cacheFile.readText().isBlank()) return null
            val type = object : TypeToken<List<Song>>() {}.type
            // Use the same gson instance to read
            return gson.fromJson(cacheFile.readText(), type)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cache from file. Deleting corrupt file.", e)
            // Critical Fix: Delete the corrupt file so the app doesn't crash on next run
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
            return null
        }
    }

    fun getAllSongs(): List<Song> = _allSongs.value
    fun getSongById(id: Long): Song? = songsMap[id]
}