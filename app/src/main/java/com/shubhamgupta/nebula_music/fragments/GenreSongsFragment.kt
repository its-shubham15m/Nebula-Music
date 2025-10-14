package com.shubhamgupta.nebula_music.fragments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_music.R
import com.shubhamgupta.nebula_music.adapters.SongAdapter
import com.shubhamgupta.nebula_music.models.Genre
import com.shubhamgupta.nebula_music.utils.PreferenceManager

class GenreSongsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var genreNameView: TextView
    private lateinit var songCountView: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var tvEmpty: TextView
    private var currentGenre: Genre? = null

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SONG_CHANGED", "PLAYBACK_STATE_CHANGED" -> {
                    // Refresh the adapter to update play states
                    currentGenre?.let { setupGenreSongs(it) }
                }
            }
        }
    }

    companion object {
        private const val ARG_GENRE = "genre"

        fun newInstance(genre: Genre): GenreSongsFragment {
            val fragment = GenreSongsFragment()
            val args = Bundle()
            args.putParcelable(ARG_GENRE, genre)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_genre_songs, container, false)
        initializeViews(view)
        return view
    }

    // In onResume method of each fragment:

    override fun onResume() {
        super.onResume()
        // Lock drawer when fragment is visible
        (requireActivity() as com.shubhamgupta.nebula_music.MainActivity).setDrawerLocked(true)

        // Register broadcast receiver for playback state changes with proper flags
        val filter = IntentFilter().apply {
            addAction("SONG_CHANGED")
            addAction("PLAYBACK_STATE_CHANGED")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(playbackReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireActivity().registerReceiver(playbackReceiver, filter)
        }

        // Refresh data when returning to fragment
        currentGenre?.let { setupGenreSongs(it) }
    }

    override fun onPause() {
        super.onPause()
        // Unlock drawer when leaving fragment
        (requireActivity() as com.shubhamgupta.nebula_music.MainActivity).setDrawerLocked(false)

        try {
            requireActivity().unregisterReceiver(playbackReceiver)
        } catch (e: Exception) {
            // Ignore if receiver was not registered
        }
    }

    private fun initializeViews(view: View) {
        recyclerView = view.findViewById(R.id.recycler_view_genre_songs)
        genreNameView = view.findViewById(R.id.genre_name)
        songCountView = view.findViewById(R.id.genre_song_count)
        btnBack = view.findViewById(R.id.btn_back)
        tvEmpty = view.findViewById(R.id.tv_empty_genre_songs)

        recyclerView.layoutManager = LinearLayoutManager(context)

        currentGenre = arguments?.getParcelable<Genre>(ARG_GENRE)
        currentGenre?.let { setupGenreSongs(it) }

        btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        // Get the shuffle card
        val shuffleCard = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)
        shuffleCard.setOnClickListener {
            shuffleGenreSongs()
        }
    }

    private fun setupGenreSongs(genre: Genre) {
        genreNameView.text = genre.name
        songCountView.text = "${genre.songCount} songs"

        if (genre.songs.isEmpty()) {
            recyclerView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.GONE
        } else {
            recyclerView.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.VISIBLE

            val adapter = SongAdapter(
                songs = genre.songs,
                onItemClick = { pos -> openNowPlaying(pos, genre.songs) },
                onMenuClick = { pos, menuItem -> handleMenuAction(pos, menuItem, genre.songs) },
                isSongFavorite = { songId -> PreferenceManager.isFavorite(requireContext(), songId) }
            )
            recyclerView.adapter = adapter
        }
    }

    private fun shuffleGenreSongs() {
        currentGenre?.let { genre ->
            if (genre.songs.isNotEmpty()) {
                val shuffledSongs = genre.songs.shuffled()
                val service = (requireActivity() as com.shubhamgupta.nebula_music.MainActivity).getMusicService()
                service?.startPlayback(ArrayList(shuffledSongs), 0)
                service?.toggleShuffle()
                (requireActivity() as com.shubhamgupta.nebula_music.MainActivity).navigateToNowPlaying()
                showToast("Shuffling ${genre.songs.size} ${genre.name} songs")
            } else {
                showToast("No songs to shuffle")
            }
        }
    }

    private fun handleMenuAction(position: Int, menuItem: String, songs: List<com.shubhamgupta.nebula_music.models.Song>) {
        val song = songs[position]
        when (menuItem) {
            "play" -> openNowPlaying(position, songs)
            "toggle_favorite" -> toggleFavorite(song)
        }
    }

    private fun toggleFavorite(song: com.shubhamgupta.nebula_music.models.Song) {
        if (PreferenceManager.isFavorite(requireContext(), song.id)) {
            PreferenceManager.removeFavorite(requireContext(), song.id)
        } else {
            PreferenceManager.addFavorite(requireContext(), song.id)
        }
        // Refresh adapter to update favorite states
        currentGenre?.let { setupGenreSongs(it) }
    }

    private fun openNowPlaying(position: Int, songs: List<com.shubhamgupta.nebula_music.models.Song>) {
        val songToPlay = songs[position]
        PreferenceManager.addRecentSong(requireContext(), songToPlay.id)

        val service = (requireActivity() as com.shubhamgupta.nebula_music.MainActivity).getMusicService()
        service?.startPlayback(ArrayList(songs), position)

        (requireActivity() as com.shubhamgupta.nebula_music.MainActivity).navigateToNowPlaying()
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ADD THIS METHOD
    fun refreshData() {
        currentGenre?.let { setupGenreSongs(it) }
    }
}