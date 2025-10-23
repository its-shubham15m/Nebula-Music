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
import com.shubhamgupta.nebula_music.adapters.GenreAdapter
import com.shubhamgupta.nebula_music.models.Genre
import com.shubhamgupta.nebula_music.models.Song
import com.shubhamgupta.nebula_music.repository.SongRepository
import com.shubhamgupta.nebula_music.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GenresFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingProgress: ProgressBar
    private val genreList = mutableListOf<Genre>()
    private var filteredGenreList = mutableListOf<Genre>()
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
                filterGenres()
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
            Log.e("GenresFragment", "Error setting scrolling enabled: $enabled", e)
        }
    }

    fun saveScrollState() {
        if (this::recyclerView.isInitialized) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.let {
                scrollPosition = it.findFirstVisibleItemPosition()
                val firstVisibleView = it.findViewByPosition(scrollPosition)
                scrollOffset = firstVisibleView?.top ?: 0
                Log.d("GenresFragment", "Saved scroll state: position=$scrollPosition, offset=$scrollOffset")
            }
        }
    }

    fun restoreScrollState() {
        if (this::recyclerView.isInitialized && scrollPosition > 0) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.let {
                handler.postDelayed({
                    it.scrollToPositionWithOffset(scrollPosition, scrollOffset)
                    Log.d("GenresFragment", "Restored scroll state: position=$scrollPosition, offset=$scrollOffset")
                }, 100)
            }
        }
    }

    private val sortReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SORT_GENRES") {
                val sortTypeOrdinal = intent.getIntExtra("sort_type", 0)
                val newSortType = MainActivity.SortType.entries.toTypedArray()[sortTypeOrdinal]
                if (newSortType != currentSortType) {
                    currentSortType = newSortType
                    PreferenceManager.saveSortPreference(requireContext(), "genres", currentSortType)
                    loadGenres()
                }
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "FORCE_REFRESH_GENRES") {
                loadGenres()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "genres")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
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
        showLoading()
        // Proactively load data when the view is created for better reliability
        handler.postDelayed({
            loadGenres()
        }, 100)
    }

    override fun onResume() {
        super.onResume()
        val searchFilter = IntentFilter("SEARCH_QUERY_CHANGED")
        val sortFilter = IntentFilter("SORT_GENRES")
        val refreshFilter = IntentFilter("FORCE_REFRESH_GENRES")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(searchReceiver, searchFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(sortReceiver, sortFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireActivity().registerReceiver(searchReceiver, searchFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireActivity().registerReceiver(sortReceiver, sortFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireActivity().registerReceiver(refreshReceiver, refreshFilter)
        }

        val savedSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "genres")
        if (savedSortType != currentSortType) {
            currentSortType = savedSortType
            loadGenres()
        } else if (!isFirstLoad) {
            loadGenresPreserveState()
        } else {
            isFirstLoad = false
        }
    }

    override fun onPause() {
        super.onPause()
        requireActivity().unregisterReceiver(searchReceiver)
        requireActivity().unregisterReceiver(sortReceiver)
        requireActivity().unregisterReceiver(refreshReceiver)
        loadJob?.cancel()
        saveScrollState()
    }

    fun refreshData() {
        if (isVisible && !isRemoving) loadGenres()
    }

    fun refreshDataPreserveState() {
        if (isVisible && !isRemoving) loadGenresPreserveState()
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

    private suspend fun fetchAndGroupSongs(): List<Genre> = withContext(Dispatchers.IO) {
        if (!isAdded) return@withContext emptyList()
        val songs = SongRepository.getAllSongs(requireContext())
        val genreMap = mutableMapOf<String, Genre>()

        songs.forEach { song ->
            // REVERTED: Using song.year as the grouping key because song.genre is unreliable.
            // This is the logic from the version you said was working.
            val genreName = song.year?.toString() ?: "Unknown"

            val genre = genreMap.getOrPut(genreName) {
                Genre(name = genreName, songCount = 0, songs = mutableListOf())
            }
            genre.songs.add(song)
        }

        // Update song counts after grouping
        genreMap.values.forEach { it.songCount = it.songs.size }
        return@withContext genreMap.values.toList()
    }

    private fun loadGenres() {
        showLoading()
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val newGenres = fetchAndGroupSongs()
                genreList.clear()
                genreList.addAll(newGenres)
                applyCurrentSort()
            } catch (e: Exception) {
                Log.e("GenresFragment", "Error loading genres", e)
                if (isAdded) {
                    hideLoading()
                    emptyView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun loadGenresPreserveState() {
        saveScrollState()
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val newGenres = fetchAndGroupSongs()
                genreList.clear()
                genreList.addAll(newGenres)
                applyCurrentSortPreserveState()
            } catch (e: Exception) {
                Log.e("GenresFragment", "Error in loadGenresPreserveState", e)
            }
        }
    }

    private fun applyCurrentSort() {
        when (currentSortType) {
            MainActivity.SortType.NAME_ASC -> genreList.sortBy { it.name.lowercase() }
            MainActivity.SortType.NAME_DESC -> genreList.sortByDescending { it.name.lowercase() }
            else -> genreList.sortBy { it.name.lowercase() }
        }
        filterGenres()
    }

    private fun applyCurrentSortPreserveState() {
        applyCurrentSort()
        handler.postDelayed({ restoreScrollState() }, 200)
    }

    private fun filterGenres() {
        filteredGenreList = if (currentQuery.isBlank()) {
            genreList.toMutableList()
        } else {
            genreList.filter { it.name.contains(currentQuery, true) }.toMutableList()
        }
        updateAdapter()
    }

    private fun updateAdapter() {
        if (!isAdded) return
        recyclerView.adapter = GenreAdapter(
            genres = filteredGenreList,
            onGenreClick = { position -> openGenreSongs(position) }
        )
        hideLoading()
        emptyView.visibility = if (filteredGenreList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openGenreSongs(position: Int) {
        if (position < 0 || position >= filteredGenreList.size) return
        val genre = filteredGenreList[position]
        val fragment = GenreSongsFragment.newInstance(genre)
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }
}