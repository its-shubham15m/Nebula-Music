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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
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
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private lateinit var chipAll: MaterialCardView
    private lateinit var chipMusic: MaterialCardView
    private lateinit var chipVideo: MaterialCardView

    // Containers
    private lateinit var containerTimeWarp: LinearLayout
    private lateinit var containerAiPlaylists: LinearLayout
    private lateinit var containerVideos: LinearLayout
    private lateinit var containerArtists: LinearLayout

    // Wrappers
    private lateinit var mainContentContainer: LinearLayout
    private lateinit var layoutHeaderBlock: LinearLayout
    private lateinit var layoutSectionTimeWarp: LinearLayout
    private lateinit var layoutSectionArtists: LinearLayout
    private lateinit var layoutSectionEcho: LinearLayout
    private lateinit var layoutSectionStellar: LinearLayout

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

        if (!viewModel.isDataLoaded) {
            viewModel.loadOrbitData()
        }
    }

    private fun initViews(view: View) {
        swipeRefresh = view.findViewById(R.id.swipe_refresh)
        loadingOverlay = view.findViewById(R.id.loading_overlay)

        val tvGreeting = view.findViewById<TextView>(R.id.tv_orbit_greeting)
        val tvUsername = view.findViewById<TextView>(R.id.tv_orbit_username)

        viewModel.greeting.observe(viewLifecycleOwner) { fullGreeting ->
            if (fullGreeting.contains(",")) {
                val parts = fullGreeting.split(",", limit = 2)
                tvGreeting.text = parts[0] + ","
                tvUsername.text = parts[1]
            } else {
                tvGreeting.text = fullGreeting
                tvUsername.text = ""
            }
        }

        chipAll = view.findViewById(R.id.chip_all)
        chipMusic = view.findViewById(R.id.chip_music)
        chipVideo = view.findViewById(R.id.chip_video)

        containerTimeWarp = view.findViewById(R.id.container_time_warp)
        containerAiPlaylists = view.findViewById(R.id.container_echo_chamber)
        containerVideos = view.findViewById(R.id.container_stellar)
        containerArtists = view.findViewById(R.id.container_artists)

        mainContentContainer = view.findViewById(R.id.main_content_container)
        layoutHeaderBlock = view.findViewById(R.id.layout_header_block)
        layoutSectionTimeWarp = view.findViewById(R.id.layout_section_time_warp)
        layoutSectionArtists = view.findViewById(R.id.layout_section_artists)
        layoutSectionEcho = view.findViewById(R.id.layout_section_echo_chamber)
        layoutSectionStellar = view.findViewById(R.id.layout_section_stellar)
    }

    private fun setupInteractions() {
        chipAll.setOnClickListener { updateFilter("ALL") }
        chipMusic.setOnClickListener { updateFilter("MUSIC") }
        chipVideo.setOnClickListener { updateFilter("VIDEO") }

        swipeRefresh.setOnRefreshListener {
            viewModel.loadOrbitData(forceRefresh = true)
            randomizeSectionOrder()
        }
    }

    private fun randomizeSectionOrder() {
        mainContentContainer.removeView(layoutSectionTimeWarp)
        mainContentContainer.removeView(layoutSectionArtists)
        mainContentContainer.removeView(layoutSectionEcho)
        mainContentContainer.removeView(layoutSectionStellar)

        val sections = listOf(
            layoutSectionTimeWarp,
            layoutSectionArtists,
            layoutSectionEcho,
            layoutSectionStellar
        ).shuffled()

        sections.forEach { section ->
            mainContentContainer.addView(section)
        }
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if(isLoading && !swipeRefresh.isRefreshing) {
                loadingOverlay?.visibility = View.VISIBLE
            } else {
                loadingOverlay?.visibility = View.GONE
                if(!isLoading) swipeRefresh.isRefreshing = false
            }
        }

        viewModel.timeWarpData.observe(viewLifecycleOwner) { items ->
            if (currentFilter != "VIDEO") {
                layoutSectionTimeWarp.visibility = View.VISIBLE
                populateSongs(containerTimeWarp, items.filterIsInstance<Song>())
            } else {
                layoutSectionTimeWarp.visibility = View.GONE
            }
        }

        viewModel.artistData.observe(viewLifecycleOwner) { artists ->
            if (currentFilter != "VIDEO") {
                layoutSectionArtists.visibility = View.VISIBLE
                populateArtists(containerArtists, artists)
            } else {
                layoutSectionArtists.visibility = View.GONE
            }
        }

        viewModel.aiPlaylists.observe(viewLifecycleOwner) { playlists ->
            if (currentFilter != "VIDEO") {
                layoutSectionEcho.visibility = View.VISIBLE
                populateAiPlaylists(containerAiPlaylists, playlists)
            } else {
                layoutSectionEcho.visibility = View.GONE
            }
        }

        viewModel.videosData.observe(viewLifecycleOwner) { videos ->
            if (currentFilter != "MUSIC") {
                layoutSectionStellar.visibility = View.VISIBLE
                populateVideos(containerVideos, videos)
            } else {
                layoutSectionStellar.visibility = View.GONE
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

    private fun populateArtists(container: LinearLayout, artists: List<OrbitViewModel.ArtistCard>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        artists.forEach { artist ->
            val view = inflater.inflate(R.layout.item_orbit_card, container, false)
            view.findViewById<TextView>(R.id.tv_title).text = artist.name
            view.findViewById<TextView>(R.id.tv_subtitle).text = "${artist.songCount} Songs"
            val img = view.findViewById<ImageView>(R.id.img_art)

            if (artist.imageUrl.isNotEmpty()) {
                Glide.with(this)
                    .load(artist.imageUrl)
                    .transform(CenterCrop(), RoundedCorners(16))
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .placeholder(R.drawable.default_album_art)
                    .error(R.drawable.default_album_art)
                    .into(img)
            } else {
                img.setImageResource(R.drawable.default_album_art)
            }

            view.setOnClickListener {
                openArtistDetails(artist.name)
            }
            container.addView(view)
        }
    }

    private fun populateAiPlaylists(container: LinearLayout, playlists: List<OrbitViewModel.OrbitCard>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        playlists.forEach { card ->
            val view = inflater.inflate(R.layout.item_orbit_card, container, false)
            view.findViewById<TextView>(R.id.tv_title).text = card.title
            view.findViewById<TextView>(R.id.tv_subtitle).text = card.tagline
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

    private fun openArtistDetails(artistName: String) {
        val fragment = OrbitArtistFragment.newInstance(artistName)
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
        observeViewModel()
    }
}