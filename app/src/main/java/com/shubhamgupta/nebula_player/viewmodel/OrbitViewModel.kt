package com.shubhamgupta.nebula_player.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shubhamgupta.nebula_player.BuildConfig
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.models.Video
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.repository.VideoRepository
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.utils.UserProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class OrbitViewModel(application: Application) : AndroidViewModel(application) {

    // --- STATE DATA ---
    private val _greeting = MutableLiveData<String>()
    val greeting: LiveData<String> = _greeting

    private val _timeWarpData = MutableLiveData<List<Any>>() // Songs (Quick Plays)
    val timeWarpData: LiveData<List<Any>> = _timeWarpData

    private val _aiPlaylists = MutableLiveData<List<OrbitCard>>() // AI Generated Playlists
    val aiPlaylists: LiveData<List<OrbitCard>> = _aiPlaylists

    private val _videosData = MutableLiveData<List<Video>>()
    val videosData: LiveData<List<Video>> = _videosData

    private val _lastWatched = MutableLiveData<Video?>()
    val lastWatchedData: LiveData<Video?> = _lastWatched

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    // --- DETAILED PLAYLIST DATA ---
    private val _selectedPlaylistSongs = MutableLiveData<List<Song>>()
    val selectedPlaylistSongs: LiveData<List<Song>> = _selectedPlaylistSongs

    private val _recommendedArtists = MutableLiveData<List<String>>() // For the bottom of detail page
    val recommendedArtists: LiveData<List<String>> = _recommendedArtists

    // --- AI CONFIG ---
    private val GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY

    private val generativeModel by lazy {
        GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = GEMINI_API_KEY
        )
    }

    data class OrbitCard(
        val id: String,
        val title: String,
        val subtitle: String,
        val type: String, // "PLAYLIST_AI", "VIDEO", "SONG"
        val queryMood: String = "",
        val cachedSongIds: List<Long> = emptyList()
    )

    fun loadOrbitData() {
        updateGreeting()
        viewModelScope.launch {
            _isLoading.postValue(true)

            // 1. Load Local Content
            val allSongs = withContext(Dispatchers.IO) { SongRepository.getAllSongs(getApplication()) }
            val allVideos = withContext(Dispatchers.IO) { VideoRepository.getAllVideos(getApplication()) }

            // 2. Quick Picks (Time Warp)
            val favorites = allSongs.filter { it.isFavorite }.shuffled().take(10)
            val randomMix = allSongs.shuffled().take(10)
            _timeWarpData.postValue((favorites + randomMix).distinctBy { it.id }.take(15))

            // 3. AI Playlists Cards
            val suggestions = listOf(
                OrbitCard("ai_1", "Bollywood Mush", "Sentimental hits", "PLAYLIST_AI", "Romantic, Bollywood, Soft, Slow, Hindi"),
                OrbitCard("ai_2", "1AM Feels", "Late night vibes", "PLAYLIST_AI", "Sad, Lo-fi, Slow, Melancholic, Acoustic"),
                OrbitCard("ai_3", "Gym Grind", "High energy workout", "PLAYLIST_AI", "High tempo, Rap, Rock, EDM, Energy"),
                OrbitCard("ai_4", "Sunday Morning", "Relax and unwind", "PLAYLIST_AI", "Acoustic, Jazz, Soft Pop, Happy"),
                OrbitCard("ai_5", "Focus Flow", "Deep work session", "PLAYLIST_AI", "Instrumental, Classical, Ambient, No Lyrics")
            )
            _aiPlaylists.postValue(suggestions)

            // 4. Videos
            _videosData.postValue(allVideos.shuffled().take(15))

            // 5. Last Watched
            if (allVideos.isNotEmpty()) {
                _lastWatched.postValue(allVideos.first())
            }

            _isLoading.postValue(false)
        }
    }

    fun loadPlaylistDetails(mood: String, title: String, forceRefresh: Boolean = false) {
        _isLoading.postValue(true)
        getOrGeneratePlaylist(mood, title, forceRefresh) { songs ->
            _selectedPlaylistSongs.postValue(songs)

            // Generate dummy recommendations based on the song list artists
            val artists = songs.mapNotNull { it.artist }.distinct().shuffled().take(5)
            _recommendedArtists.postValue(artists)

            _isLoading.postValue(false)
        }
    }

    private fun getOrGeneratePlaylist(
        mood: String,
        playlistTitle: String,
        forceRefresh: Boolean,
        callback: (List<Song>) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()

            if (!forceRefresh) {
                val cachedIds = getCachedPlaylist(playlistTitle)
                if (cachedIds.isNotEmpty()) {
                    val songs = SongRepository.getAllSongs(context).filter { cachedIds.contains(it.id) }
                    if (songs.isNotEmpty()) {
                        withContext(Dispatchers.Main) { callback(songs) }
                        return@launch
                    }
                }
            }

            // AI Generation Logic
            val allSongs = SongRepository.getAllSongs(context)
            val simplifiedSongList = allSongs.take(300).joinToString("\n") { "${it.id}|${it.title}|${it.artist}" } // Limit for token size

            if (GEMINI_API_KEY.isEmpty() || simplifiedSongList.isEmpty()) {
                withContext(Dispatchers.Main) { callback(allSongs.shuffled().take(20)) }
                return@launch
            }

            val prompt = """
                Act as a DJ. I have a list of songs: 'ID|Title|Artist'.
                Select 15-20 songs matching mood: '$mood'.
                Return JSON array of IDs only.
                List: $simplifiedSongList
            """.trimIndent()

            try {
                val response = generativeModel.generateContent(prompt)
                val responseText = response.text?.replace("```json", "")?.replace("```", "")?.trim()
                val type = object : TypeToken<List<Long>>() {}.type
                val ids: List<Long> = Gson().fromJson(responseText, type)
                val matchedSongs = allSongs.filter { ids.contains(it.id) }

                savePlaylistToCache(playlistTitle, ids)
                withContext(Dispatchers.Main) { callback(matchedSongs) }
            } catch (e: Exception) {
                // Fallback
                val keywords = mood.split(", ")
                val fallback = allSongs.filter { s -> keywords.any { k -> s.title.contains(k, true) } }.take(20)
                withContext(Dispatchers.Main) { callback(if(fallback.isNotEmpty()) fallback else allSongs.shuffled().take(15)) }
            }
        }
    }

    fun getSimilarSongs(seedSong: Song, callback: (List<Song>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val allSongs = SongRepository.getAllSongs(getApplication())
            val similar = allSongs.filter {
                (it.artist == seedSong.artist && it.id != seedSong.id)
            }.shuffled().take(19).toMutableList()
            similar.add(0, seedSong)
            withContext(Dispatchers.Main) { callback(similar) }
        }
    }

    private fun updateGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeMsg = when (hour) {
            in 0..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..20 -> "Good Evening"
            else -> "Late Night"
        }

        // Fetch User Name
        val userName = try {
            UserProfileManager.getUserName(getApplication())
        } catch (e: Exception) { "User" }

        _greeting.postValue("$timeMsg, $userName")
    }

    private fun getCachedPlaylist(key: String): List<Long> {
        val json = PreferenceManager.getAiPlaylistJson(getApplication(), key)
        return if (json != null) Gson().fromJson(json, object : TypeToken<List<Long>>() {}.type) else emptyList()
    }

    private fun savePlaylistToCache(key: String, ids: List<Long>) {
        PreferenceManager.saveAiPlaylistJson(getApplication(), key, Gson().toJson(ids))
    }
}