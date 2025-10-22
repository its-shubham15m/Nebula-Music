package com.shubhamgupta.nebula_music.utils

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.shubhamgupta.nebula_music.models.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object SongCacheManager {
    private const val TAG = "SongCacheManager"

    private val songsCache = ConcurrentHashMap<Long, Song>()
    @Volatile
    private var allSongsCache: List<Song> = emptyList()

    @Volatile
    private var isCacheInitialized = false

    // NEW: Added properties for time-based cache updates
    @Volatile
    private var lastCacheUpdateTime = 0L
    private const val CACHE_UPDATE_INTERVAL = 5 * 60 * 1000L // 5 minutes

    fun initializeCache(context: Context) {
        if (isCacheInitialized) return

        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Initializing cache from MediaStore...")
            val songs = loadSongsFromMediaStore(context.applicationContext)
            updateCache(songs)
            isCacheInitialized = true
            // NEW: Set the initial update time
            lastCacheUpdateTime = System.currentTimeMillis()
            Log.d(TAG, "Cache initialized with ${songs.size} songs.")
        }
    }

    fun refreshCache(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Refreshing cache from MediaStore...")
            val songs = loadSongsFromMediaStore(context.applicationContext)
            updateCache(songs)
            // NEW: Set the refresh update time
            lastCacheUpdateTime = System.currentTimeMillis()
            Log.d(TAG, "Cache refreshed with ${songs.size} songs.")
        }
    }

    private fun loadSongsFromMediaStore(context: Context): List<Song> {
        val songs = mutableListOf<Song>()
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.GENRE,
                MediaStore.Audio.Media.DATA
            )

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000"

            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                MediaStore.Audio.Media.TITLE + " ASC"
            )

            cursor?.use {
                val idIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateAddedIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val yearIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val genreIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE)
                val dataIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (it.moveToNext()) {
                    val id = it.getLong(idIndex)
                    val title = it.getString(titleIndex) ?: "Unknown Title"
                    val artist = it.getString(artistIndex) ?: "Unknown Artist"
                    val album = it.getString(albumIndex)
                    val albumId = it.getLong(albumIdIndex)
                    val duration = it.getLong(durationIndex)
                    val dateAdded = it.getLong(dateAddedIndex) * 1000
                    val year = it.getString(yearIndex)
                    val genre = it.getString(genreIndex)
                    val path = it.getString(dataIndex)
                    val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                    val isFavorite = PreferenceManager.isFavorite(context, id)

                    songs.add(
                        Song(id, title, artist, albumId, album, year, genre, uri, duration, dateAdded, path, isFavorite)
                    )
                }
            }
            Log.d(TAG, "Loaded ${songs.size} songs from MediaStore")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading songs from MediaStore", e)
        }
        return songs
    }

    fun getAllSongs(): List<Song> {
        return allSongsCache
    }

    fun getSongById(id: Long): Song? {
        return songsCache[id]
    }

    // NEW: The function your MusicService needs
    /**
     * Checks if the cache is stale and needs to be updated.
     */
    fun shouldUpdateCache(): Boolean {
        // Update if the cache was never initialized OR if it's been longer than the interval
        return !isCacheInitialized || (System.currentTimeMillis() - lastCacheUpdateTime > CACHE_UPDATE_INTERVAL)
    }

    private suspend fun updateCache(songs: List<Song>) {
        val newMap = ConcurrentHashMap<Long, Song>()
        songs.forEach { song ->
            newMap[song.id] = song
        }
        withContext(Dispatchers.Main) {
            songsCache.clear()
            songsCache.putAll(newMap)
            allSongsCache = songs
        }
    }
}