package com.shubhamgupta.nebula_player.fragments

import android.net.Uri
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
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.adapters.SongAdapter
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.utils.SongUtils
import com.shubhamgupta.nebula_player.viewmodel.OrbitViewModel

class OrbitPlaylistFragment : Fragment() {

    private lateinit var viewModel: OrbitViewModel
    private var playlistTitle: String = ""
    private var playlistMood: String = ""
    private var playlistTagline: String = ""
    private var playlistImageName: String = ""

    private lateinit var songAdapter: SongAdapter
    private var songList = ArrayList<Song>()

    companion object {
        fun newInstance(title: String, mood: String, tagline: String, imageName: String): OrbitPlaylistFragment {
            val fragment = OrbitPlaylistFragment()
            val args = Bundle()
            args.putString("TITLE", title)
            args.putString("MOOD", mood)
            args.putString("TAGLINE", tagline)
            args.putString("IMG_NAME", imageName)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlistTitle = arguments?.getString("TITLE") ?: "Playlist"
        playlistMood = arguments?.getString("MOOD") ?: ""
        playlistTagline = arguments?.getString("TAGLINE") ?: "Curated for you"
        playlistImageName = arguments?.getString("IMG_NAME") ?: ""
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

        // Load data - The ViewModel will now ignore this if data is already present
        viewModel.loadPlaylistDetails(playlistMood, playlistTitle)
    }

    private fun setupUI(view: View) {
        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<TextView>(R.id.tv_toolbar_title).text = playlistTitle
        view.findViewById<TextView>(R.id.tv_playlist_title).text = playlistTitle
        view.findViewById<TextView>(R.id.tv_playlist_desc).text = playlistTagline

        val imgPlaylist = view.findViewById<ImageView>(R.id.img_playlist_art)
        if (playlistImageName.isNotEmpty()) {
            val assetPath = "file:///android_asset/playlists/$playlistImageName"
            Glide.with(this)
                .load(Uri.parse(assetPath))
                .placeholder(R.drawable.default_album_art)
                .into(imgPlaylist)
        }

        view.findViewById<View>(R.id.btn_refresh).setOnClickListener {
            viewModel.loadPlaylistDetails(playlistMood, playlistTitle, forceRefresh = true)
        }

        view.findViewById<View>(R.id.btn_shuffle_card).setOnClickListener {
            if(songList.isNotEmpty()) {
                val shuffled = ArrayList(songList)
                shuffled.shuffle()
                playMusic(shuffled, 0)
            }
        }

        view.findViewById<View>(R.id.btn_play_all_card).setOnClickListener {
            if(songList.isNotEmpty()) {
                playMusic(songList, 0)
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

    private fun setupRecommendations(view: View, songs: List<Song>) {
        val rvRec = view.findViewById<RecyclerView>(R.id.rv_recommendations)
        rvRec.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_orbit_card, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val song = songs[position]

                holder.itemView.findViewById<TextView>(R.id.tv_title).text = song.title
                holder.itemView.findViewById<TextView>(R.id.tv_subtitle).text = song.artist ?: "Unknown"

                val img = holder.itemView.findViewById<ImageView>(R.id.img_art)
                Glide.with(holder.itemView.context)
                    .load(SongUtils.getAlbumArtUri(song.albumId))
                    .transform(CenterCrop(), RoundedCorners(16))
                    .placeholder(R.drawable.default_album_art)
                    .into(img)

                holder.itemView.setOnClickListener {
                    playMusic(arrayListOf(song), 0)
                }
            }
            override fun getItemCount() = songs.size
        }
    }

    private fun setupObservers() {
        viewModel.selectedPlaylistSongs.observe(viewLifecycleOwner) { songs ->
            songList = ArrayList(songs)
            songAdapter.submitList(songs)

            if (songs.isNotEmpty()) {
                val durationMin = (songs.sumOf { it.duration } / 1000) / 60
                val statsText = "${songs.size} Songs • ${durationMin} min"
                view?.findViewById<TextView>(R.id.tv_playlist_stats)?.text = statsText
            } else {
                view?.findViewById<TextView>(R.id.tv_playlist_stats)?.text = "0 Songs • 0 min"
            }
        }

        viewModel.recommendedSongs.observe(viewLifecycleOwner) { songs ->
            view?.let { setupRecommendations(it, songs) }
        }
    }

    private fun playMusic(songs: ArrayList<Song>, index: Int) {
        val mainActivity = requireActivity() as? MainActivity
        mainActivity?.getMusicService()?.startPlayback(songs, index)
        mainActivity?.showNowPlayingPage()
    }
}