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
import com.shubhamgupta.nebula_music.adapters.ArtistAdapter
import com.shubhamgupta.nebula_music.models.Artist
import com.shubhamgupta.nebula_music.repository.SongRepository
import com.shubhamgupta.nebula_music.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ArtistsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingProgress: ProgressBar
    private val artistList = mutableListOf<Artist>()
    private var filteredArtistList = mutableListOf<Artist>()
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
                filterArtists()
            }
        }
    }

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
            Log.e("ArtistsFragment", "Error setting scrolling enabled: $enabled", e)
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
                Log.d("ArtistsFragment", "Saved scroll state: position=$scrollPosition, offset=$scrollOffset")
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
                    Log.d("ArtistsFragment", "Restored scroll state: position=$scrollPosition, offset=$scrollOffset")
                }, 100)
            }
        }
    }

    private val sortReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SORT_ARTISTS") {
                val sortTypeOrdinal = intent.getIntExtra("sort_type", 0)
                val newSortType = MainActivity.SortType.entries.toTypedArray()[sortTypeOrdinal]
                Log.d("ArtistsFragment", "Sort broadcast received: $newSortType")
                if (newSortType != currentSortType) {
                    currentSortType = newSortType
                    PreferenceManager.saveSortPreference(requireContext(), "artists", currentSortType)
                    Log.d("ArtistsFragment", "Sort changed and saved to: $currentSortType")
                    // Force reload and sort
                    loadArtists()
                }
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "FORCE_REFRESH_ARTISTS") {
                Log.d("ArtistsFragment", "Force refresh received via broadcast")
                loadArtists()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Load saved sort preference with proper default
        currentSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "artists")
        Log.d("ArtistsFragment", "onCreate - Loaded sort: $currentSortType")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_category_list, container, false)
        recyclerView = view.findViewById(R.id.category_recycler_view)
        emptyView = view.findViewById(R.id.tv_empty_category)
        loadingProgress = view.findViewById(R.id.loading_progress)
        recyclerView.layoutManager = LinearLayoutManager(context)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("ArtistsFragment", "onViewCreated - Current sort: $currentSortType")

        // Show loading immediately
        showLoading()

        // Auto-load immediately when view is created with proper sort
        handler.postDelayed({
            loadArtists()
        }, 100)
    }

    override fun onResume() {
        super.onResume()
        Log.d("ArtistsFragment", "onResume - Current sort: $currentSortType")

        // Fix for Android 14+ - add RECEIVER_NOT_EXPORTED flag
        val searchFilter = IntentFilter("SEARCH_QUERY_CHANGED")
        val sortFilter = IntentFilter("SORT_ARTISTS")
        val refreshFilter = IntentFilter("FORCE_REFRESH_ARTISTS")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires explicit export flags
            requireActivity().registerReceiver(searchReceiver, searchFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(sortReceiver, sortFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // For older versions, use the old method
            requireActivity().registerReceiver(searchReceiver, searchFilter)
            requireActivity().registerReceiver(sortReceiver, sortFilter)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter)
        }

        // Check if sort preference changed and reload if needed
        val savedSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "artists")
        Log.d("ArtistsFragment", "onResume - Saved sort: $savedSortType, Current sort: $currentSortType")

        if (savedSortType != currentSortType) {
            currentSortType = savedSortType
            Log.d("ArtistsFragment", "Sort changed to: $currentSortType, reloading")
            loadArtists()
        } else if (!isFirstLoad) {
            // Refresh data even if sort didn't change
            Log.d("ArtistsFragment", "Refreshing data on resume")
            loadArtistsPreserveState()
        } else {
            isFirstLoad = false
        }
    }

    override fun onPause() {
        super.onPause()
        requireActivity().unregisterReceiver(searchReceiver)
        requireActivity().unregisterReceiver(sortReceiver)
        requireActivity().unregisterReceiver(refreshReceiver)

        // Cancel any ongoing loading job
        loadJob?.cancel()

        // Save scroll state when pausing
        saveScrollState()
    }

    fun refreshData() {
        Log.d("ArtistsFragment", "refreshData - Manual refresh triggered")
        if (isVisible && !isRemoving) {
            loadArtists()
        }
    }

    fun refreshDataPreserveState() {
        Log.d("ArtistsFragment", "refreshDataPreserveState - Manual refresh with state preservation")
        if (isVisible && !isRemoving) {
            loadArtistsPreserveState()
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

    private fun loadArtists() {
        Log.d("ArtistsFragment", "loadArtists - Starting to load artists with sort: $currentSortType")

        // Cancel any previous loading job
        loadJob?.cancel()

        // Show loading indicator
        showLoading()

        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                artistList.clear()
                val songs = SongRepository.getAllSongs(requireContext())
                val artistMap = mutableMapOf<String, Artist>()

                songs.forEach { song ->
                    val artistName = song.artist ?: "Unknown Artist"
                    if (!artistMap.containsKey(artistName)) {
                        artistMap[artistName] = Artist(
                            name = artistName,
                            songCount = 0,
                            songs = mutableListOf()
                        )
                    }
                    artistMap[artistName]?.let { artist ->
                        artist.songCount++
                        artist.songs.add(song)
                    }
                }

                artistList.addAll(artistMap.values)
                Log.d("ArtistsFragment", "Loaded ${artistList.size} artists, now applying sort: $currentSortType")

                // Apply sort immediately after loading
                applyCurrentSort()
            } catch (e: Exception) {
                Log.e("ArtistsFragment", "Error loading artists", e)
                hideLoading()
                emptyView.visibility = View.VISIBLE
            }
        }
    }

    private fun loadArtistsPreserveState() {
        Log.d("ArtistsFragment", "loadArtistsPreserveState - Loading artists with state preservation")

        // Save current scroll state before refresh
        saveScrollState()

        // Cancel any previous loading job
        loadJob?.cancel()

        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                artistList.clear()
                val songs = SongRepository.getAllSongs(requireContext())
                val artistMap = mutableMapOf<String, Artist>()

                songs.forEach { song ->
                    val artistName = song.artist ?: "Unknown Artist"
                    if (!artistMap.containsKey(artistName)) {
                        artistMap[artistName] = Artist(
                            name = artistName,
                            songCount = 0,
                            songs = mutableListOf()
                        )
                    }
                    artistMap[artistName]?.let { artist ->
                        artist.songCount++
                        artist.songs.add(song)
                    }
                }

                artistList.addAll(artistMap.values)
                Log.d("ArtistsFragment", "Loaded ${artistList.size} artists, now applying sort: $currentSortType")

                // Apply sort immediately after loading
                applyCurrentSortPreserveState()
            } catch (e: Exception) {
                Log.e("ArtistsFragment", "Error loading artists", e)
                hideLoading()
                emptyView.visibility = View.VISIBLE
            }
        }
    }

    private fun applyCurrentSort() {
        Log.d("ArtistsFragment", "applyCurrentSort - Applying: $currentSortType to ${artistList.size} artists")

        when (currentSortType) {
            MainActivity.SortType.NAME_ASC -> {
                artistList.sortBy { it.name.lowercase() }
                Log.d("ArtistsFragment", "Sorted by NAME_ASC")
            }
            MainActivity.SortType.NAME_DESC -> {
                artistList.sortByDescending { it.name.lowercase() }
                Log.d("ArtistsFragment", "Sorted by NAME_DESC")
            }
            else -> {
                artistList.sortBy { it.name.lowercase() }
                Log.d("ArtistsFragment", "Sorted by default NAME_ASC")
            }
        }

        // Debug: Show first few artist names to verify sort
        if (artistList.isNotEmpty()) {
            val firstFew = artistList.take(3).map { it.name }
            Log.d("ArtistsFragment", "First 3 artists: $firstFew")
        }

        filterArtists()
    }

    private fun applyCurrentSortPreserveState() {
        Log.d("ArtistsFragment", "applyCurrentSortPreserveState - Applying sort with state preservation")
        applyCurrentSort()

        // Restore scroll state after data is loaded and sorted
        handler.postDelayed({
            restoreScrollState()
        }, 200)
    }

    private fun filterArtists() {
        filteredArtistList = if (currentQuery.isBlank()) {
            artistList.toMutableList()
        } else {
            artistList.filter { artist ->
                artist.name.contains(currentQuery, true)
            }.toMutableList()
        }

        updateAdapter()
    }

    private fun updateAdapter() {
        val adapter = ArtistAdapter(
            artists = filteredArtistList,
            onArtistClick = { position -> openArtistSongs(position) }
        )
        recyclerView.adapter = adapter

        // Hide loading and show appropriate view
        hideLoading()
        emptyView.visibility = if (filteredArtistList.isEmpty()) View.VISIBLE else View.GONE
        Log.d("ArtistsFragment", "Adapter updated with ${filteredArtistList.size} artists, sort: $currentSortType")
    }

    private fun openArtistSongs(position: Int) {
        val artist = filteredArtistList[position]
        val fragment = ArtistSongsFragment.newInstance(artist)
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack("artist_songs")
            .commit()
    }
}