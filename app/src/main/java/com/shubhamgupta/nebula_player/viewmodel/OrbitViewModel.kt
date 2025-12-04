package com.shubhamgupta.nebula_player.viewmodel

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    private fun getApiKey(): String {
        return PreferenceManager.getGeminiApiKey(getApplication()) ?: ""
    }

    private fun getGenerativeModel(): GenerativeModel {
        return GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = getApiKey()
        )
    }

    data class OrbitCard(
        val id: String,
        val title: String,
        val tagline: String, // New Tagline field
        val imageName: String, // Filename in assets/playlists/
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

            // 3. AI Playlists Cards (Updated based on Image)
            val suggestions = listOf(
                OrbitCard("ai_1", "Bollywood Mush", "Sentimental B-Town hits", "bollywood_mush.png", "PLAYLIST_AI", "Bollywood, Romantic, Arijit Singh, Shreya Ghoshal, Soft, Hindi, Love"),
                OrbitCard("ai_2", "1AM Feels", "For the late night thoughts", "1am_feels.png", "PLAYLIST_AI", "Sad, Lo-fi, Slow, Melancholic, Acoustic, Night, Lonely"),
                OrbitCard("ai_3", "POP Icons", "Global chart toppers", "pop_icons.png", "PLAYLIST_AI", "Pop, English, Taylor Swift, Justin Bieber, Weeknd, Upbeat, Hits"),
                OrbitCard("ai_4", "#GRWM", "Get Ready With Me vibe", "grwm.png", "PLAYLIST_AI", "Upbeat, Party, Bollywood Dance, Punjabi, Trendy, Fashion, Makeup"),
                OrbitCard("ai_5", "pov: you're in love", "Butterflies in your stomach", "pov_love.png", "PLAYLIST_AI", "Romantic, Acoustic, Sweet, Slow, Love Songs, Dreamy"),
                OrbitCard("ai_6", "Sad Melodies", "When the tears dry out", "sad_melodies.png", "PLAYLIST_AI", "Heartbreak, Sad, Piano, Emotional, Separation, Breakup"),
                OrbitCard("ai_7", "Workout Mornings", "Push your limits", "workout.png", "PLAYLIST_AI", "Gym, Phonk, EDM, Rock, High Energy, Motivation, Cardio"),
                OrbitCard("ai_8", "Old Classics", "Golden era nostalgia", "old_classics.png", "PLAYLIST_AI", "Retro, 90s, 80s, Kishore Kumar, Mohd Rafi, Classic, Hindi Old"),
                OrbitCard("ai_9", "New Releases", "Fresh out of the studio", "new_releases.png", "PLAYLIST_AI", "New, 2024, 2025, Trendy, Latest, Fresh, Viral")
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
                    // Only return cached if it has enough songs, otherwise regenerate
                    if (songs.size > 10) {
                        withContext(Dispatchers.Main) { callback(songs) }
                        return@launch
                    }
                }
            }

            // AI Generation Logic
            val allSongs = SongRepository.getAllSongs(context)
            // Increased token context limit
            val simplifiedSongList = allSongs.take(500).joinToString("\n") { "${it.id}|${it.title}|${it.artist}" }

            val currentKey = getApiKey()
            if (currentKey.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Please set Gemini API Key in Settings", Toast.LENGTH_LONG).show()
                    callback(allSongs.shuffled().take(40))
                }
                return@launch
            }

            // --- PROFESSIONAL PROMPT UPDATE (Updated for 40 songs) ---
            val prompt = """
                You are an expert music curator for a high-end streaming service. 
                Your task is to curate a highly cohesive playlist based on a specific mood.
                
                Input Mood/Theme: '$mood'
                
                I have provided a local library of songs below in the format: 'ID|Title|Artist'.
                
                Instructions:
                1. Select 40 to 50 songs from the list that BEST match the requested mood.
                2. Prioritize "Vibe Consistency" - ensure the songs flow well together.
                3. If the mood implies a specific genre (e.g., "Bollywood"), prioritize songs from that genre or artist.
                4. Return ONLY a raw JSON array of the song IDs (integers/longs).
                5. Do NOT include markdown formatting (like ```json), explanations, or song titles. Just the array.
                
                Local Library:
                $simplifiedSongList
            """.trimIndent()

            try {
                // Use dynamic generative model with user key
                val model = getGenerativeModel()
                val response = model.generateContent(prompt)
                val responseText = response.text?.replace("```json", "")?.replace("```", "")?.trim()
                val type = object : TypeToken<List<Long>>() {}.type
                val ids: List<Long> = Gson().fromJson(responseText, type)
                val matchedSongs = allSongs.filter { ids.contains(it.id) }

                savePlaylistToCache(playlistTitle, ids)
                withContext(Dispatchers.Main) { callback(matchedSongs) }
            } catch (e: Exception) {
                // Fallback: Smart local filtering if AI fails
                val keywords = mood.split(", ")
                val fallback = allSongs.filter { s ->
                    keywords.any { k ->
                        s.title.contains(k, true) || (s.artist?.contains(k, true) == true)
                    }
                }.take(40) // Increased fallback limit

                withContext(Dispatchers.Main) {
                    callback(if(fallback.isNotEmpty()) fallback else allSongs.shuffled().take(40))
                }
            }
        }
    }

    fun getSimilarSongs(seedSong: Song, callback: (List<Song>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val allSongs = SongRepository.getAllSongs(getApplication())
            val similar = allSongs.filter {
                (it.artist == seedSong.artist && it.id != seedSong.id)
            }.shuffled().take(39).toMutableList() // Increased to match new length preference
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