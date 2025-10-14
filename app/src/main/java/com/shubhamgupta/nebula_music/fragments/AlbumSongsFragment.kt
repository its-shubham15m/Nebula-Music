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
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shubhamgupta.nebula_music.R
import com.shubhamgupta.nebula_music.adapters.SongAdapter
import com.shubhamgupta.nebula_music.models.Album
import com.shubhamgupta.nebula_music.utils.PreferenceManager
import com.shubhamgupta.nebula_music.utils.SongUtils

class AlbumSongsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var albumArt: ImageView
    private lateinit var albumNameView: TextView
    private lateinit var albumArtistView: TextView
    private lateinit var songCountView: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var tvEmpty: TextView
    private var currentAlbum: Album? = null

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SONG_CHANGED", "PLAYBACK_STATE_CHANGED" -> {
                    // Refresh the adapter to update play states
                    currentAlbum?.let { setupAlbumSongs(it) }
                }
            }
        }
    }

    companion object {
        private const val ARG_ALBUM = "album"

        fun newInstance(album: Album): AlbumSongsFragment {
            val fragment = AlbumSongsFragment()
            val args = Bundle()
            args.putParcelable(ARG_ALBUM, album)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_album_songs, container, false)
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
        currentAlbum?.let { setupAlbumSongs(it) }
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
        recyclerView = view.findViewById(R.id.recycler_view_album_songs)
        albumArt = view.findViewById(R.id.album_art)
        albumNameView = view.findViewById(R.id.album_name)
        albumArtistView = view.findViewById(R.id.album_artist)
        songCountView = view.findViewById(R.id.album_song_count)
        btnBack = view.findViewById(R.id.btn_back)
        tvEmpty = view.findViewById(R.id.tv_empty_album_songs)

        recyclerView.layoutManager = LinearLayoutManager(context)

        currentAlbum = arguments?.getParcelable<Album>(ARG_ALBUM)
        currentAlbum?.let { setupAlbumSongs(it) }

        btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        // Get the shuffle card
        val shuffleCard = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)
        shuffleCard.setOnClickListener {
            shuffleAlbumSongs()
        }
    }

    private fun setupAlbumSongs(album: Album) {
        albumNameView.text = album.name
        albumArtistView.text = album.artist
        songCountView.text = "${album.songCount} songs"

        // Load album art
        val albumArtUri = SongUtils.getAlbumArtUri(album.albumId)
        Glide.with(this)
            .load(albumArtUri)
            .placeholder(R.drawable.default_album_art)
            .error(R.drawable.default_album_art)
            .into(albumArt)

        if (album.songs.isEmpty()) {
            recyclerView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.GONE
        } else {
            recyclerView.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.VISIBLE

            val adapter = SongAdapter(
                songs = album.songs,
                onItemClick = { pos -> openNowPlaying(pos, album.songs) },
                onMenuClick = { pos, menuItem -> handleMenuAction(pos, menuItem, album.songs) },
                isSongFavorite = { songId -> PreferenceManager.isFavorite(requireContext(), songId) }
            )
            recyclerView.adapter = adapter
        }
    }

    private fun shuffleAlbumSongs() {
        currentAlbum?.let { album ->
            if (album.songs.isNotEmpty()) {
                val shuffledSongs = album.songs.shuffled()
                val service = (requireActivity() as com.shubhamgupta.nebula_music.MainActivity).getMusicService()
                service?.startPlayback(ArrayList(shuffledSongs), 0)
                service?.toggleShuffle()
                (requireActivity() as com.shubhamgupta.nebula_music.MainActivity).navigateToNowPlaying()
                showToast("Shuffling ${album.songs.size} songs from ${album.name}")
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
        currentAlbum?.let { setupAlbumSongs(it) }
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
        currentAlbum?.let { setupAlbumSongs(it) }
    }
}