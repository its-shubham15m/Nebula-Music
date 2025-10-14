package com.shubhamgupta.nebula_music.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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

class FavoritesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnCreatePlaylist: Button
    private var musicService: MusicService? = null
    private val favoriteSongs = mutableListOf<Song>()
    private lateinit var adapter: SongAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_favorites, container, false)
        initializeViews(view)
        musicService = (requireActivity() as MainActivity).getMusicService()
        loadFavorites()
        return view
    }

    override fun onResume() {
        super.onResume()
        // Lock drawer when fragment is visible
        (requireActivity() as MainActivity).setDrawerLocked(true)
        loadFavorites() // Refresh when returning to fragment
    }

    override fun onPause() {
        super.onPause()
        // Unlock drawer when leaving fragment
        (requireActivity() as MainActivity).setDrawerLocked(false)
    }

    private fun initializeViews(view: View) {
        recyclerView = view.findViewById(R.id.recycler_view_fav)
        tvEmpty = view.findViewById(R.id.tv_empty_fav)
        btnBack = view.findViewById(R.id.btn_back)

        // Get the shuffle card instead of individual button
        val shuffleCard = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)

        btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        shuffleCard.setOnClickListener {
            shuffleFavorites()
        }

        // Sort and select buttons (optional functionality)
        view.findViewById<ImageButton>(R.id.btn_sort).setOnClickListener {
            showSortDialog()
        }

        view.findViewById<ImageButton>(R.id.btn_select).setOnClickListener {
            // Implement selection mode if needed
            showToast("Select mode - Coming soon")
        }
    }

    private fun loadFavorites() {
        val favoriteIds = PreferenceManager.getFavorites(requireContext())
        val allSongs = SongRepository.getAllSongs(requireContext())

        favoriteSongs.clear()
        // Sort by recently added to library (using dateAdded descending)
        favoriteSongs.addAll(allSongs.filter { it.id in favoriteIds }
            .sortedByDescending { it.dateAdded })

        if (favoriteSongs.isEmpty()) {
            recyclerView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            // Hide shuffle card when no songs
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.GONE
        } else {
            recyclerView.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            // Show shuffle card when songs are available
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.VISIBLE
        }

        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(context)
        adapter = SongAdapter(favoriteSongs, { position ->
            playSong(position)
        }, { pos, menuItem ->
            handleMenuAction(pos, menuItem)
        })
        recyclerView.adapter = adapter
    }

    private fun playSong(position: Int) {
        val songToPlay = favoriteSongs[position]
        // Add to recents when playing a song from the favorites list
        PreferenceManager.addRecentSong(requireContext(), songToPlay.id)

        musicService?.startPlayback(ArrayList(favoriteSongs), position)
        (requireActivity() as MainActivity).navigateToNowPlaying()
    }

    private fun shuffleFavorites() {
        if (favoriteSongs.isNotEmpty()) {
            val shuffledSongs = favoriteSongs.shuffled()
            musicService?.startPlayback(ArrayList(shuffledSongs), 0)
            musicService?.toggleShuffle()
            (requireActivity() as MainActivity).navigateToNowPlaying()
            showToast("Shuffling ${favoriteSongs.size} favorite songs")
        } else {
            showToast("No favorite songs to shuffle")
        }
    }

    private fun showSortDialog() {
        val items = arrayOf("Name (A-Z)", "Name (Z-A)", "Date Added (Newest)", "Date Added (Oldest)")

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Sort by")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> sortFavoritesByName(true)
                    1 -> sortFavoritesByName(false)
                    2 -> sortFavoritesByDate(true)
                    3 -> sortFavoritesByDate(false)
                }
            }
            .show()
    }

    private fun sortFavoritesByName(ascending: Boolean) {
        favoriteSongs.sortBy { it.title }
        if (!ascending) {
            favoriteSongs.reverse()
        }
        adapter.notifyDataSetChanged()
        showToast("Sorted by name ${if (ascending) "A-Z" else "Z-A"}")
    }

    private fun sortFavoritesByDate(descending: Boolean) {
        favoriteSongs.sortBy { it.dateAdded }
        if (descending) {
            favoriteSongs.reverse()
        }
        adapter.notifyDataSetChanged()
        showToast("Sorted by date ${if (descending) "newest first" else "oldest first"}")
    }

    private fun handleMenuAction(position: Int, menuItem: String) {
        val song = favoriteSongs[position]
        when (menuItem) {
            "play" -> playSong(position)
            "add_to_queue" -> {
                showToast("Added to queue: ${song.title}")
            }
            "add_to_playlist" -> {
                showToast("Add to playlist: ${song.title}")
            }
            "delete" -> {
                // Remove from favorites
                song.isFavorite = false
                PreferenceManager.removeFavorite(requireContext(), song.id)
                loadFavorites() // Reload the list
                showToast("Removed from favorites")
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
        loadFavorites()
    }
}