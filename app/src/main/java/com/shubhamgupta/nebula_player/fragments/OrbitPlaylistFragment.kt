package com.shubhamgupta.nebula_player.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.adapters.SongAdapter
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.viewmodel.OrbitViewModel

class OrbitPlaylistFragment : Fragment() {

    private lateinit var viewModel: OrbitViewModel
    private var playlistTitle: String = ""
    private var playlistMood: String = ""
    private lateinit var songAdapter: SongAdapter
    private var songList = ArrayList<Song>()

    companion object {
        fun newInstance(title: String, mood: String): OrbitPlaylistFragment {
            val fragment = OrbitPlaylistFragment()
            val args = Bundle()
            args.putString("TITLE", title)
            args.putString("MOOD", mood)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlistTitle = arguments?.getString("TITLE") ?: "Playlist"
        playlistMood = arguments?.getString("MOOD") ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_orbit_playlist, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[OrbitViewModel::class.java]

        setupUI(view)
        setupRecyclerView(view)
        setupObservers()

        // Load data
        viewModel.loadPlaylistDetails(playlistMood, playlistTitle)
    }

    private fun setupUI(view: View) {
        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        toolbar.title = playlistTitle

        view.findViewById<TextView>(R.id.tv_playlist_title).text = playlistTitle

        // Refresh Button
        view.findViewById<View>(R.id.btn_refresh).setOnClickListener {
            viewModel.loadPlaylistDetails(playlistMood, playlistTitle, forceRefresh = true)
        }

        // Shuffle Button
        view.findViewById<View>(R.id.btn_shuffle).setOnClickListener {
            if(songList.isNotEmpty()) {
                val shuffled = ArrayList(songList)
                shuffled.shuffle()
                playMusic(shuffled, 0)
            }
        }

        // FAB Play
        view.findViewById<FloatingActionButton>(R.id.fab_play).setOnClickListener {
            if(songList.isNotEmpty()) playMusic(songList, 0)
        }
    }

    private fun setupRecyclerView(view: View) {
        val rv = view.findViewById<RecyclerView>(R.id.rv_songs)
        rv.layoutManager = LinearLayoutManager(context)

        // Using existing SongAdapter
        songAdapter = SongAdapter(requireContext(),
            onItemClick = { pos -> playMusic(songList, pos) },
            onDataChanged = {},
            onDeleteRequest = {} // Read-only view
        )
        rv.adapter = songAdapter
    }

    private fun setupRecommendations(view: View, artists: List<String>) {
        val rvRec = view.findViewById<RecyclerView>(R.id.rv_recommendations)
        // Simple horizontal adapter for artist pills/cards
        // For brevity, using a basic anonymous adapter logic here,
        // ideally create a 'RecommendationAdapter'
        rvRec.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_orbit_card, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val artist = artists[position]
                holder.itemView.findViewById<TextView>(R.id.tv_title).text = artist
                holder.itemView.findViewById<TextView>(R.id.tv_subtitle).text = "Artist"
                holder.itemView.findViewById<ImageView>(R.id.img_art).setImageResource(R.drawable.default_album_art)
            }
            override fun getItemCount() = artists.size
        }
    }

    private fun setupObservers() {
        viewModel.selectedPlaylistSongs.observe(viewLifecycleOwner) { songs ->
            songList = ArrayList(songs)
            songAdapter.submitList(songs)

            // Update Stats
            val durationMin = (songs.sumOf { it.duration } / 1000) / 60
            val statsText = "${songs.size} Songs • ${durationMin} min"
            view?.findViewById<TextView>(R.id.tv_playlist_stats)?.text = statsText

            // Update Art (use first song's art)
            if(songs.isNotEmpty()) {
                val img = view?.findViewById<ImageView>(R.id.img_playlist_art)
                if (img != null) {
                    Glide.with(this).load(songs[0].uri).placeholder(R.drawable.default_album_art).into(img)
                }
            }
        }

        viewModel.recommendedArtists.observe(viewLifecycleOwner) { artists ->
            view?.let { setupRecommendations(it, artists) }
        }
    }

    private fun playMusic(songs: ArrayList<Song>, index: Int) {
        val mainActivity = requireActivity() as? MainActivity
        mainActivity?.getMusicService()?.startPlayback(songs, index)
        mainActivity?.showNowPlayingPage()
    }
}