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
import com.shubhamgupta.nebula_music.models.Artist
import com.shubhamgupta.nebula_music.utils.PreferenceManager

class ArtistSongsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var artistNameView: TextView
    private lateinit var songCountView: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var tvEmpty: TextView
    private var currentArtist: Artist? = null

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SONG_CHANGED", "PLAYBACK_STATE_CHANGED" -> {
                    // Refresh the adapter to update play states
                    currentArtist?.let { setupArtistSongs(it) }
                }
            }
        }
    }

    companion object {
        private const val ARG_ARTIST = "artist"

        fun newInstance(artist: Artist): ArtistSongsFragment {
            val fragment = ArtistSongsFragment()
            val args = Bundle()
            args.putParcelable(ARG_ARTIST, artist)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_artist_songs, container, false)
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
        currentArtist?.let { setupArtistSongs(it) }
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
        recyclerView = view.findViewById(R.id.recycler_view_artist_songs)
        artistNameView = view.findViewById(R.id.artist_name)
        songCountView = view.findViewById(R.id.artist_song_count)
        btnBack = view.findViewById(R.id.btn_back)
        tvEmpty = view.findViewById(R.id.tv_empty_artist_songs)

        recyclerView.layoutManager = LinearLayoutManager(context)

        currentArtist = arguments?.getParcelable<Artist>(ARG_ARTIST)
        currentArtist?.let { setupArtistSongs(it) }

        btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        // Get the shuffle card
        val shuffleCard = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)
        shuffleCard.setOnClickListener {
            shuffleArtistSongs()
        }
    }

    private fun setupArtistSongs(artist: Artist) {
        artistNameView.text = artist.name
        songCountView.text = "${artist.songCount} songs"

        if (artist.songs.isEmpty()) {
            recyclerView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.GONE
        } else {
            recyclerView.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.VISIBLE

            val adapter = SongAdapter(
                songs = artist.songs,
                onItemClick = { pos -> openNowPlaying(pos, artist.songs) },
                onMenuClick = { pos, menuItem -> handleMenuAction(pos, menuItem, artist.songs) },
                isSongFavorite = { songId -> PreferenceManager.isFavorite(requireContext(), songId) }
            )
            recyclerView.adapter = adapter
        }
    }

    private fun shuffleArtistSongs() {
        currentArtist?.let { artist ->
            if (artist.songs.isNotEmpty()) {
                val shuffledSongs = artist.songs.shuffled()
                val service = (requireActivity() as com.shubhamgupta.nebula_music.MainActivity).getMusicService()
                service?.startPlayback(ArrayList(shuffledSongs), 0)
                service?.toggleShuffle()
                (requireActivity() as com.shubhamgupta.nebula_music.MainActivity).navigateToNowPlaying()
                showToast("Shuffling ${artist.songs.size} songs by ${artist.name}")
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
        currentArtist?.let { setupArtistSongs(it) }
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
        currentArtist?.let { setupArtistSongs(it) }
    }
}