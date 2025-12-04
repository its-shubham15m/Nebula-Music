package com.shubhamgupta.nebula_player.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.google.android.material.card.MaterialCardView
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.VideoPlayerActivity
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.models.Video
import com.shubhamgupta.nebula_player.utils.SongUtils
import com.shubhamgupta.nebula_player.viewmodel.OrbitViewModel

class OrbitFragment : Fragment() {

    private lateinit var viewModel: OrbitViewModel
    private var loadingOverlay: FrameLayout? = null

    private lateinit var chipAll: MaterialCardView
    private lateinit var chipMusic: MaterialCardView
    private lateinit var chipVideo: MaterialCardView
    private lateinit var containerTimeWarp: LinearLayout
    private lateinit var containerAiPlaylists: LinearLayout
    private lateinit var containerVideos: LinearLayout
    private lateinit var cardLastWatched: MaterialCardView

    private var currentFilter = "ALL"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_orbit_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[OrbitViewModel::class.java] // Use Activity Scope to share with Detail Fragment

        initViews(view)
        setupInteractions()
        observeViewModel()

        viewModel.loadOrbitData()
    }

    private fun initViews(view: View) {
        val tvGreeting = view.findViewById<TextView>(R.id.tv_orbit_greeting)
        viewModel.greeting.observe(viewLifecycleOwner) { tvGreeting.text = it }

        loadingOverlay = view.findViewById(R.id.loading_overlay)
        chipAll = view.findViewById(R.id.chip_all)
        chipMusic = view.findViewById(R.id.chip_music)
        chipVideo = view.findViewById(R.id.chip_video)
        cardLastWatched = view.findViewById(R.id.card_last_watched)
        containerTimeWarp = view.findViewById(R.id.container_time_warp)
        containerAiPlaylists = view.findViewById(R.id.container_echo_chamber)
        containerVideos = view.findViewById(R.id.container_stellar)
    }

    private fun setupInteractions() {
        chipAll.setOnClickListener { updateFilter("ALL") }
        chipMusic.setOnClickListener { updateFilter("MUSIC") }
        chipVideo.setOnClickListener { updateFilter("VIDEO") }

        cardLastWatched.setOnClickListener {
            val video = viewModel.lastWatchedData.value
            if (video != null) playVideo(video)
        }
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            loadingOverlay?.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
        viewModel.timeWarpData.observe(viewLifecycleOwner) { items ->
            if (currentFilter != "VIDEO") populateSongs(containerTimeWarp, items.filterIsInstance<Song>())
        }
        viewModel.aiPlaylists.observe(viewLifecycleOwner) { playlists ->
            if (currentFilter != "VIDEO") populateAiPlaylists(containerAiPlaylists, playlists)
        }
        viewModel.videosData.observe(viewLifecycleOwner) { videos ->
            if (currentFilter != "MUSIC") populateVideos(containerVideos, videos)
        }
        viewModel.lastWatchedData.observe(viewLifecycleOwner) { video ->
            if (video != null && currentFilter != "MUSIC") {
                cardLastWatched.visibility = View.VISIBLE
                view?.findViewById<TextView>(R.id.tv_last_watched_title)?.text = video.title
                val img = view?.findViewById<ImageView>(R.id.img_last_watched_thumb)
                if (img != null) Glide.with(this).load(video.uri).into(img)
            } else {
                cardLastWatched.visibility = View.GONE
            }
        }
    }

    private fun populateSongs(container: LinearLayout, songs: List<Song>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        songs.forEach { song ->
            val view = inflater.inflate(R.layout.item_orbit_card, container, false) // Using item_orbit_card
            view.findViewById<TextView>(R.id.tv_title).text = song.title
            view.findViewById<TextView>(R.id.tv_subtitle).text = song.artist ?: "Unknown"
            val img = view.findViewById<ImageView>(R.id.img_art)
            Glide.with(this).load(SongUtils.getAlbumArtUri(song.albumId))
                .transform(CenterCrop(), RoundedCorners(16)).placeholder(R.drawable.default_album_art).into(img)

            view.setOnClickListener {
                viewModel.getSimilarSongs(song) { queue ->
                    playMusicList(queue as ArrayList<Song>, 0)
                }
            }
            container.addView(view)
        }
    }

    private fun populateAiPlaylists(container: LinearLayout, playlists: List<OrbitViewModel.OrbitCard>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        playlists.forEach { card ->
            val view = inflater.inflate(R.layout.item_orbit_card, container, false) // Using item_orbit_card
            view.findViewById<TextView>(R.id.tv_title).text = card.title
            view.findViewById<TextView>(R.id.tv_subtitle).text = "AI Curated"
            val img = view.findViewById<ImageView>(R.id.img_art)
            img.setImageResource(R.drawable.default_album_art)
            img.setColorFilter(ContextCompat.getColor(requireContext(), R.color.purple_200), android.graphics.PorterDuff.Mode.MULTIPLY)

            view.setOnClickListener {
                // Navigate to Detail Fragment instead of playing immediately
                openPlaylistDetails(card)
            }
            container.addView(view)
        }
    }

    private fun populateVideos(container: LinearLayout, videos: List<Video>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        videos.forEach { video ->
            val view = inflater.inflate(R.layout.item_orbit_video, container, false) // Using new horizontal video layout
            view.findViewById<TextView>(R.id.video_title).text = video.title
            view.findViewById<TextView>(R.id.video_file_info).text = "Video"
            view.findViewById<TextView>(R.id.video_duration).text = SongUtils.formatDuration(video.duration)

            val img = view.findViewById<ImageView>(R.id.video_thumbnail)
            Glide.with(this).load(video.uri).transform(CenterCrop(), RoundedCorners(16)).into(img)

            view.setOnClickListener { playVideo(video) }
            container.addView(view)
        }
    }

    private fun openPlaylistDetails(card: OrbitViewModel.OrbitCard) {
        val fragment = OrbitPlaylistFragment.newInstance(card.title, card.queryMood)
        parentFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
            replace(R.id.home_content_container, fragment) // Make sure ID matches host container
            addToBackStack(null)
        }
    }

    private fun playMusicList(songs: ArrayList<Song>, index: Int) {
        val mainActivity = requireActivity() as? MainActivity
        mainActivity?.getMusicService()?.startPlayback(songs, index)
        mainActivity?.showNowPlayingPage()
    }

    @OptIn(UnstableApi::class)
    private fun playVideo(video: Video) {
        // FIXED: Using VideoPlayerActivity class directly with VIDEO_ID
        try {
            val intent = Intent(requireContext(), VideoPlayerActivity::class.java)
            intent.putExtra("VIDEO_ID", video.id)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Error opening video player", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFilter(filter: String) {
        currentFilter = filter
        val activeColor = ContextCompat.getColor(requireContext(), R.color.colorSecondary)
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.colorSurfaceVariant)
        chipAll.setCardBackgroundColor(if(filter == "ALL") activeColor else inactiveColor)
        chipMusic.setCardBackgroundColor(if(filter == "MUSIC") activeColor else inactiveColor)
        chipVideo.setCardBackgroundColor(if(filter == "VIDEO") activeColor else inactiveColor)
        viewModel.loadOrbitData()
    }
}