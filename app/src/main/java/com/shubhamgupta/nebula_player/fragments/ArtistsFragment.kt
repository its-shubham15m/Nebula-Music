package com.shubhamgupta.nebula_player.fragments

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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.adapters.ArtistAdapter
import com.shubhamgupta.nebula_player.models.Artist
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArtistsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingProgress: ProgressBar

    private lateinit var artistAdapter: ArtistAdapter

    private var allArtists = listOf<Artist>()
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
            if (intent?.action == "SORT_ARTISTS") {
                val sortTypeOrdinal = intent.getIntExtra("sort_type", 0)
                val newSortType = MainActivity.SortType.entries.toTypedArray()[sortTypeOrdinal]
                if (newSortType != currentSortType) {
                    currentSortType = newSortType
                    PreferenceManager.saveSortPreference(requireContext(), "artists", currentSortType)
                    processAndSubmitList()
                }
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "FORCE_REFRESH_ARTISTS") {
                SongRepository.refreshSongs(requireContext())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "artists")
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
        artistAdapter = ArtistAdapter { position ->
            openArtistSongs(position)
        }
        recyclerView.adapter = artistAdapter
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
                processArtistsFromSongs(songs)
            }
        }
    }

    private suspend fun processArtistsFromSongs(songs: List<Song>) {
        withContext(Dispatchers.IO) {
            val artistMap = songs.groupBy { it.artist ?: "Unknown Artist" }
                .mapValues { entry ->
                    Artist(
                        name = entry.key,
                        songCount = entry.value.size,
                        songs = entry.value.toMutableList()
                    )
                }
            allArtists = artistMap.values.toList()

            withContext(Dispatchers.Main) {
                loadingProgress.visibility = View.GONE
                processAndSubmitList()

                if (allArtists.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                } else {
                    emptyView.visibility = View.GONE
                }
            }
        }
    }

    private fun processAndSubmitList() {
        var processedList = if (currentQuery.isBlank()) {
            allArtists.toMutableList()
        } else {
            allArtists.filter { it.name.contains(currentQuery, true) }.toMutableList()
        }

        when (currentSortType) {
            MainActivity.SortType.NAME_ASC -> processedList.sortBy { it.name.lowercase() }
            MainActivity.SortType.NAME_DESC -> processedList.sortByDescending { it.name.lowercase() }
            else -> processedList.sortBy { it.name.lowercase() }
        }

        artistAdapter.submitList(processedList) {
            if (scrollPosition > 0) {
                restoreScrollState()
            }
        }
    }

    // Bridge for MusicPageFragment
    fun refreshData() {
        if (isAdded) SongRepository.refreshSongs(requireContext())
    }

    fun refreshDataPreserveState() {
        refreshData()
    }

    private fun openArtistSongs(position: Int) {
        if (position < 0 || position >= artistAdapter.currentList.size) return
        val artist = artistAdapter.currentList[position]
        val fragment = ArtistSongsFragment.newInstance(artist)

        val parent = parentFragment?.parentFragment
        if (parent is HomePageFragment) {
            parent.childFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                .replace(R.id.home_content_container, fragment)
                .addToBackStack("artist_songs")
                .commit()
            parent.updateMiniPlayerPosition()
        } else {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack("artist_songs")
                .commit()
        }
    }

    // ... (Scroll state and Receiver registration code remains same) ...
    override fun onResume() {
        super.onResume()
        val searchFilter = IntentFilter("SEARCH_QUERY_CHANGED")
        val sortFilter = IntentFilter("SORT_ARTISTS")
        val refreshFilter = IntentFilter("FORCE_REFRESH_ARTISTS")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(searchReceiver, searchFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(sortReceiver, sortFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireActivity().registerReceiver(searchReceiver, searchFilter)
            requireActivity().registerReceiver(sortReceiver, sortFilter)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter)
        }

        // Trigger background check
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