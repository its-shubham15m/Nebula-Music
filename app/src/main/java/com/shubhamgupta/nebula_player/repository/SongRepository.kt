package com.shubhamgupta.nebula_player.repository

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.utils.SongCacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Repository acts as the single entry point for data.
 * It handles the actual fetching from MediaStore and updates the CacheManager.
 */
object SongRepository {

    private const val TAG = "SongRepository"

    /**
     * Returns a reactive stream of songs from the Cache.
     * The UI observes this to stay jitter-free.
     */
    fun getSongsFlow(): Flow<List<Song>> {
        return SongCacheManager.allSongs
    }

    /**
     * Returns the current cached songs instantly.
     * Use this for initial checks or synchronous needs (e.g. Service startup).
     */
    fun getAllSongs(context: Context): List<Song> {
        // Return cached data for speed
        val cached = SongCacheManager.getAllSongs()

        // If cache is empty, we can trigger a load, but return empty list for now to avoid blocking
        if (cached.isEmpty()) {
            refreshSongs(context)
        }
        return cached
    }

    /**
     * Triggers a background sync.
     * Queries MediaStore -> Updates Cache -> UI updates automatically via Flow.
     */
    fun refreshSongs(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val freshSongs = fetchSongsFromDevice(context)
            // Update the cache (which notifies the UI)
            SongCacheManager.updateCache(context, freshSongs)
        }
    }

    /**
     * Efficient O(1) lookup.
     */
    fun getSongById(id: Long): Song? {
        return SongCacheManager.getSongById(id)
    }

    /**
     * The actual fetching logic (moved here as requested).
     * Scans the device storage for audio files.
     */
    private fun fetchSongsFromDevice(context: Context): List<Song> {
        val songs = mutableListOf<Song>()
        Log.d(TAG, "Starting MediaStore scan...")

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
                MediaStore.Audio.Media.DATA // For file path
            )

            // Filter: Music files only, longer than 10 seconds
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
                    val dateAdded = it.getLong(dateAddedIndex) * 1000 // Convert to milliseconds
                    val year = it.getString(yearIndex)
                    val genre = it.getString(genreIndex)
                    val path = it.getString(dataIndex)

                    val uri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )

                    // Get favorite status from PreferenceManager
                    val isFavorite = PreferenceManager.isFavorite(context, id)

                    songs.add(
                        Song(
                            id = id,
                            title = title,
                            artist = artist,
                            albumId = albumId,
                            album = album,
                            year = year,
                            genre = genre,
                            uri = uri,
                            duration = duration,
                            dateAdded = dateAdded,
                            path = path,
                            isFavorite = isFavorite,
                            // Ensure other fields are handled if your Song model requires them
                            embeddedArtBytes = null
                        )
                    )
                }
            }
            Log.d(TAG, "Loaded ${songs.size} songs from MediaStore")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading songs", e)
        }

        return songs
    }
}