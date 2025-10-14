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
import com.shubhamgupta.nebula_music.adapters.AlbumAdapter
import com.shubhamgupta.nebula_music.models.Album
import com.shubhamgupta.nebula_music.repository.SongRepository
import com.shubhamgupta.nebula_music.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AlbumsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingProgress: ProgressBar
    private val albumList = mutableListOf<Album>()
    private var filteredAlbumList = mutableListOf<Album>()
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
                filterAlbums()
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
            Log.e("AlbumsFragment", "Error setting scrolling enabled: $enabled", e)
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
                Log.d("AlbumsFragment", "Saved scroll state: position=$scrollPosition, offset=$scrollOffset")
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
                    Log.d("AlbumsFragment", "Restored scroll state: position=$scrollPosition, offset=$scrollOffset")
                }, 100)
            }
        }
    }

    private val sortReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SORT_ALBUMS") {
                val sortTypeOrdinal = intent.getIntExtra("sort_type", 0)
                val newSortType = MainActivity.SortType.entries.toTypedArray()[sortTypeOrdinal]
                Log.d("AlbumsFragment", "Sort broadcast received: $newSortType")
                if (newSortType != currentSortType) {
                    currentSortType = newSortType
                    PreferenceManager.saveSortPreference(requireContext(), "albums", currentSortType)
                    Log.d("AlbumsFragment", "Sort changed and saved to: $currentSortType")
                    // Force reload and sort
                    loadAlbums()
                }
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "FORCE_REFRESH_ALBUMS") {
                Log.d("AlbumsFragment", "Force refresh received via broadcast")
                loadAlbums()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Load saved sort preference with proper default
        currentSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "albums")
        Log.d("AlbumsFragment", "onCreate - Loaded sort: $currentSortType")
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
        Log.d("AlbumsFragment", "onViewCreated - Current sort: $currentSortType")

        // Show loading immediately
        showLoading()

        // Auto-load immediately when view is created with proper sort
        handler.postDelayed({
            loadAlbums()
        }, 100)
    }

    override fun onResume() {
        super.onResume()
        Log.d("AlbumsFragment", "onResume - Current sort: $currentSortType")

        // Fix for Android 14+ - add RECEIVER_NOT_EXPORTED flag
        val searchFilter = IntentFilter("SEARCH_QUERY_CHANGED")
        val sortFilter = IntentFilter("SORT_ALBUMS")
        val refreshFilter = IntentFilter("FORCE_REFRESH_ALBUMS")

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
        val savedSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "albums")
        Log.d("AlbumsFragment", "onResume - Saved sort: $savedSortType, Current sort: $currentSortType")

        if (savedSortType != currentSortType) {
            currentSortType = savedSortType
            Log.d("AlbumsFragment", "Sort changed to: $currentSortType, reloading")
            loadAlbums()
        } else if (!isFirstLoad) {
            // Refresh data even if sort didn't change
            Log.d("AlbumsFragment", "Refreshing data on resume")
            loadAlbumsPreserveState()
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
        Log.d("AlbumsFragment", "refreshData - Manual refresh triggered")
        if (isVisible && !isRemoving) {
            loadAlbums()
        }
    }

    fun refreshDataPreserveState() {
        Log.d("AlbumsFragment", "refreshDataPreserveState - Manual refresh with state preservation")
        if (isVisible && !isRemoving) {
            loadAlbumsPreserveState()
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

    private fun loadAlbums() {
        Log.d("AlbumsFragment", "loadAlbums - Starting to load albums with sort: $currentSortType")

        // Cancel any previous loading job
        loadJob?.cancel()

        // Show loading indicator
        showLoading()

        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                albumList.clear()
                val songs = SongRepository.getAllSongs(requireContext())
                val albumMap = mutableMapOf<String, Album>()

                songs.forEach { song ->
                    val albumName = song.album ?: "Unknown Album"
                    if (!albumMap.containsKey(albumName)) {
                        albumMap[albumName] = Album(
                            name = albumName,
                            artist = song.artist ?: "Unknown Artist",
                            songCount = 0,
                            songs = mutableListOf(),
                            albumId = song.albumId
                        )
                    }
                    albumMap[albumName]?.let { album ->
                        album.songCount++
                        album.songs.add(song)
                    }
                }

                albumList.addAll(albumMap.values)
                Log.d("AlbumsFragment", "Loaded ${albumList.size} albums, now applying sort: $currentSortType")

                // Apply sort immediately after loading
                applyCurrentSort()
            } catch (e: Exception) {
                Log.e("AlbumsFragment", "Error loading albums", e)
                hideLoading()
                emptyView.visibility = View.VISIBLE
            }
        }
    }

    private fun loadAlbumsPreserveState() {
        Log.d("AlbumsFragment", "loadAlbumsPreserveState - Loading albums with state preservation")

        // Save current scroll state before refresh
        saveScrollState()

        // Cancel any previous loading job
        loadJob?.cancel()

        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                albumList.clear()
                val songs = SongRepository.getAllSongs(requireContext())
                val albumMap = mutableMapOf<String, Album>()

                songs.forEach { song ->
                    val albumName = song.album ?: "Unknown Album"
                    if (!albumMap.containsKey(albumName)) {
                        albumMap[albumName] = Album(
                            name = albumName,
                            artist = song.artist ?: "Unknown Artist",
                            songCount = 0,
                            songs = mutableListOf(),
                            albumId = song.albumId
                        )
                    }
                    albumMap[albumName]?.let { album ->
                        album.songCount++
                        album.songs.add(song)
                    }
                }

                albumList.addAll(albumMap.values)
                Log.d("AlbumsFragment", "Loaded ${albumList.size} albums, now applying sort: $currentSortType")

                // Apply sort immediately after loading
                applyCurrentSortPreserveState()
            } catch (e: Exception) {
                Log.e("AlbumsFragment", "Error loading albums", e)
                hideLoading()
                emptyView.visibility = View.VISIBLE
            }
        }
    }

    private fun applyCurrentSort() {
        Log.d("AlbumsFragment", "applyCurrentSort - Applying: $currentSortType to ${albumList.size} albums")

        when (currentSortType) {
            MainActivity.SortType.NAME_ASC -> {
                albumList.sortBy { it.name.lowercase() }
                Log.d("AlbumsFragment", "Sorted by NAME_ASC")
            }
            MainActivity.SortType.NAME_DESC -> {
                albumList.sortByDescending { it.name.lowercase() }
                Log.d("AlbumsFragment", "Sorted by NAME_DESC")
            }
            else -> {
                albumList.sortBy { it.name.lowercase() }
                Log.d("AlbumsFragment", "Sorted by default NAME_ASC")
            }
        }

        // Debug: Show first few album names to verify sort
        if (albumList.isNotEmpty()) {
            val firstFew = albumList.take(3).map { it.name }
            Log.d("AlbumsFragment", "First 3 albums: $firstFew")
        }

        filterAlbums()
    }

    private fun applyCurrentSortPreserveState() {
        Log.d("AlbumsFragment", "applyCurrentSortPreserveState - Applying sort with state preservation")
        applyCurrentSort()

        // Restore scroll state after data is loaded and sorted
        handler.postDelayed({
            restoreScrollState()
        }, 200)
    }

    private fun filterAlbums() {
        filteredAlbumList = if (currentQuery.isBlank()) {
            albumList.toMutableList()
        } else {
            albumList.filter { album ->
                album.name.contains(currentQuery, true) ||
                        album.artist.contains(currentQuery, true)
            }.toMutableList()
        }

        updateAdapter()
    }

    private fun updateAdapter() {
        val adapter = AlbumAdapter(
            albums = filteredAlbumList,
            onAlbumClick = { position -> openAlbumSongs(position) }
        )
        recyclerView.adapter = adapter

        // Hide loading and show appropriate view
        hideLoading()
        emptyView.visibility = if (filteredAlbumList.isEmpty()) View.VISIBLE else View.GONE
        Log.d("AlbumsFragment", "Adapter updated with ${filteredAlbumList.size} albums, sort: $currentSortType")
    }

    private fun openAlbumSongs(position: Int) {
        val album = filteredAlbumList[position]
        val fragment = AlbumSongsFragment.newInstance(album)
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack("album_songs")
            .commit()
    }
}