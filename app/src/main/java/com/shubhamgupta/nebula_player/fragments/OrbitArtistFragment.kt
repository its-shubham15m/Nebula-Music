package com.shubhamgupta.nebula_player.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.adapters.SongAdapter
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.viewmodel.OrbitViewModel

class OrbitArtistFragment : Fragment() {

    private lateinit var viewModel: OrbitViewModel
    private var artistName: String = ""
    private lateinit var songAdapter: SongAdapter
    private var songList = ArrayList<Song>()

    companion object {
        fun newInstance(artistName: String): OrbitArtistFragment {
            val fragment = OrbitArtistFragment()
            val args = Bundle()
            args.putString("ARTIST_NAME", artistName)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        artistName = arguments?.getString("ARTIST_NAME") ?: "Artist"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_orbit_artist, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[OrbitViewModel::class.java]

        setupUI(view)
        setupRecyclerView(view)
        setupObservers(view)

        // Load data specific to artist
        viewModel.loadArtistDetails(artistName)
    }

    private fun setupUI(view: View) {
        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<TextView>(R.id.tv_toolbar_title).text = artistName
        view.findViewById<TextView>(R.id.tv_artist_name).text = artistName
        view.findViewById<TextView>(R.id.tv_artist_desc).text = "Artist Mix"

        // Play All Button
        view.findViewById<View>(R.id.btn_play_all_card).setOnClickListener {
            if(songList.isNotEmpty()) {
                playMusic(songList, 0)
            }
        }

        // Shuffle Button
        view.findViewById<View>(R.id.btn_shuffle_card).setOnClickListener {
            if(songList.isNotEmpty()) {
                val shuffled = ArrayList(songList)
                shuffled.shuffle()
                playMusic(shuffled, 0)
            }
        }
    }

    private fun setupRecyclerView(view: View) {
        val rv = view.findViewById<RecyclerView>(R.id.rv_songs)
        rv.layoutManager = LinearLayoutManager(context)
        songAdapter = SongAdapter(requireContext(),
            onItemClick = { pos -> playMusic(songList, pos) },
            onDataChanged = {},
            onDeleteRequest = {}
        )
        rv.adapter = songAdapter
    }

    private fun setupObservers(view: View) {
        // Observe Songs
        viewModel.selectedPlaylistSongs.observe(viewLifecycleOwner) { songs ->
            songList = ArrayList(songs)
            songAdapter.submitList(songs)

            if (songs.isNotEmpty()) {
                val durationMin = (songs.sumOf { it.duration } / 1000) / 60
                val statsText = "${songs.size} Songs • ${durationMin} min"
                view.findViewById<TextView>(R.id.tv_playlist_stats)?.text = statsText
            } else {
                view.findViewById<TextView>(R.id.tv_playlist_stats)?.text = "0 Songs"
            }
        }

        // Observe Image (Scraped from web)
        viewModel.selectedArtistImage.observe(viewLifecycleOwner) { imageUrl ->
            val imgView = view.findViewById<ImageView>(R.id.img_artist_art)
            if (imageUrl.isNotEmpty()) {
                Glide.with(this)
                    .load(imageUrl)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .placeholder(R.drawable.default_album_art)
                    .error(R.drawable.default_album_art)
                    .into(imgView)
            } else {
                // Keep default if scraping fails or is loading
                imgView.setImageResource(R.drawable.default_album_art)
            }
        }
    }

    private fun playMusic(songs: ArrayList<Song>, index: Int) {
        val mainActivity = requireActivity() as? MainActivity
        mainActivity?.getMusicService()?.startPlayback(songs, index)
        mainActivity?.showNowPlayingPage()
    }
}