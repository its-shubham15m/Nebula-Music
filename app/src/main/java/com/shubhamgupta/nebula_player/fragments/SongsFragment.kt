package com.shubhamgupta.nebula_player.fragments

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.adapters.SongAdapter
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SongsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingProgress: ProgressBar

    private lateinit var songAdapter: SongAdapter

    private var allSongs: List<Song> = emptyList()
    private var currentSortType = MainActivity.SortType.NAME_ASC
    private var currentQuery = ""

    private var scrollPosition = 0
    private var scrollOffset = 0

    private lateinit var deleteResultLauncher: ActivityResultLauncher<IntentSenderRequest>

    // --- Broadcast Receivers ---
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
            if (intent?.action == "SORT_SONGS") {
                val sortTypeOrdinal = intent.getIntExtra("sort_type", 0)
                val newSortType = MainActivity.SortType.entries.toTypedArray()[sortTypeOrdinal]
                if (newSortType != currentSortType) {
                    currentSortType = newSortType
                    PreferenceManager.saveSortPreference(requireContext(), "songs", currentSortType)
                    processAndSubmitList()
                }
            }
        }
    }

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SONG_CHANGED", "PLAYBACK_STATE_CHANGED" -> {
                    if (::songAdapter.isInitialized) {
                        songAdapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "FORCE_REFRESH_SONGS") {
                refreshData()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "songs")

        deleteResultLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(requireContext(), "Song deleted successfully", Toast.LENGTH_SHORT).show()
                refreshData()
            } else {
                Toast.makeText(requireContext(), "Song could not be deleted", Toast.LENGTH_SHORT).show()
            }
        }
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

        setupRecyclerView()
        return view
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(context)

        songAdapter = SongAdapter(
            context = requireContext(),
            onItemClick = { pos -> openNowPlaying(pos) },
            onDataChanged = { /* Optional */ },
            onDeleteRequest = { song -> requestDeleteSong(song) }
        )
        recyclerView.adapter = songAdapter
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            if (SongRepository.getAllSongs(requireContext()).isEmpty()) {
                loadingProgress.visibility = View.VISIBLE
            }

            SongRepository.getSongsFlow().collectLatest { songs ->
                loadingProgress.visibility = View.GONE
                allSongs = songs
                processAndSubmitList()

                if (songs.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                } else {
                    emptyView.visibility = View.GONE
                }
            }
        }
    }

    /**
     * PUBLIC METHOD: Called by MusicPageFragment to force a refresh.
     * Triggers the repository to scan MediaStore.
     */
    fun refreshData() {
        if (isAdded) {
            SongRepository.refreshSongs(requireContext())
        }
    }

    /**
     * PUBLIC METHOD: Called by MusicPageFragment.
     * In the new ListAdapter architecture, state is preserved automatically by DiffUtil,
     * so this is just an alias for refreshData.
     */
    fun refreshDataPreserveState() {
        refreshData()
    }

    private fun processAndSubmitList() {
        val processedList = if (currentQuery.isBlank()) {
            allSongs.toMutableList()
        } else {
            allSongs.filter { song ->
                song.title.contains(currentQuery, true) ||
                        song.artist?.contains(currentQuery, true) == true ||
                        song.album?.contains(currentQuery, true) == true
            }.toMutableList()
        }

        when (currentSortType) {
            MainActivity.SortType.NAME_ASC -> processedList.sortBy { it.title.lowercase() }
            MainActivity.SortType.NAME_DESC -> processedList.sortByDescending { it.title.lowercase() }
            MainActivity.SortType.DATE_ADDED_ASC -> processedList.sortBy { it.dateAdded }
            MainActivity.SortType.DATE_ADDED_DESC -> processedList.sortByDescending { it.dateAdded }
            MainActivity.SortType.DURATION -> processedList.sortByDescending { it.duration }
        }

        if (::songAdapter.isInitialized) {
            songAdapter.submitList(processedList) {
                if (scrollPosition > 0) {
                    // restore logic if needed
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val searchFilter = IntentFilter("SEARCH_QUERY_CHANGED")
        val sortFilter = IntentFilter("SORT_SONGS")
        val refreshFilter = IntentFilter("FORCE_REFRESH_SONGS")
        val playbackFilter = IntentFilter().apply {
            addAction("SONG_CHANGED")
            addAction("PLAYBACK_STATE_CHANGED")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(searchReceiver, searchFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(sortReceiver, sortFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(playbackReceiver, playbackFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireActivity().registerReceiver(searchReceiver, searchFilter)
            requireActivity().registerReceiver(sortReceiver, sortFilter)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter)
            requireActivity().registerReceiver(playbackReceiver, playbackFilter)
        }

        // Background check for updates
        SongRepository.refreshSongs(requireContext())
    }

    override fun onPause() {
        super.onPause()
        requireActivity().unregisterReceiver(searchReceiver)
        requireActivity().unregisterReceiver(sortReceiver)
        requireActivity().unregisterReceiver(refreshReceiver)
        try {
            requireActivity().unregisterReceiver(playbackReceiver)
        } catch (e: Exception) { /* Ignore */ }

        saveScrollState()
    }

    fun setScrollingEnabled(enabled: Boolean) {
        try {
            if (this::recyclerView.isInitialized) {
                recyclerView.isNestedScrollingEnabled = enabled
                recyclerView.isEnabled = enabled
                if (!enabled) {
                    recyclerView.setOnTouchListener { _, _ -> true }
                } else {
                    recyclerView.setOnTouchListener(null)
                }
            }
        } catch (e: Exception) {
            Log.e("SongsFragment", "Error setting scrolling enabled: $enabled", e)
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

    private fun requestDeleteSong(song: Song) {
        try {
            val intentSender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uris = listOf(song.uri)
                MediaStore.createDeleteRequest(requireContext().contentResolver, uris).intentSender
            } else {
                null
            }

            if (intentSender != null) {
                val request = IntentSenderRequest.Builder(intentSender).build()
                deleteResultLauncher.launch(request)
            } else {
                Toast.makeText(requireContext(), "Could not request deletion (Android 10-).", Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                val request = IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                deleteResultLauncher.launch(request)
            } else {
                Toast.makeText(requireContext(), "Permission denied.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openNowPlaying(position: Int) {
        val service = (requireActivity() as MainActivity).getMusicService()
        if (::songAdapter.isInitialized) {
            val currentList = songAdapter.currentList
            service?.startPlayback(ArrayList(currentList), position)
            (requireActivity() as MainActivity).navigateToNowPlaying()
        }
    }
}