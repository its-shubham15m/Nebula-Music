package com.shubhamgupta.nebula_player.fragments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.adapters.GenreAdapter
import com.shubhamgupta.nebula_player.models.Genre
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.fragments.GenreSongsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GenresFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingProgress: ProgressBar

    private lateinit var genreAdapter: GenreAdapter

    private var allGenres = listOf<Genre>()
    private var currentSortType = MainActivity.SortType.NAME_ASC
    private var currentQuery = ""

    private val handler = Handler(Looper.getMainLooper())
    private var scrollPosition = 0
    private var scrollOffset = 0

    private val searchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SEARCH_QUERY_CHANGED") {
                currentQuery = intent.getStringExtra("query") ?: ""
                processAndSubmitList()
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
                    processAndSubmitList()
                }
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "FORCE_REFRESH_GENRES") {
                SongRepository.refreshSongs(requireContext())
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

        setupRecyclerView()
        return view
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(context)
        genreAdapter = GenreAdapter { position ->
            openGenreSongs(position)
        }
        recyclerView.adapter = genreAdapter
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val bottomPadding = (140 * resources.displayMetrics.density).toInt()
        recyclerView.clipToPadding = false
        recyclerView.setPadding(0, 0, 0, bottomPadding)

        // Reactive Data Loading
        viewLifecycleOwner.lifecycleScope.launch {
            if (SongRepository.getAllSongs(requireContext()).isEmpty()) {
                loadingProgress.visibility = View.VISIBLE
            }

            SongRepository.getSongsFlow().collectLatest { songs ->
                processGenresFromSongs(songs)
            }
        }
    }

    private suspend fun processGenresFromSongs(songs: List<Song>) {
        withContext(Dispatchers.IO) {
            val genreMap = songs.groupBy { it.genre ?: "Unknown Genre" }
                .mapValues { entry ->
                    Genre(
                        name = entry.key,
                        songCount = entry.value.size,
                        songs = entry.value.toMutableList()
                    )
                }
            allGenres = genreMap.values.toList()

            withContext(Dispatchers.Main) {
                loadingProgress.visibility = View.GONE
                processAndSubmitList()
                if (allGenres.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                } else {
                    emptyView.visibility = View.GONE
                }
            }
        }
    }

    private fun processAndSubmitList() {
        var processedList = if (currentQuery.isBlank()) {
            allGenres.toMutableList()
        } else {
            allGenres.filter { it.name.contains(currentQuery, true) }.toMutableList()
        }

        when (currentSortType) {
            MainActivity.SortType.NAME_ASC -> processedList.sortBy { it.name.lowercase() }
            MainActivity.SortType.NAME_DESC -> processedList.sortByDescending { it.name.lowercase() }
            else -> processedList.sortBy { it.name.lowercase() }
        }

        genreAdapter.submitList(processedList) {
            if (scrollPosition > 0) restoreScrollState()
        }
    }

    fun refreshData() {
        if (isAdded) SongRepository.refreshSongs(requireContext())
    }

    fun refreshDataPreserveState() {
        refreshData()
    }

    private fun openGenreSongs(position: Int) {
        if (position < 0 || position >= genreAdapter.currentList.size) return
        val genre = genreAdapter.currentList[position]
        val fragment = GenreSongsFragment.newInstance(genre)

        val parent = parentFragment?.parentFragment
        if (parent is HomePageFragment) {
            parent.childFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                .replace(R.id.home_content_container, fragment)
                .addToBackStack("genre_songs")
                .commit()
            parent.updateMiniPlayerPosition()
        } else {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack("genre_songs")
                .commit()
        }
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
            requireActivity().registerReceiver(searchReceiver, searchFilter)
            requireActivity().registerReceiver(sortReceiver, sortFilter)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter)
        }
        SongRepository.refreshSongs(requireContext())
    }

    override fun onPause() {
        super.onPause()
        try {
            requireActivity().unregisterReceiver(searchReceiver)
            requireActivity().unregisterReceiver(sortReceiver)
            requireActivity().unregisterReceiver(refreshReceiver)
        } catch (e: Exception) {}
        saveScrollState()
    }

    fun setScrollingEnabled(enabled: Boolean) {
        if (this::recyclerView.isInitialized) {
            recyclerView.isNestedScrollingEnabled = enabled
            recyclerView.isEnabled = enabled
        }
    }

    fun saveScrollState() {
        if (this::recyclerView.isInitialized) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.let {
                scrollPosition = it.findFirstVisibleItemPosition()
                val firstVisibleView = it.findViewByPosition(scrollPosition)
                scrollOffset = firstVisibleView?.top ?: 0
            }
        }
    }

    fun restoreScrollState() {
        if (this::recyclerView.isInitialized) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.let {
                handler.postDelayed({
                    it.scrollToPositionWithOffset(scrollPosition, scrollOffset)
                }, 100)
            }
        }
    }
}