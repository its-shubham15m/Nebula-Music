package com.shubhamgupta.nebula_music.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shubhamgupta.nebula_music.models.Song
import com.shubhamgupta.nebula_music.repository.SongRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object SongCacheManager {
    private const val TAG = "SongCacheManager"

    // Primary cache storage
    private val songsCache = ConcurrentHashMap<Long, Song>()
    private var allSongsCache: List<Song> = emptyList()
    private var isCacheInitialized = false
    private var lastCacheUpdateTime = 0L

    // Cache update interval (5 minutes)
    private const val CACHE_UPDATE_INTERVAL = 5 * 60 * 1000L

    // Listeners for cache updates
    private val cacheUpdateListeners = mutableListOf<(List<Song>) -> Unit>()

    /**
     * Initialize the cache - should be called when app starts
     */
    fun initializeCache(context: Context) {
        if (isCacheInitialized && System.currentTimeMillis() - lastCacheUpdateTime < CACHE_UPDATE_INTERVAL) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val songs = SongRepository.getAllSongs(context)

                withContext(Dispatchers.Main) {
                    updateCache(songs)
                    isCacheInitialized = true
                    lastCacheUpdateTime = System.currentTimeMillis()
                    Log.d(TAG, "Cache initialized with ${songs.size} songs")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing cache", e)
            }
        }
    }

    /**
     * Force refresh the cache
     */
    fun refreshCache(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val songs = SongRepository.getAllSongs(context)

                withContext(Dispatchers.Main) {
                    updateCache(songs)
                    lastCacheUpdateTime = System.currentTimeMillis()
                    Log.d(TAG, "Cache refreshed with ${songs.size} songs")

                    // Notify listeners
                    cacheUpdateListeners.forEach { listener ->
                        listener(songs)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing cache", e)
            }
        }
    }

    /**
     * Get all songs from cache
     */
    fun getAllSongs(): List<Song> {
        return allSongsCache
    }

    /**
     * Get song by ID from cache
     */
    fun getSongById(id: Long): Song? {
        return songsCache[id]
    }

    /**
     * Get multiple songs by IDs efficiently
     */
    fun getSongsByIds(ids: List<Long>): List<Song> {
        return ids.mapNotNull { songsCache[it] }
    }

    /**
     * Check if cache needs update
     */
    fun shouldUpdateCache(): Boolean {
        return !isCacheInitialized ||
                System.currentTimeMillis() - lastCacheUpdateTime > CACHE_UPDATE_INTERVAL
    }

    /**
     * Add cache update listener
     */
    fun addCacheUpdateListener(listener: (List<Song>) -> Unit) {
        cacheUpdateListeners.add(listener)
    }

    /**
     * Remove cache update listener
     */
    fun removeCacheUpdateListener(listener: (List<Song>) -> Unit) {
        cacheUpdateListeners.remove(listener)
    }

    /**
     * Update internal cache storage
     */
    private fun updateCache(songs: List<Song>) {
        songsCache.clear()
        songs.forEach { song ->
            songsCache[song.id] = song
        }
        allSongsCache = songs
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats(): String {
        return "Songs in cache: ${songsCache.size}, Last update: $lastCacheUpdateTime"
    }
}