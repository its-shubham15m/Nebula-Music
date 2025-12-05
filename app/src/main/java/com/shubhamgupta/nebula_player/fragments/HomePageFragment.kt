package com.shubhamgupta.nebula_player.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R

class HomePageFragment : Fragment() {

    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var contentContainer: View

    // Track the currently active fragment to hide it when switching
    private var activeFragment: Fragment? = null

    // Tags for child fragments
    private val TAG_MUSIC = "music_page"
    private val TAG_VIDEO = "video_page"
    private val TAG_SEARCH = "search_page"
    private val TAG_ORBIT = "orbit_page"

    // Store the current tab ID. Default to Music.
    private var currentTabId: Int = R.id.nav_music

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bottomNavigationView = view.findViewById(R.id.bottom_navigation)
        contentContainer = view.findViewById(R.id.home_content_container)

        // --- Handle Window Insets ---
        ViewCompat.setOnApplyWindowInsetsListener(bottomNavigationView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = systemBars.bottom)
            insets
        }

        // Handle Keyboard (IME) visibility
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisible) {
                bottomNavigationView.visibility = View.GONE
                (requireActivity() as? MainActivity)?.setMiniPlayerBottomMargin(0)
            } else {
                if ((requireActivity() as? MainActivity)?.isDrawerLocked() == false) {
                    bottomNavigationView.visibility = View.VISIBLE
                } else {
                    bottomNavigationView.visibility = View.VISIBLE
                }
                bottomNavigationView.post { updateMiniPlayerPosition() }
            }
            insets
        }

        bottomNavigationView.doOnLayout {
            updateMiniPlayerPosition()
        }

        // Setup Listener
        bottomNavigationView.setOnItemSelectedListener { item ->
            if (currentTabId != item.itemId) {
                currentTabId = item.itemId
                handleTabSelection(item.itemId)
            }
            true
        }

        // Handle double-click (Reselection)
        bottomNavigationView.setOnItemReselectedListener { item ->
            when (item.itemId) {
                R.id.nav_search -> {
                    val fragment = childFragmentManager.findFragmentByTag(TAG_SEARCH) as? SearchFragment
                    fragment?.focusSearchInput()
                }
                R.id.nav_music -> {
                    val fragment = childFragmentManager.findFragmentByTag(TAG_MUSIC) as? MusicPageFragment
                    fragment?.switchToSongsTab()
                }
            }
        }

        // Restore state if available
        if (savedInstanceState != null) {
            currentTabId = savedInstanceState.getInt("LAST_SELECTED_TAB", R.id.nav_music)
        }

        // FIX: Manually load the fragment for the current tab.
        // We do this explicitly because setting 'selectedItemId' below might NOT trigger
        // the listener if the ID is the same as the default (first item), causing a black screen.
        handleTabSelection(currentTabId)

        // Sync the visual state of the BottomNavigation
        if (bottomNavigationView.selectedItemId != currentTabId) {
            bottomNavigationView.selectedItemId = currentTabId
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("LAST_SELECTED_TAB", currentTabId)
    }

    private fun handleTabSelection(itemId: Int) {
        when (itemId) {
            R.id.nav_music -> {
                switchFragment(TAG_MUSIC) { MusicPageFragment.newInstance() }
                setMiniPlayerVisible(true)
                (requireActivity() as? MainActivity)?.setDrawerLocked(false)
            }
            R.id.nav_video -> {
                switchFragment(TAG_VIDEO) { VideosFragment() }
                setMiniPlayerVisible(false)
                (requireActivity() as? MainActivity)?.setDrawerLocked(true)
            }
            R.id.nav_search -> {
                switchFragment(TAG_SEARCH) { SearchFragment() }
                setMiniPlayerVisible(true)
                (requireActivity() as? MainActivity)?.setDrawerLocked(true)
            }
            R.id.nav_orbit -> {
                switchFragment(TAG_ORBIT) { OrbitFragment() }
                setMiniPlayerVisible(true)
                (requireActivity() as? MainActivity)?.setDrawerLocked(true)
            }
        }
    }

    // Navigation Methods
    fun navigateToFavorites() {
        childFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
            replace(R.id.home_content_container, FavoritesFragment(), "favorites")
            addToBackStack("favorites")
        }
        updateMiniPlayerPosition()
    }

    fun navigateToPlaylists() {
        childFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
            replace(R.id.home_content_container, PlaylistsFragment(), "playlists")
            addToBackStack("playlists")
        }
        updateMiniPlayerPosition()
    }

    fun navigateToRecents() {
        childFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
            replace(R.id.home_content_container, RecentFragment(), "recents")
            addToBackStack("recents")
        }
        updateMiniPlayerPosition()
    }

    private fun switchFragment(tag: String, createFragment: () -> Fragment) {
        val transaction = childFragmentManager.beginTransaction()

        // Hide the current active fragment if it exists
        activeFragment?.let { transaction.hide(it) }

        var targetFragment = childFragmentManager.findFragmentByTag(tag)
        if (targetFragment == null) {
            targetFragment = createFragment()
            transaction.add(R.id.home_content_container, targetFragment, tag)
        } else {
            transaction.show(targetFragment)
        }

        transaction.setPrimaryNavigationFragment(targetFragment)
        transaction.setReorderingAllowed(true)
        transaction.commit()

        activeFragment = targetFragment
    }

    fun updateMiniPlayerPosition() {
        if (bottomNavigationView.visibility == View.VISIBLE) {
            val height = bottomNavigationView.measuredHeight
            val offsetPx = (4 * resources.displayMetrics.density).toInt()
            val margin = if (height > 0) height + offsetPx else offsetPx
            (requireActivity() as? MainActivity)?.setMiniPlayerBottomMargin(margin)
        } else {
            (requireActivity() as? MainActivity)?.setMiniPlayerBottomMargin(0)
        }
    }

    fun handleBackPress(): Boolean {
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack()
            return true
        }

        val videoFragment = childFragmentManager.findFragmentByTag(TAG_VIDEO) as? VideosFragment
        if (videoFragment != null && videoFragment.isVisible && videoFragment.handleBackPress()) {
            return true
        }

        return false
    }

    override fun onResume() {
        super.onResume()
        bottomNavigationView.post { updateMiniPlayerPosition() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (requireActivity() as? MainActivity)?.setMiniPlayerBottomMargin(0)
    }

    private fun setMiniPlayerVisible(visible: Boolean) {
        (requireActivity() as? MainActivity)?.setMiniPlayerVisibility(visible)
    }

    fun refreshData() {
        val activeFragment = childFragmentManager.findFragmentById(R.id.home_content_container)
        when (activeFragment) {
            is MusicPageFragment -> activeFragment.refreshData()
            is VideosFragment -> activeFragment.refreshData()
            is FavoritesFragment -> activeFragment.refreshData()
            is PlaylistsFragment -> activeFragment.refreshData()
            is RecentFragment -> activeFragment.refreshData()
        }
    }

    fun switchToTab(tabId: Int) {
        bottomNavigationView.selectedItemId = tabId
    }

    companion object {
        fun newInstance(): HomePageFragment = HomePageFragment()
    }
}