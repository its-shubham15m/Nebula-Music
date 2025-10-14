package com.shubhamgupta.nebula_music.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_music.MainActivity
import com.shubhamgupta.nebula_music.R
import com.shubhamgupta.nebula_music.adapters.SongAdapter
import com.shubhamgupta.nebula_music.models.Song
import com.shubhamgupta.nebula_music.repository.SongRepository
import com.shubhamgupta.nebula_music.service.MusicService
import com.shubhamgupta.nebula_music.utils.PreferenceManager

class RecentFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnBack: ImageButton
    private var musicService: MusicService? = null
    private val recentSongs = mutableListOf<Song>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_recent, container, false)
        initializeViews(view)
        loadRecentSongs()
        return view
    }

    override fun onResume() {
        super.onResume()
        // Lock drawer when fragment is visible
        (requireActivity() as MainActivity).setDrawerLocked(true)
        loadRecentSongs()
    }

    override fun onPause() {
        super.onPause()
        // Unlock drawer when leaving fragment
        (requireActivity() as MainActivity).setDrawerLocked(false)
    }

    private fun initializeViews(view: View) {
        recyclerView = view.findViewById(R.id.recycler_view_recent)
        tvEmpty = view.findViewById(R.id.tv_empty_recent)
        btnBack = view.findViewById(R.id.btn_back)

        // Get the shuffle card instead of individual button
        val shuffleCard = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        shuffleCard.setOnClickListener {
            shuffleRecentSongs()
        }

        // Sort and select buttons (optional functionality)
        view.findViewById<ImageButton>(R.id.btn_sort).setOnClickListener {
            showSortDialog()
        }

        view.findViewById<ImageButton>(R.id.btn_select).setOnClickListener {
            // Implement selection mode if needed
            showToast("Select mode - Coming soon")
        }

        try {
            musicService = (requireActivity() as MainActivity).getMusicService()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadRecentSongs() {
        val recentSongIds = PreferenceManager.getRecentSongs(requireContext())
        val allSongs = SongRepository.getAllSongs(requireContext())

        recentSongs.clear()
        recentSongs.addAll(allSongs.filter { song ->
            recentSongIds.contains(song.id)
        }.sortedBy { song ->
            recentSongIds.indexOf(song.id)
        })

        if (recentSongs.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            // Hide shuffle card when no songs
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            // Show shuffle card when songs are available
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.VISIBLE

            val adapter = SongAdapter(recentSongs,
                onItemClick = { position ->
                    playSong(position)
                },
                onMenuClick = { position, menuItem ->
                    handleMenuAction(position, menuItem)
                }
            )
            recyclerView.adapter = adapter
        }
    }

    private fun playSong(position: Int) {
        val songToPlay = recentSongs[position]
        PreferenceManager.addRecentSong(requireContext(), songToPlay.id)

        musicService?.startPlayback(ArrayList(recentSongs), position)

        // Simply navigate to NowPlaying - the MainActivity should handle its own UI updates
        (requireActivity() as MainActivity).navigateToNowPlaying()
    }

    private fun shuffleRecentSongs() {
        if (recentSongs.isNotEmpty()) {
            val shuffledSongs = recentSongs.shuffled()
            musicService?.startPlayback(ArrayList(shuffledSongs), 0)
            musicService?.toggleShuffle()
            (requireActivity() as MainActivity).navigateToNowPlaying()
            showToast("Shuffling ${recentSongs.size} recent songs")
        } else {
            showToast("No recent songs to shuffle")
        }
    }

    private fun showSortDialog() {
        val items = arrayOf("Most Recent", "Least Recent", "Name (A-Z)", "Name (Z-A)")

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Sort by")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> sortRecentByDate(true)
                    1 -> sortRecentByDate(false)
                    2 -> sortRecentByName(true)
                    3 -> sortRecentByName(false)
                }
            }
            .show()
    }

    private fun sortRecentByDate(descending: Boolean) {
        val recentSongIds = PreferenceManager.getRecentSongs(requireContext())
        recentSongs.sortBy { song -> recentSongIds.indexOf(song.id) }
        if (!descending) {
            recentSongs.reverse()
        }
        recyclerView.adapter?.notifyDataSetChanged()
        showToast("Sorted by ${if (descending) "most recent" else "least recent"}")
    }

    private fun sortRecentByName(ascending: Boolean) {
        recentSongs.sortBy { it.title }
        if (!ascending) {
            recentSongs.reverse()
        }
        recyclerView.adapter?.notifyDataSetChanged()
        showToast("Sorted by name ${if (ascending) "A-Z" else "Z-A"}")
    }

    private fun handleMenuAction(position: Int, menuItem: String) {
        val song = recentSongs[position]
        when (menuItem) {
            "play" -> playSong(position)
            "add_to_queue" -> {
                showToast("Added to queue: ${song.title}")
            }
            "add_to_playlist" -> {
                showToast("Add to playlist: ${song.title}")
            }
            "delete" -> {
                PreferenceManager.removeRecentSong(requireContext(), song.id)
                loadRecentSongs()
                showToast("Removed from recent")
            }
            "share" -> {
                shareSong(song)
            }
        }
    }

    private fun shareSong(song: Song) {
        val shareIntent = android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT,
                "Check out \"${song.title}\" by ${song.artist ?: "Unknown Artist"}")
        }
        startActivity(android.content.Intent.createChooser(shareIntent, "Share Song"))
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ADD THIS METHOD
    fun refreshData() {
        loadRecentSongs()
    }
}