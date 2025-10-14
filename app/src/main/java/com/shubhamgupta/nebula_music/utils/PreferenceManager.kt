package com.shubhamgupta.nebula_music.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shubhamgupta.nebula_music.MainActivity
import com.shubhamgupta.nebula_music.models.Playlist
import com.shubhamgupta.nebula_music.models.Song
import com.shubhamgupta.nebula_music.service.MusicService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PreferenceManager {

    private const val PREFS_NAME = "NebulaMusicPrefs"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_RECENT_SONGS = "recent_songs"
    private const val KEY_PLAYLISTS = "playlists"
    private const val MAX_RECENT_SONGS = 50

    // Enhanced keys for playback state and queue with HashMap optimization
    private const val KEY_LAST_PLAYED_SONG_ID = "last_played_song_id"
    private const val KEY_LAST_SEEKBAR_POSITION = "last_seekbar_position"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_SHUFFLE_MODE = "shuffle_mode"
    private const val KEY_QUEUE_SONGS = "queue_songs"
    private const val KEY_CURRENT_QUEUE_POSITION = "current_queue_position"
    private const val KEY_ORIGINAL_QUEUE_SONGS = "original_queue_songs"
    private const val KEY_QUEUE_HASHMAP = "queue_hashmap"
    private const val KEY_LAST_SONG_DETAILS = "last_song_details"

    // Add these constants at the top with other keys
    private const val KEY_SORT_SONGS = "sort_songs"
    private const val KEY_SORT_ARTISTS = "sort_artists"
    private const val KEY_SORT_ALBUMS = "sort_albums"
    private const val KEY_SORT_GENRES = "sort_genres"

    // Cache for songs to avoid repeated loading - NOW USING HASHMAP FOR FAST LOOKUP
    private var cachedSongsMap: Map<Long, Song> = emptyMap()
    private var isCacheDirty = true

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    //
    // NEW: Sorting Preferences Management
    //
    fun saveSortPreference(context: Context, category: String, sortType: MainActivity.SortType) {
        val key = when (category) {
            "songs" -> KEY_SORT_SONGS
            "artists" -> KEY_SORT_ARTISTS
            "albums" -> KEY_SORT_ALBUMS
            "genres" -> KEY_SORT_GENRES
            else -> KEY_SORT_SONGS
        }
        getPreferences(context).edit().putInt(key, sortType.ordinal).apply()
        Log.d("PreferenceManager", "Saved sort preference for $category: $sortType")
    }

    // Add this method to PreferenceManager
    fun getSortPreferenceWithDefault(context: Context, category: String): MainActivity.SortType {
        val key = when (category) {
            "songs" -> KEY_SORT_SONGS
            "artists" -> KEY_SORT_ARTISTS
            "albums" -> KEY_SORT_ALBUMS
            "genres" -> KEY_SORT_GENRES
            else -> KEY_SORT_SONGS
        }
        val defaultSort = when (category) {
            "songs" -> MainActivity.SortType.DATE_ADDED_DESC.ordinal // Default to newest first
            "artists" -> MainActivity.SortType.NAME_ASC.ordinal
            "albums" -> MainActivity.SortType.NAME_ASC.ordinal
            "genres" -> MainActivity.SortType.NAME_ASC.ordinal
            else -> MainActivity.SortType.NAME_ASC.ordinal
        }
        val sortOrdinal = getPreferences(context).getInt(key, defaultSort)
        return MainActivity.SortType.entries.toTypedArray().getOrElse(sortOrdinal) {
            MainActivity.SortType.entries.toTypedArray()[defaultSort]
        }
    }

    //
    // Favorites management
    //
    fun addFavorite(context: Context, songId: Long) {
        val favorites = getFavorites(context).toMutableSet()
        favorites.add(songId)
        getPreferences(context).edit().putString(KEY_FAVORITES, favorites.joinToString(",")).apply()
    }

    fun removeFavorite(context: Context, songId: Long) {
        val favorites = getFavorites(context).toMutableSet()
        favorites.remove(songId)
        getPreferences(context).edit().putString(KEY_FAVORITES, favorites.joinToString(",")).apply()
    }

    fun getFavorites(context: Context): Set<Long> {
        val favoritesString = getPreferences(context).getString(KEY_FAVORITES, "") ?: ""
        return if (favoritesString.isEmpty()) emptySet()
        else favoritesString.split(",").mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun isFavorite(context: Context, songId: Long): Boolean {
        return getFavorites(context).contains(songId)
    }

    //
    // Recent songs management
    //
    fun addRecentSong(context: Context, songId: Long) {
        val recentSongs = getRecentSongs(context).toMutableList()
        recentSongs.remove(songId) // Remove if already exists (ensures it moves to top)
        recentSongs.add(0, songId) // Add to the beginning

        // Keep only recent songs up to MAX_RECENT_SONGS
        if (recentSongs.size > MAX_RECENT_SONGS) {
            recentSongs.subList(MAX_RECENT_SONGS, recentSongs.size).clear()
        }

        getPreferences(context).edit().putString(KEY_RECENT_SONGS, recentSongs.joinToString(",")).apply()
    }

    fun removeRecentSong(context: Context, songId: Long) {
        val recentSongs = getRecentSongs(context).toMutableList()
        recentSongs.remove(songId)
        getPreferences(context).edit().putString(KEY_RECENT_SONGS, recentSongs.joinToString(",")).apply()
    }

    fun getRecentSongs(context: Context): List<Long> {
        val recentString = getPreferences(context).getString(KEY_RECENT_SONGS, "") ?: ""
        return if (recentString.isEmpty()) emptyList()
        else recentString.split(",").mapNotNull { it.toLongOrNull() }
    }

    //
    // Playlists management
    //
    fun savePlaylists(context: Context, playlists: List<Playlist>) {
        val gson = Gson()
        val json = gson.toJson(playlists)
        getPreferences(context).edit().putString(KEY_PLAYLISTS, json).apply()
    }

    fun getPlaylists(context: Context): List<Playlist> {
        val json = getPreferences(context).getString(KEY_PLAYLISTS, null)
        return if (json == null) {
            emptyList()
        } else {
            val type = object : TypeToken<List<Playlist>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        }
    }

    fun addSongToPlaylist(context: Context, playlistId: Long, songId: Long) {
        val playlists = getPlaylists(context).toMutableList()
        playlists.find { it.id == playlistId }?.songIds?.add(songId)
        savePlaylists(context, playlists)
    }

    fun removeSongFromPlaylist(context: Context, playlistId: Long, songId: Long) {
        val playlists = getPlaylists(context).toMutableList()
        playlists.find { it.id == playlistId }?.songIds?.remove(songId)
        savePlaylists(context, playlists)
    }

    //
    // NEW: Playback State and Queue Persistence
    //

    /**
     * UPDATED: Save playback state with better queue handling
     */
    fun savePlaybackState(
        context: Context,
        currentSongId: Long?,
        seekPosition: Int,
        repeatMode: MusicService.RepeatMode,
        isShuffleMode: Boolean,
        queueSongs: List<Song>,
        currentQueuePosition: Int,
        originalQueueSongs: List<Song>
    ) {
        val editor = getPreferences(context).edit()

        // Save basic playback state
        editor.putLong(KEY_LAST_PLAYED_SONG_ID, currentSongId ?: -1L)
        editor.putInt(KEY_LAST_SEEKBAR_POSITION, seekPosition)
        editor.putInt(KEY_REPEAT_MODE, repeatMode.ordinal)
        editor.putBoolean(KEY_SHUFFLE_MODE, isShuffleMode)
        editor.putInt(KEY_CURRENT_QUEUE_POSITION, currentQueuePosition)

        // Save queue songs as JSON
        val gson = Gson()

        // Save song IDs for queue - ensure we save the actual current queue
        val queueJson = gson.toJson(queueSongs.map { it.id })
        val originalQueueJson = gson.toJson(originalQueueSongs.map { it.id })

        editor.putString(KEY_QUEUE_SONGS, queueJson)
        editor.putString(KEY_ORIGINAL_QUEUE_SONGS, originalQueueJson)

        Log.d("PreferenceManager", "Saving playback state: songId=$currentSongId, seekPos=$seekPosition, queueSize=${queueSongs.size}, queuePos=$currentQueuePosition, originalQueueSize=${originalQueueSongs.size}")

        // Save HashMap of all songs for fast lookup
        val songsMap = hashMapOf<String, Map<String, String>>()
        (queueSongs + originalQueueSongs).forEach { song ->
            songsMap[song.id.toString()] = mapOf(
                "title" to song.title,
                "artist" to (song.artist ?: "Unknown Artist"),
                "album" to (song.album ?: "Unknown Album"),
                "albumId" to song.albumId.toString(),
                "duration" to song.duration.toString(),
                "uri" to song.uri.toString() // NEW: Save URI for better restoration
            )
        }
        val hashMapJson = gson.toJson(songsMap)
        editor.putString(KEY_QUEUE_HASHMAP, hashMapJson)

        // Save last song details for MiniPlayer
        currentSongId?.let { songId ->
            val currentSong = queueSongs.getOrNull(currentQueuePosition) ?: return@let
            val lastSongDetails = mapOf(
                "title" to currentSong.title,
                "artist" to (currentSong.artist ?: "Unknown Artist"),
                "album" to (currentSong.album ?: "Unknown Album"),
                "albumId" to currentSong.albumId.toString()
            )
            editor.putString(KEY_LAST_SONG_DETAILS, gson.toJson(lastSongDetails))
        }

        editor.apply()
    }

    /**
     * NEW: Enhanced queue saving with better validation - FIXED FOR ANDROID 14+
     */
    fun savePlaybackStateWithQueueValidation(
        context: Context,
        currentSongId: Long?,
        seekPosition: Int,
        repeatMode: MusicService.RepeatMode,
        isShuffleMode: Boolean,
        queueSongs: List<Song>,
        currentQueuePosition: Int,
        originalQueueSongs: List<Song>
    ) {
        try {
            // Validate queue data before saving - FIXED: Handle empty queues properly
            val validQueueSongs = if (queueSongs.isNotEmpty()) queueSongs else originalQueueSongs
            val validOriginalQueueSongs = if (originalQueueSongs.isNotEmpty()) originalQueueSongs else queueSongs

            // FIXED: Only validate queue position if queue is not empty
            val validQueuePosition = if (validQueueSongs.isNotEmpty()) {
                currentQueuePosition.coerceIn(0, validQueueSongs.size - 1)
            } else {
                0
            }

            Log.d("PreferenceManager", "Saving validated queue - Current: ${validQueueSongs.size}, Original: ${validOriginalQueueSongs.size}, Position: $validQueuePosition")

            savePlaybackState(
                context = context,
                currentSongId = currentSongId,
                seekPosition = seekPosition,
                repeatMode = repeatMode,
                isShuffleMode = isShuffleMode,
                queueSongs = validQueueSongs,
                currentQueuePosition = validQueuePosition,
                originalQueueSongs = validOriginalQueueSongs
            )
        } catch (e: Exception) {
            Log.e("PreferenceManager", "Error saving playback state with validation", e)
            // Fallback: Save without validation to prevent crashes
            savePlaybackState(
                context = context,
                currentSongId = currentSongId,
                seekPosition = seekPosition,
                repeatMode = repeatMode,
                isShuffleMode = isShuffleMode,
                queueSongs = queueSongs,
                currentQueuePosition = 0,
                originalQueueSongs = originalQueueSongs
            )
        }
    }

    /**
     * Loads the saved playback state
     */
    fun loadPlaybackState(context: Context): PlaybackState? {
        val prefs = getPreferences(context)
        val lastSongId = prefs.getLong(KEY_LAST_PLAYED_SONG_ID, -1L)

        if (lastSongId == -1L) {
            Log.d("PreferenceManager", "No saved playback state found")
            return null // No saved state
        }

        val lastSeekPosition = prefs.getInt(KEY_LAST_SEEKBAR_POSITION, 0)
        val repeatMode = prefs.getInt(KEY_REPEAT_MODE, MusicService.RepeatMode.ALL.ordinal)
        val isShuffleMode = prefs.getBoolean(KEY_SHUFFLE_MODE, false)
        val currentQueuePosition = prefs.getInt(KEY_CURRENT_QUEUE_POSITION, 0)

        Log.d("PreferenceManager", "Loading playback state: songId=$lastSongId, seekPos=$lastSeekPosition, queuePos=$currentQueuePosition")

        val queueSongIds = try {
            val queueJson = prefs.getString(KEY_QUEUE_SONGS, null)
            if (queueJson != null) {
                val type = object : TypeToken<List<Long>>() {}.type
                Gson().fromJson<List<Long>>(queueJson, type) ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.e("PreferenceManager", "Error loading queue songs", e)
            emptyList()
        }

        val originalQueueSongIds = try {
            val originalQueueJson = prefs.getString(KEY_ORIGINAL_QUEUE_SONGS, null)
            if (originalQueueJson != null) {
                val type = object : TypeToken<List<Long>>() {}.type
                Gson().fromJson<List<Long>>(originalQueueJson, type) ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.e("PreferenceManager", "Error loading original queue songs", e)
            emptyList()
        }

        val songsHashMap = try {
            val hashMapJson = prefs.getString(KEY_QUEUE_HASHMAP, null)
            if (hashMapJson != null) {
                val type = object : TypeToken<Map<String, Map<String, String>>>() {}.type
                Gson().fromJson<Map<String, Map<String, String>>>(hashMapJson, type) ?: emptyMap()
            } else emptyMap()
        } catch (e: Exception) {
            Log.e("PreferenceManager", "Error loading songs hashmap", e)
            emptyMap()
        }

        val lastSongDetails = try {
            val detailsJson = prefs.getString(KEY_LAST_SONG_DETAILS, null)
            if (detailsJson != null) {
                val type = object : TypeToken<Map<String, String>>() {}.type
                Gson().fromJson<Map<String, String>>(detailsJson, type) ?: emptyMap()
            } else emptyMap()
        } catch (e: Exception) {
            Log.e("PreferenceManager", "Error loading last song details", e)
            emptyMap()
        }

        Log.d("PreferenceManager", "Loaded state: queueSize=${queueSongIds.size}, originalQueueSize=${originalQueueSongIds.size}")

        return PlaybackState(
            lastPlayedSongId = if (lastSongId != -1L) lastSongId else null,
            lastSeekPosition = lastSeekPosition,
            repeatMode = repeatMode,
            isShuffleMode = isShuffleMode,
            queueSongIds = queueSongIds,
            originalQueueSongIds = originalQueueSongIds,
            currentQueuePosition = currentQueuePosition,
            songsHashMap = songsHashMap,
            lastSongDetails = lastSongDetails
        )
    }

    fun getLastSongDetails(context: Context): Map<String, String> {
        return try {
            val detailsJson = getPreferences(context).getString(KEY_LAST_SONG_DETAILS, null)
            if (detailsJson != null) {
                val type = object : TypeToken<Map<String, String>>() {}.type
                Gson().fromJson<Map<String, String>>(detailsJson, type) ?: emptyMap()
            } else emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Clears the saved playback state
     */
    fun clearPlaybackState(context: Context) {
        val editor = getPreferences(context).edit()
        editor.remove(KEY_LAST_PLAYED_SONG_ID)
        editor.remove(KEY_LAST_SEEKBAR_POSITION)
        editor.remove(KEY_REPEAT_MODE)
        editor.remove(KEY_SHUFFLE_MODE)
        editor.remove(KEY_QUEUE_SONGS)
        editor.remove(KEY_ORIGINAL_QUEUE_SONGS)
        editor.remove(KEY_CURRENT_QUEUE_POSITION)
        editor.remove(KEY_QUEUE_HASHMAP)
        editor.remove(KEY_LAST_SONG_DETAILS)
        editor.apply()
    }

    /**
     * Data class for playback state
     */
    data class PlaybackState(
        val lastPlayedSongId: Long?,
        val lastSeekPosition: Int,
        val repeatMode: Int,
        val isShuffleMode: Boolean,
        val queueSongIds: List<Long>,
        val originalQueueSongIds: List<Long>,
        val currentQueuePosition: Int,
        val songsHashMap: Map<String, Map<String, String>>, // songId -> song details
        val lastSongDetails: Map<String, String>
    )

    //
    // NEW: Song Cache Management - OPTIMIZED WITH HASHMAP
    //

    /**
     * Marks the song cache as dirty, forcing a reload next time
     */
    fun markCacheDirty() {
        isCacheDirty = true
        cachedSongsMap = emptyMap()
    }

    /**
     * Gets cached songs or loads them if cache is dirty
     */
    suspend fun getCachedSongs(context: Context): List<Song> {
        return if (!isCacheDirty && cachedSongsMap.isNotEmpty()) {
            cachedSongsMap.values.toList()
        } else {
            // Load songs in background and cache them
            val songs = loadSongsInBackground(context)
            cachedSongsMap = songs.associateBy { it.id }
            isCacheDirty = false
            songs
        }
    }

    /**
     * Gets cached songs map for fast lookup - NEW METHOD
     */
    suspend fun getCachedSongsMap(context: Context): Map<Long, Song> {
        if (isCacheDirty || cachedSongsMap.isEmpty()) {
            getCachedSongs(context) // This will update the map
        }
        return cachedSongsMap
    }

    /**
     * Updates cache with specific songs - NEW METHOD for queue optimization
     */
    private fun updateCacheWithSongs(songs: List<Song>) {
        val updatedMap = cachedSongsMap.toMutableMap()
        songs.forEach { song ->
            updatedMap[song.id] = song
        }
        cachedSongsMap = updatedMap
    }

    /**
     * Loads songs in background (simulated - replace with your actual song loading)
     */
    private suspend fun loadSongsInBackground(context: Context): List<Song> {
        // This should be replaced with your actual song loading logic
        // For now, returning empty list as placeholder
        return emptyList()
    }

    /**
     * Gets songs for queue by matching IDs with cached songs - OPTIMIZED VERSION
     */
    suspend fun getSongsForQueue(context: Context, songIds: List<Long>): List<Song> {
        val cachedSongsMap = getCachedSongsMap(context)

        // Use HashMap for O(1) lookups instead of O(n) searches
        return songIds.mapNotNull { id ->
            cachedSongsMap[id]
        }
    }

    /**
     * FAST QUEUE LOADING - NEW METHOD: Direct loading without full cache
     */
    suspend fun getSongsForQueueFast(context: Context, songIds: List<Long>): List<Song> {
        if (songIds.isEmpty()) return emptyList()

        // Use SongCacheManager for fast lookups
        return withContext(Dispatchers.IO) {
            songIds.mapNotNull { id ->
                SongCacheManager.getSongById(id)
            }
        }
    }

    /**
     * Load specific songs by IDs - NEW METHOD for efficient loading
     */
    private suspend fun loadSpecificSongs(context: Context, songIds: List<Long>): List<Song> {
        // Use SongCacheManager for efficient loading
        return withContext(Dispatchers.IO) {
            songIds.mapNotNull { id ->
                SongCacheManager.getSongById(id)
            }
        }
    }

    fun init(activity: MainActivity) {}
}