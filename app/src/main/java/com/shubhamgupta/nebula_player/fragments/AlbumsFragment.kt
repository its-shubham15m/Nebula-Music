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
import com.shubhamgupta.nebula_player.adapters.AlbumAdapter
import com.shubhamgupta.nebula_player.models.Album
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.fragments.AlbumSongsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlbumsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingProgress: ProgressBar

    private lateinit var albumAdapter: AlbumAdapter

    private var allAlbums = listOf<Album>()
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
            if (intent?.action == "SORT_ALBUMS") {
                val sortTypeOrdinal = intent.getIntExtra("sort_type", 0)
                val newSortType = MainActivity.SortType.entries.toTypedArray()[sortTypeOrdinal]
                if (newSortType != currentSortType) {
                    currentSortType = newSortType
                    PreferenceManager.saveSortPreference(requireContext(), "albums", currentSortType)
                    processAndSubmitList()
                }
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "FORCE_REFRESH_ALBUMS") {
                SongRepository.refreshSongs(requireContext())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "albums")
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
        albumAdapter = AlbumAdapter { position ->
            openAlbumSongs(position)
        }
        recyclerView.adapter = albumAdapter
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
                processAlbumsFromSongs(songs)
            }
        }
    }

    private suspend fun processAlbumsFromSongs(songs: List<Song>) {
        withContext(Dispatchers.IO) {
            val albumMap = songs.groupBy { it.album ?: "Unknown Album" }
                .mapValues { entry ->
                    val firstSong = entry.value.first()
                    Album(
                        name = entry.key,
                        artist = firstSong.artist ?: "Unknown Artist",
                        songCount = entry.value.size,
                        songs = entry.value.toMutableList(),
                        albumId = firstSong.albumId
                    )
                }
            allAlbums = albumMap.values.toList()

            withContext(Dispatchers.Main) {
                loadingProgress.visibility = View.GONE
                processAndSubmitList()
                if (allAlbums.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                } else {
                    emptyView.visibility = View.GONE
                }
            }
        }
    }

    private fun processAndSubmitList() {
        var processedList = if (currentQuery.isBlank()) {
            allAlbums.toMutableList()
        } else {
            allAlbums.filter { album ->
                album.name.contains(currentQuery, true) || album.artist.contains(currentQuery, true)
            }.toMutableList()
        }

        when (currentSortType) {
            MainActivity.SortType.NAME_ASC -> processedList.sortBy { it.name.lowercase() }
            MainActivity.SortType.NAME_DESC -> processedList.sortByDescending { it.name.lowercase() }
            else -> processedList.sortBy { it.name.lowercase() }
        }

        albumAdapter.submitList(processedList) {
            if (scrollPosition > 0) restoreScrollState()
        }
    }

    fun refreshData() {
        if (isAdded) SongRepository.refreshSongs(requireContext())
    }

    fun refreshDataPreserveState() {
        refreshData()
    }

    private fun openAlbumSongs(position: Int) {
        if (position < 0 || position >= albumAdapter.currentList.size) return
        val album = albumAdapter.currentList[position]
        val fragment = AlbumSongsFragment.newInstance(album)

        val parent = parentFragment?.parentFragment
        if (parent is HomePageFragment) {
            parent.childFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                .replace(R.id.home_content_container, fragment)
                .addToBackStack("album_songs")
                .commit()
            parent.updateMiniPlayerPosition()
        } else {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack("album_songs")
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        val searchFilter = IntentFilter("SEARCH_QUERY_CHANGED")
        val sortFilter = IntentFilter("SORT_ALBUMS")
        val refreshFilter = IntentFilter("FORCE_REFRESH_ALBUMS")

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
        } catch (e: Exception) { }
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