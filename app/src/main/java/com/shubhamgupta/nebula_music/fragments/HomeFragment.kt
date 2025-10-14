package com.shubhamgupta.nebula_music.fragments

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shubhamgupta.nebula_music.MainActivity
import com.shubhamgupta.nebula_music.R
import com.shubhamgupta.nebula_music.adapters.SongAdapter
import com.shubhamgupta.nebula_music.models.Song
import com.shubhamgupta.nebula_music.models.Playlist
import com.shubhamgupta.nebula_music.repository.SongRepository
import com.shubhamgupta.nebula_music.utils.PreferenceManager
import com.shubhamgupta.nebula_music.utils.SongUtils
import java.util.Collections
import java.util.UUID

class HomeFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private val songList = mutableListOf<Song>()
    private var filteredSongList = mutableListOf<Song>()
    private var currentSortType = MainActivity.SortType.NAME_ASC
    private var currentQuery = ""
    private var viewMode: String = "songs"

    // Card UI elements properties
    private var cardFavImage: ImageView? = null
    private var cardFavTitle: TextView? = null
    private var cardFavCount: TextView? = null

    private var cardPlaylistImage: ImageView? = null
    private var cardPlaylistTitle: TextView? = null
    private var cardPlaylistCount: TextView? = null

    private var cardRecentImage: ImageView? = null
    private var cardRecentTitle: TextView? = null
    private var cardRecentCount: TextView? = null

    private val searchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SEARCH_QUERY_CHANGED") {
                currentQuery = intent.getStringExtra("query") ?: ""
                filterSongs()
            }
        }
    }

    private val sortReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SORT_SONGS") {
                val sortTypeOrdinal = intent.getIntExtra("sort_type", 0)
                currentSortType = MainActivity.SortType.entries.toTypedArray()[sortTypeOrdinal]
                sortSongs()
            }
        }
    }

    // Receiver to update card data when a song is favorited, a playlist changes, or a new song is played
    private val songChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Trigger card update for any relevant change, including current song changes
            if (intent?.action == "SONG_CHANGED" || intent?.action == "PLAYLIST_CHANGED" || intent?.action == "SONG_STATE_CHANGED") {
                loadQuickActionCardData()
            }
        }
    }

    // Receiver to force refresh when returning from full-screen views
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "MAIN_DATA_REFRESH") {
                loadSongs() // Reloads the main list
                loadQuickActionCardData() // Updates the quick action cards
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadSongs() else Toast.makeText(
                context,
                "Permission denied",
                Toast.LENGTH_SHORT
            ).show()
        }

    companion object {
        fun newInstance(mode: String): HomeFragment {
            val fragment = HomeFragment()
            val args = Bundle()
            args.putString("VIEW_MODE", mode)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewMode = arguments?.getString("VIEW_MODE") ?: "songs"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(context)

        // Bind UI elements from the parent Activity's layout (activity_main.xml)
        val mainActivity = requireActivity() as MainActivity

        mainActivity.findViewById<View>(R.id.card_favorites)?.let {
            cardFavImage = it.findViewById(R.id.img_favorites_overlay)
            cardFavTitle = it.findViewById(R.id.card_fav_title)
            cardFavCount = it.findViewById(R.id.card_fav_count)
        }
        mainActivity.findViewById<View>(R.id.card_playlists)?.let {
            cardPlaylistImage = it.findViewById(R.id.img_playlists_overlay)
            cardPlaylistTitle = it.findViewById(R.id.card_playlist_title)
            cardPlaylistCount = it.findViewById(R.id.card_playlist_count)
        }
        mainActivity.findViewById<View>(R.id.card_recent)?.let {
            cardRecentImage = it.findViewById(R.id.img_recent_overlay)
            cardRecentTitle = it.findViewById(R.id.card_recent_title)
            cardRecentCount = it.findViewById(R.id.card_recent_count)
        }

        checkPermissions()
        // loadQuickActionCardData() is called in onResume after permissions are checked

        return view
    }

    // In onResume method:

    override fun onResume() {
        super.onResume()
        // Register receivers with proper flags for Android 14+
        val songChangeFilter = IntentFilter("SONG_CHANGED")
        songChangeFilter.addAction("SONG_STATE_CHANGED")
        val searchFilter = IntentFilter("SEARCH_QUERY_CHANGED")
        val sortFilter = IntentFilter("SORT_SONGS")
        val refreshFilter = IntentFilter("MAIN_DATA_REFRESH")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(songChangeReceiver, songChangeFilter, Context.RECEIVER_EXPORTED)
            requireActivity().registerReceiver(searchReceiver, searchFilter, Context.RECEIVER_EXPORTED)
            requireActivity().registerReceiver(sortReceiver, sortFilter, Context.RECEIVER_EXPORTED)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter, Context.RECEIVER_EXPORTED)
        } else {
            requireActivity().registerReceiver(songChangeReceiver, songChangeFilter)
            requireActivity().registerReceiver(searchReceiver, searchFilter)
            requireActivity().registerReceiver(sortReceiver, sortFilter)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter)
        }

        if (songList.isEmpty()) {
            checkPermissions()
        } else {
            loadSongs()
        }

        // 🚨 CRITICAL: This ensures the cards are updated whenever the user returns to the screen
        loadQuickActionCardData()
    }

    override fun onPause() {
        super.onPause()
        requireActivity().unregisterReceiver(songChangeReceiver)
        requireActivity().unregisterReceiver(searchReceiver)
        requireActivity().unregisterReceiver(sortReceiver)
        requireActivity().unregisterReceiver(refreshReceiver)
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    android.Manifest.permission.READ_MEDIA_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(android.Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                loadSongs()
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            } else {
                loadSongs()
            }
        }
    }

    private fun loadSongs() {
        songList.clear()
        songList.addAll(SongRepository.getAllSongs(requireContext()))

        // Sort based on View Mode to organize the list
        when (viewMode) {
            "artists" -> {
                songList.sortBy { it.artist.orEmpty().lowercase() }
            }
            "albums" -> {
                songList.sortBy { it.album.orEmpty().lowercase() }
            }
            "genres" -> {
                songList.sortBy { it.genre.orEmpty().lowercase() }
            }
            "songs" -> {
                sortSongs()
            }
        }

        if (viewMode != "songs") {
            filterSongs()
        }

        loadQuickActionCardData()
    }

    /**
     * Public function to load and update the data displayed on the quick action cards.
     * This is called by MainActivity when the MusicService is connected.
     */
    fun loadQuickActionCardData() {
        val context = context ?: return
        val allSongs = SongRepository.getAllSongs(context)

        // Get the music service to check the currently playing song
        val mainActivity = requireActivity() as MainActivity
        val musicService = mainActivity.getMusicService()

        // --- RECENT CARD UPDATE (Prioritize currently playing song) ---
        val recentIds = PreferenceManager.getRecentSongs(context)
        val recentCount = recentIds.size

        val currentlyPlayingSong = musicService?.getCurrentSong()

        // Determine which song's art to display: currently playing, or the last from history
        val songForRecentCard = if (currentlyPlayingSong != null) {
            currentlyPlayingSong
        } else {
            // Fallback: Get the most recent song from persistent history (ID at index 0 is most recent)
            allSongs.firstOrNull { it.id == recentIds.firstOrNull() }
        }

        // Update text
        cardRecentTitle?.text = "Recent"
        cardRecentCount?.text = context.resources.getQuantityString(R.plurals.song_count_plurals, recentCount, recentCount)

        // Update Image using Glide
        if (songForRecentCard != null) {
            val artUri = SongUtils.getAlbumArtUri(songForRecentCard.albumId)
            cardRecentImage?.let { imageView ->
                Glide.with(context)
                    .load(artUri)
                    .placeholder(R.drawable.default_album_art)
                    .error(R.drawable.default_album_art)
                    .centerCrop()
                    .into(imageView)
            }
        } else {
            cardRecentImage?.setImageResource(R.drawable.default_album_art)
        }

        // --- FAVORITES CARD UPDATE (Use last added favorite for album art) ---
        val favoriteIds = PreferenceManager.getFavorites(context)
        val favoriteCount = favoriteIds.size

        // Get the latest added favorite song (assuming list order is chronological)
        val lastFavoriteSongId = favoriteIds.lastOrNull()
        val latestFavoriteSong = allSongs.firstOrNull { it.id == lastFavoriteSongId }

        // Update text
        cardFavTitle?.text = "Favorites"
        cardFavCount?.text = context.resources.getQuantityString(R.plurals.song_count_plurals, favoriteCount, favoriteCount)

        // Update Image using Glide
        if (latestFavoriteSong != null) {
            val artUri = SongUtils.getAlbumArtUri(latestFavoriteSong.albumId)
            cardFavImage?.let { imageView ->
                Glide.with(context)
                    .load(artUri)
                    .placeholder(R.drawable.ic_favorite_filled)
                    .error(R.drawable.default_album_art)
                    .centerCrop()
                    .into(imageView)
            }
        } else {
            cardFavImage?.setImageResource(R.drawable.default_album_art)
        }

        // --- PLAYLISTS CARD UPDATE ---
        val playlists = PreferenceManager.getPlaylists(context)
        val playlistCount = playlists.size
        // Get the first playlist
        val firstPlaylist = playlists.firstOrNull()

        // Update text
        cardPlaylistTitle?.text = "Playlists"
        cardPlaylistCount?.text = context.resources.getQuantityString(R.plurals.playlist_count_plurals, playlistCount, playlistCount)

        // Update Image using Glide (using the art of the first song in the first playlist)
        val firstSongInPlaylist = firstPlaylist?.songIds?.firstOrNull()?.let { id ->
            allSongs.firstOrNull { it.id == id }
        }

        if (firstSongInPlaylist != null) {
            val artUri = SongUtils.getAlbumArtUri(firstSongInPlaylist.albumId)
            cardPlaylistImage?.let { imageView ->
                Glide.with(context)
                    .load(artUri)
                    .placeholder(R.drawable.ic_playlist)
                    .error(R.drawable.default_album_art)
                    .centerCrop()
                    .into(imageView)
            }
        } else {
            cardPlaylistImage?.setImageResource(R.drawable.default_album_art)
        }
    }


    private fun sortSongs() {
        if (viewMode == "songs") {
            when (currentSortType) {
                MainActivity.SortType.NAME_ASC -> {
                    songList.sortBy { it.title.lowercase() }
                }
                MainActivity.SortType.NAME_DESC -> {
                    songList.sortByDescending { it.title.lowercase() }
                }
                MainActivity.SortType.DATE_ADDED_ASC -> {
                    songList.sortBy { it.dateAdded }
                }
                MainActivity.SortType.DATE_ADDED_DESC -> {
                    songList.sortByDescending { it.dateAdded }
                }
                MainActivity.SortType.DURATION -> {
                    songList.sortByDescending { it.duration }
                }
            }
        }
        filterSongs()
    }

    private fun filterSongs() {
        filteredSongList = if (currentQuery.isBlank()) {
            songList.toMutableList()
        } else {
            songList.filter { song ->
                song.title.contains(currentQuery, true) ||
                        song.artist?.contains(currentQuery, true) == true ||
                        song.album?.contains(currentQuery, true) == true
            }.toMutableList()
        }

        updateAdapter()
    }

    private fun updateAdapter() {
        val adapter = SongAdapter(
            songs = filteredSongList,
            onItemClick = { pos -> openNowPlaying(pos) },
            onMenuClick = { pos, menuItem -> handleMenuAction(pos, menuItem) },
            isSongFavorite = { songId -> PreferenceManager.isFavorite(requireContext(), songId) }
        )
        recyclerView.adapter = adapter
    }

    private fun handleMenuAction(position: Int, menuItem: String) {
        val song = filteredSongList[position]
        when (menuItem) {
            "play" -> {
                openNowPlaying(position)
            }
            "add_to_queue" -> {
                Toast.makeText(requireContext(), "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
            }
            "add_to_playlist" -> {
                showAddToPlaylistDialog(song)
            }
            "toggle_favorite" -> {
                toggleFavorite(song)
            }
            "delete" -> {
                Toast.makeText(requireContext(), "Delete: ${song.title}", Toast.LENGTH_SHORT).show()
            }
            "share" -> {
                shareSong(song)
            }
        }
    }

    private fun toggleFavorite(song: Song) {
        if (PreferenceManager.isFavorite(requireContext(), song.id)) {
            PreferenceManager.removeFavorite(requireContext(), song.id)
            Toast.makeText(requireContext(), "Removed from Favorites: ${song.title}", Toast.LENGTH_SHORT).show()
        } else {
            PreferenceManager.addFavorite(requireContext(), song.id)
            Toast.makeText(requireContext(), "Added to Favorites: ${song.title}", Toast.LENGTH_SHORT).show()
        }
        updateAdapter()
        loadQuickActionCardData()
    }

    private fun showAddToPlaylistDialog(song: Song) {
        val playlists = PreferenceManager.getPlaylists(requireContext()).toMutableList()
        val playlistNames = playlists.map { it.name }.toMutableList()

        playlistNames.add(0, "+ Create New Playlist")

        AlertDialog.Builder(requireContext())
            .setTitle("Add to Playlist: ${song.title}")
            .setItems(playlistNames.toTypedArray()) { dialog, which ->
                if (which == 0) {
                    showCreateNewPlaylistDialog(song)
                } else {
                    val playlist = playlists[which - 1]

                    if (!playlist.songIds.contains(song.id)) {
                        PreferenceManager.addSongToPlaylist(requireContext(), playlist.id, song.id)
                        Toast.makeText(requireContext(), "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "${song.title} is already in ${playlist.name}", Toast.LENGTH_SHORT).show()
                    }
                }
                updateAdapter()
                loadQuickActionCardData()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showCreateNewPlaylistDialog(song: Song) {
        val editText = EditText(requireContext())
        editText.hint = "Playlist Name"

        AlertDialog.Builder(requireContext())
            .setTitle("Create New Playlist")
            .setView(editText)
            .setPositiveButton("Create") { dialog, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val newPlaylist = Playlist(
                        id = System.currentTimeMillis() + UUID.randomUUID().mostSignificantBits,
                        name = newName,
                        songIds = mutableListOf(song.id),
                        createdAt = System.currentTimeMillis()
                    )

                    val existingPlaylists = PreferenceManager.getPlaylists(requireContext()).toMutableList()
                    existingPlaylists.add(newPlaylist)
                    PreferenceManager.savePlaylists(requireContext(), existingPlaylists)

                    Toast.makeText(requireContext(), "Playlist '$newName' created and song added.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Playlist name cannot be empty.", Toast.LENGTH_SHORT).show()
                }
                updateAdapter()
                loadQuickActionCardData()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun shareSong(song: Song) {
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Check out \"${song.title}\" by ${song.artist ?: "Unknown Artist"}")
        }
        startActivity(Intent.createChooser(shareIntent, "Share Song"))
    }

    private fun openNowPlaying(position: Int) {
        val songToPlay = filteredSongList[position]
        requireContext().let {
            PreferenceManager.addRecentSong(it, songToPlay.id)
        }

        val service = (requireActivity() as MainActivity).getMusicService()
        service?.startPlayback(ArrayList(filteredSongList), position)

        (requireActivity() as MainActivity).navigateToNowPlaying()
    }
}