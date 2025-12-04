package com.shubhamgupta.nebula_player.fragments

import android.content.Intent
import android.net.Uri
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

    // Headers to hide/show based on filter
    private lateinit var headerTimeWarp: TextView
    private lateinit var headerAiPlaylists: TextView
    private lateinit var headerVideos: TextView

    private var currentFilter = "ALL"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_orbit_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[OrbitViewModel::class.java]

        initViews(view)
        setupInteractions()
        observeViewModel()

        viewModel.loadOrbitData()
    }

    private fun initViews(view: View) {
        val tvGreeting = view.findViewById<TextView>(R.id.tv_orbit_greeting)
        val tvUsername = view.findViewById<TextView>(R.id.tv_orbit_username) // Added this

        // Fix: Split the greeting string to separate Bold Time and Normal Name
        viewModel.greeting.observe(viewLifecycleOwner) { fullGreeting ->
            if (fullGreeting.contains(",")) {
                val parts = fullGreeting.split(",", limit = 2)
                tvGreeting.text = parts[0] + "," // "Good Evening," (Bold)
                tvUsername.text = parts[1]       // " User" (Normal)
            } else {
                tvGreeting.text = fullGreeting
                tvUsername.text = ""
            }
        }

        loadingOverlay = view.findViewById(R.id.loading_overlay)
        chipAll = view.findViewById(R.id.chip_all)
        chipMusic = view.findViewById(R.id.chip_music)
        chipVideo = view.findViewById(R.id.chip_video)
        cardLastWatched = view.findViewById(R.id.card_last_watched)

        containerTimeWarp = view.findViewById(R.id.container_time_warp)
        containerAiPlaylists = view.findViewById(R.id.container_echo_chamber)
        containerVideos = view.findViewById(R.id.container_stellar)

        headerTimeWarp = view.findViewById(R.id.tv_time_warp_header)
        headerAiPlaylists = view.findViewById(R.id.tv_echo_chamber_header)
        headerVideos = view.findViewById(R.id.tv_stellar_header)
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
            if (currentFilter != "VIDEO") {
                headerTimeWarp.visibility = View.VISIBLE
                (containerTimeWarp.parent as? View)?.visibility = View.VISIBLE
                populateSongs(containerTimeWarp, items.filterIsInstance<Song>())
            } else {
                headerTimeWarp.visibility = View.GONE
                (containerTimeWarp.parent as? View)?.visibility = View.GONE
            }
        }

        viewModel.aiPlaylists.observe(viewLifecycleOwner) { playlists ->
            if (currentFilter != "VIDEO") {
                headerAiPlaylists.visibility = View.VISIBLE
                (containerAiPlaylists.parent as? View)?.visibility = View.VISIBLE
                populateAiPlaylists(containerAiPlaylists, playlists)
            } else {
                headerAiPlaylists.visibility = View.GONE
                (containerAiPlaylists.parent as? View)?.visibility = View.GONE
            }
        }

        viewModel.videosData.observe(viewLifecycleOwner) { videos ->
            if (currentFilter != "MUSIC") {
                headerVideos.visibility = View.VISIBLE
                (containerVideos.parent as? View)?.visibility = View.VISIBLE
                populateVideos(containerVideos, videos)
            } else {
                headerVideos.visibility = View.GONE
                (containerVideos.parent as? View)?.visibility = View.GONE
            }
        }

        viewModel.lastWatchedData.observe(viewLifecycleOwner) { video ->
            if (video != null && currentFilter == "VIDEO") {
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
            val view = inflater.inflate(R.layout.item_orbit_card, container, false)
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
            val view = inflater.inflate(R.layout.item_orbit_card, container, false)

            // Set Title
            view.findViewById<TextView>(R.id.tv_title).text = card.title

            // Set Tagline (Subtitle)
            view.findViewById<TextView>(R.id.tv_subtitle).text = card.tagline

            // Load specific Image from Assets
            val img = view.findViewById<ImageView>(R.id.img_art)
            val assetPath = "file:///android_asset/playlists/${card.imageName}"

            Glide.with(this)
                .load(Uri.parse(assetPath))
                .transform(CenterCrop(), RoundedCorners(16))
                .placeholder(R.drawable.default_album_art)
                .error(R.drawable.default_album_art)
                .into(img)

            view.setOnClickListener {
                openPlaylistDetails(card)
            }
            container.addView(view)
        }
    }

    private fun populateVideos(container: LinearLayout, videos: List<Video>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        videos.forEach { video ->
            val view = inflater.inflate(R.layout.item_orbit_video, container, false)
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
        val fragment = OrbitPlaylistFragment.newInstance(
            title = card.title,
            mood = card.queryMood,
            tagline = card.tagline,
            imageName = card.imageName
        )
        parentFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
            replace(R.id.home_content_container, fragment)
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

        val activeCardColor = ContextCompat.getColor(requireContext(), R.color.colorSurface)
        val inactiveCardColor = ContextCompat.getColor(requireContext(), android.R.color.transparent)

        chipAll.setCardBackgroundColor(inactiveCardColor)
        chipMusic.setCardBackgroundColor(inactiveCardColor)
        chipVideo.setCardBackgroundColor(inactiveCardColor)

        chipAll.strokeWidth = 0
        chipMusic.strokeWidth = 0
        chipVideo.strokeWidth = 0

        val activeChip = when(filter) {
            "MUSIC" -> chipMusic
            "VIDEO" -> chipVideo
            else -> chipAll
        }

        activeChip.setCardBackgroundColor(activeCardColor)
        activeChip.strokeWidth = 2

        // Trigger UI update manually to ensure visibility toggles immediately
        observeViewModel()
    }
}