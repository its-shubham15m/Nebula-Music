package com.shubhamgupta.nebula_player.viewmodel

import android.app.Application
import android.util.Log
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

class OrbitViewModel(application: Application) : AndroidViewModel(application) {

    // --- STATE DATA ---
    private val _greeting = MutableLiveData<String>()
    val greeting: LiveData<String> = _greeting

    private val _timeWarpData = MutableLiveData<List<Any>>() // Songs (Quick Plays)
    val timeWarpData: LiveData<List<Any>> = _timeWarpData

    private val _aiPlaylists = MutableLiveData<List<OrbitCard>>() // AI Generated Playlists
    val aiPlaylists: LiveData<List<OrbitCard>> = _aiPlaylists

    private val _artistData = MutableLiveData<List<ArtistCard>>() // Artist Data
    val artistData: LiveData<List<ArtistCard>> = _artistData

    private val _videosData = MutableLiveData<List<Video>>()
    val videosData: LiveData<List<Video>> = _videosData

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    // Flag to prevent auto-refresh on back navigation
    var isDataLoaded = false

    // --- DETAILED PLAYLIST DATA ---
    private val _selectedPlaylistSongs = MutableLiveData<List<Song>>()
    val selectedPlaylistSongs: LiveData<List<Song>> = _selectedPlaylistSongs

    // Recommendations List
    private val _recommendedSongs = MutableLiveData<List<Song>>()
    val recommendedSongs: LiveData<List<Song>> = _recommendedSongs

    // --- ARTIST DETAIL DATA ---
    private val _selectedArtistImage = MutableLiveData<String>()
    val selectedArtistImage: LiveData<String> = _selectedArtistImage

    // --- CACHING VARIABLES ---
    private var lastLoadedPlaylistTitle: String = ""

    // --- AI CONFIG ---
    private fun getApiKey(): String {
        return PreferenceManager.getGeminiApiKey(getApplication()) ?: ""
    }

    private fun getGenerativeModel(): GenerativeModel {
        return GenerativeModel(
            modelName = "gemini-2.0-flash",
            apiKey = getApiKey()
        )
    }

    data class OrbitCard(
        val id: String,
        val title: String,
        val tagline: String,
        val imageName: String,
        val type: String,
        val queryMood: String = "",
        val cachedSongIds: List<Long> = emptyList()
    )

    data class ArtistCard(
        val name: String,
        val songCount: Int,
        var imageUrl: String = ""
    )

    fun loadOrbitData(forceRefresh: Boolean = false) {
        if (isDataLoaded && !forceRefresh) return

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

            // 3. Extract Artists
            val sortedArtists = extractArtistsFromSongs(allSongs)
            val topArtists = sortedArtists.take(15).shuffled()
            _artistData.postValue(topArtists)

            // 4. Videos
            _videosData.postValue(allVideos.shuffled().take(15))

            // 5. AI Playlists Cards
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

            // 6. Fetch Artist Images (Using Deezer API now to get FACES not COVERS)
            val artistsWithImages = withContext(Dispatchers.IO) {
                topArtists.map { artist ->
                    async {
                        val url = fetchBestArtistImage(artist.name)
                        artist.copy(imageUrl = url)
                    }
                }.awaitAll()
            }
            _artistData.postValue(artistsWithImages)

            isDataLoaded = true
            _isLoading.postValue(false)
        }
    }

    private fun extractArtistsFromSongs(songs: List<Song>): List<ArtistCard> {
        val artistMap = mutableMapOf<String, Int>()
        // Improved Regex: Keeps "Amitabh Bhattacharya" together, splits "Arijit Singh, Pritam"
        val regex = Regex("[,;|]\\s+|\\s+ft\\.?\\s+|\\s+feat\\.?\\s+|\\s+&\\s+", RegexOption.IGNORE_CASE)

        songs.forEach { song ->
            val rawArtist = song.artist ?: "Unknown"
            if (rawArtist != "Unknown" && rawArtist != "<unknown>") {
                val parts = rawArtist.split(regex)
                parts.forEach { part ->
                    val cleanName = part.trim()
                    // Filter out garbage data
                    if (cleanName.length > 2 && !cleanName.all { it.isDigit() } && !cleanName.contains("Unknown", true)) {
                        artistMap[cleanName] = artistMap.getOrDefault(cleanName, 0) + 1
                    }
                }
            }
        }
        return artistMap.map { ArtistCard(it.key, it.value) }.sortedByDescending { it.songCount }
    }

    // --- ARTIST FRAGMENT LOGIC ---

    fun loadArtistDetails(artistName: String) {
        _isLoading.postValue(true)
        _selectedArtistImage.postValue("")

        viewModelScope.launch(Dispatchers.IO) {
            val allSongs = SongRepository.getAllSongs(getApplication())
            val artistSongs = allSongs.filter { song ->
                val raw = song.artist ?: ""
                raw.contains(artistName, ignoreCase = true)
            }

            withContext(Dispatchers.Main) {
                _selectedPlaylistSongs.postValue(artistSongs)
            }

            val imageUrl = fetchBestArtistImage(artistName)
            withContext(Dispatchers.Main) {
                _selectedArtistImage.postValue(imageUrl)
                _isLoading.postValue(false)
            }
        }
    }

    /**
     * PRIMARY SOURCE: Deezer API
     * Why? It returns 'picture_xl' which is the Artist's profile photo (Face).
     * It does NOT return album covers.
     */
    private suspend fun fetchBestArtistImage(artistName: String): String {
        var url = fetchArtistImageFromDeezer(artistName)
        if (url.isEmpty()) {
            url = fetchImageFromBing(artistName) // Fallback
        }
        return url
    }

    private fun fetchArtistImageFromDeezer(artistName: String): String {
        return try {
            val encodedName = URLEncoder.encode(artistName, "UTF-8")
            val urlString = "https://api.deezer.com/search/artist?q=$encodedName&limit=1"

            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000

            val inputStream = connection.inputStream
            val response = inputStream.bufferedReader().use { it.readText() }

            val jsonObject = JSONObject(response)
            val dataArray = jsonObject.optJSONArray("data")

            if (dataArray != null && dataArray.length() > 0) {
                val artistObj = dataArray.getJSONObject(0)
                // 'picture_xl' is the high-res artist photo
                return artistObj.optString("picture_xl", artistObj.optString("picture_medium"))
            }
            ""
        } catch (e: Exception) {
            Log.e("OrbitImage", "Deezer API failed: ${e.message}")
            ""
        }
    }

    private fun fetchImageFromBing(artistName: String): String {
        return try {
            // Updated query to strictly ask for 'face' or 'photoshoot'
            val query = "$artistName singer official photoshoot face"
            val url = "https://www.bing.com/images/search?q=$query&first=1"

            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.0.0 Safari/537.36")
                .timeout(5000)
                .get()

            // Robust selector for Bing Images
            val element = doc.select("img.mimg").first()
                ?: doc.select("div.img_cont img").first()

            var src = element?.attr("src") ?: element?.attr("data-src") ?: ""
            if (src.startsWith("//")) {
                src = "https:$src"
            }
            src
        } catch (e: Exception) {
            Log.e("OrbitImage", "Bing fetch failed: ${e.message}")
            ""
        }
    }

    // --- PLAYLIST LOGIC ---

    fun loadPlaylistDetails(mood: String, title: String, forceRefresh: Boolean = false) {
        if (!forceRefresh && title == lastLoadedPlaylistTitle && _selectedPlaylistSongs.value?.isNotEmpty() == true) {
            return
        }

        lastLoadedPlaylistTitle = title
        _isLoading.postValue(true)

        getOrGeneratePlaylist(mood, title, forceRefresh) { songs ->
            _selectedPlaylistSongs.postValue(songs)

            viewModelScope.launch(Dispatchers.IO) {
                val allSongs = SongRepository.getAllSongs(getApplication())
                val playlistIds = songs.map { it.id }.toSet()
                val playlistArtists = songs.mapNotNull { it.artist }.distinct()

                val similarSongs = allSongs.filter {
                    !playlistIds.contains(it.id) && playlistArtists.any { artist -> it.artist?.contains(artist, true) == true }
                }

                val recommendations = if (similarSongs.size >= 10) {
                    similarSongs.shuffled().take(10)
                } else {
                    val others = allSongs.filter { !playlistIds.contains(it.id) && !similarSongs.contains(it) }.shuffled()
                    (similarSongs + others).take(10)
                }

                withContext(Dispatchers.Main) {
                    _recommendedSongs.postValue(recommendations)
                    _isLoading.postValue(false)
                }
            }
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
                    if (songs.size > 10) {
                        withContext(Dispatchers.Main) { callback(songs) }
                        return@launch
                    }
                }
            }

            val allSongs = SongRepository.getAllSongs(context)
            val simplifiedSongList = allSongs.take(600).joinToString("\n") { "${it.id}|${it.title}|${it.artist}" }

            val currentKey = getApiKey()
            if (currentKey.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Please set Gemini API Key in Settings", Toast.LENGTH_LONG).show()
                    callback(fallbackKeywordSearch(allSongs, mood))
                }
                return@launch
            }

            // Spotify-Style Prompt
            val prompt = """
                You are a senior music curator at Spotify. Create a playlist for the mood: '$mood'.
                Input: List of songs (ID|Title|Artist).
                Task: Select 30 songs that match the *vibe* and *emotion* of the mood.
                Guidelines:
                1. Match the 'feeling' (e.g., Sad = Slow, Acoustic; Party = Upbeat).
                2. Return ONLY a JSON array of Song IDs (Long values). Example: [101, 202, 303]
                
                Songs:
                $simplifiedSongList
            """.trimIndent()

            try {
                val model = getGenerativeModel()
                val response = model.generateContent(prompt)
                val responseText = response.text
                    ?.replace("```json", "")
                    ?.replace("```", "")
                    ?.trim()

                val type = object : TypeToken<List<Long>>() {}.type
                val ids: List<Long> = Gson().fromJson(responseText, type)
                val matchedSongs = ids.mapNotNull { id -> allSongs.find { it.id == id } }

                savePlaylistToCache(playlistTitle, ids)
                withContext(Dispatchers.Main) { callback(matchedSongs) }

            } catch (e: Exception) {
                Log.e("OrbitAI", "AI Gen Failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback(fallbackKeywordSearch(allSongs, mood))
                }
            }
        }
    }

    private fun fallbackKeywordSearch(allSongs: List<Song>, mood: String): List<Song> {
        val keywords = mood.split(", ", " ")
        val filtered = allSongs.filter { s ->
            keywords.any { k ->
                s.title.contains(k, true) || (s.artist?.contains(k, true) == true)
            }
        }
        return if (filtered.size > 5) filtered.take(40) else allSongs.shuffled().take(40)
    }

    fun getSimilarSongs(seedSong: Song, callback: (List<Song>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val allSongs = SongRepository.getAllSongs(getApplication())
            val similar = allSongs.filter {
                (it.artist == seedSong.artist && it.id != seedSong.id)
            }.shuffled().take(39).toMutableList()

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
        val userName = try { UserProfileManager.getUserName(getApplication()) } catch (e: Exception) { "User" }
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