package com.shubhamgupta.nebula_music.fragments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_music.MainActivity
import com.shubhamgupta.nebula_music.R
import com.shubhamgupta.nebula_music.adapters.SongAdapter
import com.shubhamgupta.nebula_music.models.Song
import com.shubhamgupta.nebula_music.repository.SongRepository
import com.shubhamgupta.nebula_music.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SongsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingProgress: ProgressBar
    private val songList = mutableListOf<Song>()
    private var filteredSongList = mutableListOf<Song>()
    private var currentSortType = MainActivity.SortType.NAME_ASC
    private var currentQuery = ""
    private var isFirstLoad = true
    private val handler = Handler(Looper.getMainLooper())
    private var loadJob: Job? = null

    // Scroll state management
    private var scrollPosition = 0
    private var scrollOffset = 0

    private val searchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SEARCH_QUERY_CHANGED") {
                currentQuery = intent.getStringExtra("query") ?: ""
                filterSongs()
            }
        }
    }

    // Add this to SongsFragment.kt
    fun setScrollingEnabled(enabled: Boolean) {
        try {
            if (this::recyclerView.isInitialized) {
                recyclerView.isNestedScrollingEnabled = enabled
                recyclerView.isEnabled = enabled

                if (!enabled) {
                    recyclerView.setOnTouchListener { _, _ -> true } // Block touches
                } else {
                    recyclerView.setOnTouchListener(null) // Allow touches
                }
            }
        } catch (e: Exception) {
            Log.e("SongsFragment", "Error setting scrolling enabled: $enabled", e)
        }
    }

    // Save scroll state
    fun saveScrollState() {
        if (this::recyclerView.isInitialized) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.let {
                scrollPosition = it.findFirstVisibleItemPosition()
                val firstVisibleView = it.findViewByPosition(scrollPosition)
                scrollOffset = firstVisibleView?.top ?: 0
                Log.d("SongsFragment", "Saved scroll state: position=$scrollPosition, offset=$scrollOffset")
            }
        }
    }

    // Restore scroll state
    fun restoreScrollState() {
        if (this::recyclerView.isInitialized && scrollPosition > 0) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.let {
                handler.postDelayed({
                    it.scrollToPositionWithOffset(scrollPosition, scrollOffset)
                    Log.d("SongsFragment", "Restored scroll state: position=$scrollPosition, offset=$scrollOffset")
                }, 100)
            }
        }
    }

    private val sortReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SORT_SONGS") {
                val sortTypeOrdinal = intent.getIntExtra("sort_type", 0)
                val newSortType = MainActivity.SortType.entries.toTypedArray()[sortTypeOrdinal]
                Log.d("SongsFragment", "Sort broadcast received: $newSortType")
                if (newSortType != currentSortType) {
                    currentSortType = newSortType
                    PreferenceManager.saveSortPreference(requireContext(), "songs", currentSortType)
                    Log.d("SongsFragment", "Sort changed and saved to: $currentSortType")
                    // Force reload and sort
                    loadSongs()
                }
            }
        }
    }

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SONG_CHANGED", "PLAYBACK_STATE_CHANGED" -> {
                    updateAdapter()
                }
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "FORCE_REFRESH_SONGS") {
                Log.d("SongsFragment", "Force refresh received via broadcast")
                loadSongs()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Load saved sort preference with proper default
        currentSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "songs")
        Log.d("SongsFragment", "onCreate - Loaded sort: $currentSortType")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_songs_list, container, false)
        recyclerView = view.findViewById(R.id.songs_recycler_view)
        emptyView = view.findViewById(R.id.tv_empty_songs)
        loadingProgress = view.findViewById(R.id.loading_progress)
        recyclerView.layoutManager = LinearLayoutManager(context)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("SongsFragment", "onViewCreated - Current sort: $currentSortType")

        // Show loading immediately
        showLoading()

        // Auto-load immediately when view is created with proper sort
        handler.postDelayed({
            loadSongs()
        }, 100)
    }

    // In SongsFragment.kt, update the onResume method:

    override fun onResume() {
        super.onResume()
        Log.d("SongsFragment", "onResume - Current sort: $currentSortType")

        // Fix for Android 14+ - add RECEIVER_NOT_EXPORTED flag
        val searchFilter = IntentFilter("SEARCH_QUERY_CHANGED")
        val sortFilter = IntentFilter("SORT_SONGS")
        val refreshFilter = IntentFilter("FORCE_REFRESH_SONGS")
        val playbackFilter = IntentFilter().apply {
            addAction("SONG_CHANGED")
            addAction("PLAYBACK_STATE_CHANGED")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires explicit export flags
            requireActivity().registerReceiver(searchReceiver, searchFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(sortReceiver, sortFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(playbackReceiver, playbackFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // For older versions, use the old method
            requireActivity().registerReceiver(searchReceiver, searchFilter)
            requireActivity().registerReceiver(sortReceiver, sortFilter)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter)
            requireActivity().registerReceiver(playbackReceiver, playbackFilter)
        }

        // Rest of the method remains the same...
        val savedSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "songs")
        Log.d("SongsFragment", "onResume - Saved sort: $savedSortType, Current sort: $currentSortType")

        if (savedSortType != currentSortType) {
            currentSortType = savedSortType
            Log.d("SongsFragment", "Sort changed to: $currentSortType, reloading")
            loadSongs()
        } else if (!isFirstLoad) {
            Log.d("SongsFragment", "Refreshing data on resume")
            loadSongsPreserveState()
        } else {
            isFirstLoad = false
        }
    }

    override fun onPause() {
        super.onPause()
        requireActivity().unregisterReceiver(searchReceiver)
        requireActivity().unregisterReceiver(sortReceiver)
        requireActivity().unregisterReceiver(refreshReceiver)
        try {
            requireActivity().unregisterReceiver(playbackReceiver)
        } catch (e: Exception) {
            // Ignore if receiver was not registered
        }

        // Cancel any ongoing loading job
        loadJob?.cancel()

        // Save scroll state when pausing
        saveScrollState()
    }

    fun refreshData() {
        Log.d("SongsFragment", "refreshData - Manual refresh triggered")
        if (isVisible && !isRemoving) {
            loadSongs()
        }
    }

    fun refreshDataPreserveState() {
        Log.d("SongsFragment", "refreshDataPreserveState - Manual refresh with state preservation")
        if (isVisible && !isRemoving) {
            loadSongsPreserveState()
        }
    }

    private fun showLoading() {
        loadingProgress.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE
    }

    private fun hideLoading() {
        loadingProgress.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
    }

    private fun loadSongs() {
        Log.d("SongsFragment", "loadSongs - Starting to load songs with sort: $currentSortType")

        // Cancel any previous loading job
        loadJob?.cancel()

        // Show loading indicator
        showLoading()

        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                songList.clear()
                val allSongs = SongRepository.getAllSongs(requireContext())
                songList.addAll(allSongs)
                Log.d("SongsFragment", "Loaded ${songList.size} songs, now applying sort: $currentSortType")

                // Apply sort immediately after loading
                applyCurrentSort()
            } catch (e: Exception) {
                Log.e("SongsFragment", "Error loading songs", e)
                hideLoading()
                emptyView.visibility = View.VISIBLE
            }
        }
    }

    private fun loadSongsPreserveState() {
        Log.d("SongsFragment", "loadSongsPreserveState - Loading songs with state preservation")

        // Save current scroll state before refresh
        saveScrollState()

        // Cancel any previous loading job
        loadJob?.cancel()

        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                songList.clear()
                val allSongs = SongRepository.getAllSongs(requireContext())
                songList.addAll(allSongs)
                Log.d("SongsFragment", "Loaded ${songList.size} songs, now applying sort: $currentSortType")

                // Apply sort immediately after loading
                applyCurrentSortPreserveState()
            } catch (e: Exception) {
                Log.e("SongsFragment", "Error loading songs", e)
                hideLoading()
                emptyView.visibility = View.VISIBLE
            }
        }
    }

    private fun applyCurrentSort() {
        Log.d("SongsFragment", "applyCurrentSort - Applying: $currentSortType to ${songList.size} songs")

        when (currentSortType) {
            MainActivity.SortType.NAME_ASC -> {
                songList.sortBy { it.title.lowercase() }
                Log.d("SongsFragment", "Sorted by NAME_ASC")
            }
            MainActivity.SortType.NAME_DESC -> {
                songList.sortByDescending { it.title.lowercase() }
                Log.d("SongsFragment", "Sorted by NAME_DESC")
            }
            MainActivity.SortType.DATE_ADDED_ASC -> {
                songList.sortBy { it.dateAdded }
                Log.d("SongsFragment", "Sorted by DATE_ADDED_ASC")
                // Debug: Show first few songs' dates
                if (songList.isNotEmpty()) {
                    Log.d("SongsFragment", "First song date: ${songList.first().dateAdded}")
                    Log.d("SongsFragment", "Last song date: ${songList.last().dateAdded}")
                }
            }
            MainActivity.SortType.DATE_ADDED_DESC -> {
                songList.sortByDescending { it.dateAdded }
                Log.d("SongsFragment", "Sorted by DATE_ADDED_DESC")
                // Debug: Show first few songs' dates
                if (songList.isNotEmpty()) {
                    Log.d("SongsFragment", "First song date: ${songList.first().dateAdded}")
                    Log.d("SongsFragment", "Last song date: ${songList.last().dateAdded}")
                }
            }
            MainActivity.SortType.DURATION -> {
                songList.sortByDescending { it.duration }
                Log.d("SongsFragment", "Sorted by DURATION")
            }
        }

        filterSongs()
    }

    private fun applyCurrentSortPreserveState() {
        Log.d("SongsFragment", "applyCurrentSortPreserveState - Applying sort with state preservation")
        applyCurrentSort()

        // Restore scroll state after data is loaded and sorted
        handler.postDelayed({
            restoreScrollState()
        }, 200)
    }

    private fun filterSongs() {
        filteredSongList = if (currentQuery.isBlank()) {
            songList.toMutableList()
        } else {
            songList.filter { song ->
                song.title.contains(currentQuery, true) ||
                        song.artist?.contains(currentQuery, true) == true ||
                        song.album?.contains(currentQuery, true) == true
            }.toMutableList()
        }

        updateAdapter()
    }

    private fun updateAdapter() {
        val adapter = SongAdapter(
            songs = filteredSongList,
            onItemClick = { pos -> openNowPlaying(pos) },
            onMenuClick = { pos, menuItem -> handleMenuAction(pos, menuItem) },
            isSongFavorite = { songId -> PreferenceManager.isFavorite(requireContext(), songId) }
        )
        recyclerView.adapter = adapter

        // Hide loading and show appropriate view
        hideLoading()
        emptyView.visibility = if (filteredSongList.isEmpty()) View.VISIBLE else View.GONE

        Log.d("SongsFragment", "Adapter updated with ${filteredSongList.size} songs, sort: $currentSortType")

        // Debug: Show first few song titles to verify sort
        if (filteredSongList.isNotEmpty()) {
            val firstFew = filteredSongList.take(3).map { it.title }
            Log.d("SongsFragment", "First 3 songs: $firstFew")
        }
    }

    private fun handleMenuAction(position: Int, menuItem: String) {
        val song = filteredSongList[position]
        when (menuItem) {
            "play" -> openNowPlaying(position)
            "toggle_favorite" -> toggleFavorite(song)
        }
    }

    private fun toggleFavorite(song: Song) {
        if (PreferenceManager.isFavorite(requireContext(), song.id)) {
            PreferenceManager.removeFavorite(requireContext(), song.id)
        } else {
            PreferenceManager.addFavorite(requireContext(), song.id)
        }
        updateAdapter()
    }

    private fun openNowPlaying(position: Int) {
        val songToPlay = filteredSongList[position]
        PreferenceManager.addRecentSong(requireContext(), songToPlay.id)

        val service = (requireActivity() as MainActivity).getMusicService()
        service?.startPlayback(ArrayList(filteredSongList), position)

        (requireActivity() as MainActivity).navigateToNowPlaying()
    }
}