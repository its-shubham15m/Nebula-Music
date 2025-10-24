package com.shubhamgupta.nebula_music.fragments

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_music.MainActivity // Import MainActivity
import com.shubhamgupta.nebula_music.R
import com.shubhamgupta.nebula_music.adapters.SongAdapter
import com.shubhamgupta.nebula_music.models.Genre
import com.shubhamgupta.nebula_music.models.Song // Import Song
import com.shubhamgupta.nebula_music.utils.PreferenceManager

class GenreSongsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var genreNameView: TextView
    private lateinit var songCountView: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var tvEmpty: TextView
    private var currentGenre: Genre? = null
    private var genreSongsList = mutableListOf<Song>() // Use MutableList

    // Delete request launcher
    private lateinit var deleteResultLauncher: ActivityResultLauncher<IntentSenderRequest>

    private val playbackReceiver = object : BroadcastReceiver() { // Unchanged
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SONG_CHANGED", "PLAYBACK_STATE_CHANGED" -> {
                    currentGenre?.let { setupGenreSongs(it) }
                }
            }
        }
    }

    companion object { // Unchanged
        private const val ARG_GENRE = "genre"
        fun newInstance(genre: Genre): GenreSongsFragment {
            val fragment = GenreSongsFragment()
            val args = Bundle()
            args.putParcelable(ARG_GENRE, genre)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize delete launcher
        deleteResultLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(requireContext(), "Song deleted successfully", Toast.LENGTH_SHORT).show()
                refreshData() // Reload data after deletion
            } else {
                Toast.makeText(requireContext(), "Song could not be deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView( // Unchanged
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_genre_songs, container, false)
        initializeViews(view)
        return view
    }

    override fun onResume() { // Unchanged (except receiver flags)
        super.onResume()
        (requireActivity() as MainActivity).setDrawerLocked(true)
        val filter = IntentFilter().apply {
            addAction("SONG_CHANGED")
            addAction("PLAYBACK_STATE_CHANGED")
        }
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
        requireActivity().registerReceiver(playbackReceiver, filter, receiverFlags)
        currentGenre?.let { setupGenreSongs(it) }
    }

    override fun onPause() { // Unchanged
        super.onPause()
        (requireActivity() as MainActivity).setDrawerLocked(false)
        try {
            requireActivity().unregisterReceiver(playbackReceiver)
        } catch (e: Exception) { /* Ignore */ }
    }

    private fun initializeViews(view: View) { // Unchanged
        recyclerView = view.findViewById(R.id.recycler_view_genre_songs)
        genreNameView = view.findViewById(R.id.genre_name)
        songCountView = view.findViewById(R.id.genre_song_count)
        btnBack = view.findViewById(R.id.btn_back)
        tvEmpty = view.findViewById(R.id.tv_empty_genre_songs)
        recyclerView.layoutManager = LinearLayoutManager(context)

        currentGenre = arguments?.getParcelable(ARG_GENRE)
        currentGenre?.let { setupGenreSongs(it) }

        btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        val shuffleCard = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)
        shuffleCard.setOnClickListener {
            shuffleGenreSongs()
        }
    }

    // UPDATED: Use MutableList and correct adapter call
    private fun setupGenreSongs(genre: Genre) {
        genreNameView.text = genre.name
        songCountView.text = "${genre.songCount} songs"

        // Update local list
        genreSongsList.clear()
        genreSongsList.addAll(genre.songs)

        if (genreSongsList.isEmpty()) {
            recyclerView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.GONE
        } else {
            recyclerView.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.VISIBLE

            val adapter = SongAdapter(
                context = requireContext(),
                songs = genreSongsList, // Pass mutable list
                onItemClick = { pos -> openNowPlaying(pos) }, // Simplified call
                onDataChanged = { refreshData() },
                onDeleteRequest = { song -> requestDeleteSong(song) }
            )
            recyclerView.adapter = adapter
        }
    }

    // NEW: Handles delete request
    private fun requestDeleteSong(song: Song) {
        try {
            val intentSender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(requireContext().contentResolver, listOf(song.uri)).intentSender
            } else null

            if (intentSender != null) {
                val request = IntentSenderRequest.Builder(intentSender).build()
                deleteResultLauncher.launch(request)
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                try {
                    requireContext().contentResolver.delete(song.uri, null, null)
                    Toast.makeText(requireContext(), "Song deleted successfully", Toast.LENGTH_SHORT).show()
                    refreshData()
                } catch (e: SecurityException) {
                    if (e is RecoverableSecurityException) {
                        val request = IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                        deleteResultLauncher.launch(request)
                    } else throw e
                }
            } else {
                val deletedRows = requireContext().contentResolver.delete(song.uri, null, null)
                if (deletedRows > 0) {
                    Toast.makeText(requireContext(), "Song deleted successfully", Toast.LENGTH_SHORT).show()
                    refreshData()
                } else {
                    Toast.makeText(requireContext(), "Could not delete song (pre-Q).", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("GenreSongsFragment", "Error requesting delete for ${song.uri}", e)
            Toast.makeText(requireContext(), "Error requesting deletion.", Toast.LENGTH_SHORT).show()
        }
    }

    // UPDATED: Use genreSongsList
    private fun shuffleGenreSongs() {
        if (genreSongsList.isNotEmpty()) {
            val shuffledSongs = genreSongsList.shuffled()
            val service = (requireActivity() as MainActivity).getMusicService()
            service?.startPlayback(ArrayList(shuffledSongs), 0)
            service?.toggleShuffle()
            (requireActivity() as MainActivity).navigateToNowPlaying()
            showToast("Shuffling ${genreSongsList.size} ${currentGenre?.name ?: "Genre"} songs")
        } else {
            showToast("No songs to shuffle")
        }
    }

    // UPDATED: Use genreSongsList
    private fun openNowPlaying(position: Int) {
        if (position < 0 || position >= genreSongsList.size) return
        val songToPlay = genreSongsList[position]
        PreferenceManager.addRecentSong(requireContext(), songToPlay.id)
        val service = (requireActivity() as MainActivity).getMusicService()
        service?.startPlayback(ArrayList(genreSongsList), position) // Use local list
        (requireActivity() as MainActivity).navigateToNowPlaying()
    }

    private fun showToast(message: String) { // Unchanged
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun refreshData() { // Unchanged
        currentGenre?.let {
            // Re-fetch or update genre data if necessary
            setupGenreSongs(it)
        }
    }
}