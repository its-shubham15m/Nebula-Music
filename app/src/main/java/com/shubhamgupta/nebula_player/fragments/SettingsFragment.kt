package com.shubhamgupta.nebula_player.fragments

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.textfield.TextInputEditText
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.utils.UserProfileManager
import kotlin.math.abs
import androidx.core.graphics.drawable.toDrawable

class SettingsFragment : Fragment() {

    private lateinit var avatarPickerLauncher: ActivityResultLauncher<String>
    private lateinit var userAvatarView: ImageView
    private lateinit var userNameView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        avatarPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Ignore
                }
                saveProfileData(null, uri.toString())
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar(view)
        setupProfileSettings(view)
        setupAudioSettings(view)
        setupAiSettings(view) // Added this call
        setupLibrarySettings(view)
        setupVideoSettings(view)
        setupGeneralSettings(view)
    }

    private fun setupToolbar(view: View) {
        view.findViewById<View>(R.id.btn_back)?.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<TextView>(R.id.tv_page_title)?.text = "Settings"
    }

    private fun setupProfileSettings(view: View) {
        userAvatarView = view.findViewById(R.id.setting_user_avatar)
        userNameView = view.findViewById(R.id.setting_user_name)
        val profileContainer = view.findViewById<LinearLayout>(R.id.setting_user_profile)

        refreshProfileUI()

        profileContainer.setOnClickListener {
            showEditProfileDialog()
        }
    }

    private fun refreshProfileUI() {
        val context = context ?: return
        val name = UserProfileManager.getUserName(context)
        val avatarUri = UserProfileManager.getUserAvatarUri(context)

        userNameView.text = name
        if (avatarUri != null) {
            Glide.with(this)
                .load(Uri.parse(avatarUri))
                .placeholder(R.drawable.default_album_art)
                .error(R.drawable.default_album_art)
                .circleCrop()
                .into(userAvatarView)
        } else {
            userAvatarView.setImageResource(R.drawable.default_album_art)
        }
    }

    private fun showEditProfileDialog() {
        val context = requireContext()
        val builder = AlertDialog.Builder(context)

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_user_setup, null)
        builder.setView(dialogView)

        val dialog = builder.create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val inputName = dialogView.findViewById<TextInputEditText>(R.id.input_user_name)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.rv_avatar_picker)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_save_setup)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)

        // 1. Set Title correctly for Settings
        tvTitle.text = "Edit Profile"

        val currentName = UserProfileManager.getUserName(context)
        inputName.setText(currentName)

        val avatars = UserProfileManager.getAvailableAvatars(context)
        val currentAvatar = UserProfileManager.getUserAvatarUri(context)

        // --- Horizontal Carousel Logic ---
        val layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        recyclerView.layoutManager = layoutManager

        // Adapter
        var selectedAvatarUri = currentAvatar
        // Fallback to first if null
        if (selectedAvatarUri == null && avatars.isNotEmpty()) selectedAvatarUri = avatars[0]

        val adapter = AvatarCarouselAdapter(context, avatars, selectedAvatarUri) { newUri ->
            selectedAvatarUri = newUri
        }
        recyclerView.adapter = adapter

        // Snap Helper (Center Lock)
        val snapHelper = LinearSnapHelper()
        snapHelper.attachToRecyclerView(recyclerView)

        // Add padding to center the first and last items
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val itemWidthPx = (100 * displayMetrics.density).toInt()
        val padding = (screenWidth - itemWidthPx) / 2 - (48 * displayMetrics.density).toInt()

        recyclerView.setPadding(padding.coerceAtLeast(0), 0, padding.coerceAtLeast(0), 0)
        recyclerView.clipToPadding = false

        // Scroll Listener for Visual Effects (Scale + Dim)
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                updateCarouselEffects(layoutManager, rv)
            }
        })

        // Scroll to initial position
        val initialPos = avatars.indexOf(selectedAvatarUri).coerceAtLeast(0)
        recyclerView.scrollToPosition(initialPos)

        // Trigger effect initially
        recyclerView.post {
            if (avatars.isNotEmpty()) {
                recyclerView.scrollBy(1, 0)
                recyclerView.scrollBy(-1, 0)
            }
        }

        // Detect snap change to update selection logic
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val centerView = snapHelper.findSnapView(layoutManager)
                    val pos = centerView?.let { layoutManager.getPosition(it) } ?: -1
                    if (pos != -1) {
                        selectedAvatarUri = avatars[pos]
                        adapter.setSelection(avatars[pos])
                    }
                }
            }
        })

        btnSave.setOnClickListener {
            val newName = inputName.text.toString()
            saveProfileData(newName, selectedAvatarUri)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateCarouselEffects(layoutManager: LinearLayoutManager, recyclerView: RecyclerView) {
        val centerX = recyclerView.width / 2f

        for (i in 0 until layoutManager.childCount) {
            val child = layoutManager.getChildAt(i) ?: continue
            val childCenterX = (child.left + child.right) / 2f

            val dist = abs(centerX - childCenterX)
            val scaleFactor = 1f - (dist / recyclerView.width)

            val targetScale = 1.0f + (0.15f * scaleFactor.coerceIn(0f, 1f))
            val finalScale = if (dist < 200) targetScale else 1.0f

            child.scaleX = finalScale
            child.scaleY = finalScale

            val overlay = child.findViewById<View>(R.id.view_overlay)
            if (overlay != null) {
                val alpha = (dist / 250f).coerceIn(0f, 0.2f)
                overlay.alpha = alpha
            }
        }
    }

    private fun saveProfileData(name: String?, avatarUri: String?) {
        val context = requireContext()
        val finalName = name ?: UserProfileManager.getUserName(context)

        UserProfileManager.saveUserProfile(context, finalName, avatarUri)
        refreshProfileUI()
        (activity as? MainActivity)?.updateSidebarProfile()

        Toast.makeText(context, "Profile Updated", Toast.LENGTH_SHORT).show()
    }

    private fun setupGeneralSettings(view: View) {
        // Theme settings placeholder
    }

    private fun setupAudioSettings(view: View) {
        val context = requireContext()
        // Equalizer
        view.findViewById<LinearLayout>(R.id.setting_equalizer)?.setOnClickListener {
            (activity as? MainActivity)?.showEqualizerPage()
        }
        // Crossfade
        val switchCrossfade = view.findViewById<SwitchCompat>(R.id.switch_crossfade)
        switchCrossfade.isChecked = PreferenceManager.isCrossfadeEnabled(context)
        switchCrossfade.setOnCheckedChangeListener { _, isChecked ->
            PreferenceManager.setCrossfadeEnabled(context, isChecked)
        }
        // Gapless Playback
        val switchGapless = view.findViewById<SwitchCompat>(R.id.switch_gapless)
        switchGapless.isChecked = PreferenceManager.isGaplessPlaybackEnabled(context)
        switchGapless.setOnCheckedChangeListener { _, isChecked ->
            PreferenceManager.setGaplessPlaybackEnabled(context, isChecked)
        }
        // Filter Short Audio
        val switchFilterShort = view.findViewById<SwitchCompat>(R.id.switch_filter_short)
        switchFilterShort.isChecked = PreferenceManager.isFilterShortAudioEnabled(context)
        switchFilterShort.setOnCheckedChangeListener { _, isChecked ->
            PreferenceManager.setFilterShortAudioEnabled(context, isChecked)
            Toast.makeText(context, "Refresh library to apply changes", Toast.LENGTH_SHORT).show()
        }
    }

    // --- NEW: AI Settings Setup ---
    private fun setupAiSettings(view: View) {
        val context = requireContext()
        view.findViewById<LinearLayout>(R.id.setting_gemini_key)?.setOnClickListener {
            showApiKeyDialog(context)
        }
    }

    private fun showApiKeyDialog(context: Context) {
        // 1. Inflate the custom layout
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_gemini_api, null)

        // 2. Create the Dialog
        val builder = AlertDialog.Builder(context)
        builder.setView(dialogView)
        val dialog = builder.create()

        // 3. Make background transparent so the custom XML corners show correctly
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())

        // 4. Initialize Views
        val etApiKey = dialogView.findViewById<EditText>(R.id.et_api_key)
        val btnCancel = dialogView.findViewById<Button>(R.id.btn_cancel_api)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_save_api)

        // 5. Pre-fill existing key if available
        val existingKey = PreferenceManager.getGeminiApiKey(context)
        if (!existingKey.isNullOrEmpty()) {
            etApiKey.setText(existingKey)
        }

        // 6. Handle Clicks
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isNotEmpty()) {
                PreferenceManager.saveGeminiApiKey(context, key)
                Toast.makeText(context, "API Key Saved", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(context, "Key cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun setupLibrarySettings(view: View) {
        view.findViewById<LinearLayout>(R.id.setting_scan_media)?.setOnClickListener {
            Toast.makeText(requireContext(), "Scanning media...", Toast.LENGTH_SHORT).show()
            SongRepository.refreshSongs(requireContext())
        }
    }

    private fun setupVideoSettings(view: View) {
        val context = requireContext()
        val switchHwAccel = view.findViewById<SwitchCompat>(R.id.switch_hw_accel)
        switchHwAccel.isChecked = PreferenceManager.isVideoHwAccelerationEnabled(context)
        switchHwAccel.setOnCheckedChangeListener { _, isChecked ->
            PreferenceManager.setVideoHwAccelerationEnabled(context, isChecked)
        }
        val switchBgPlay = view.findViewById<SwitchCompat>(R.id.switch_video_bg)
        switchBgPlay.isChecked = PreferenceManager.isVideoBgPlaybackEnabled(context)
        switchBgPlay.setOnCheckedChangeListener { _, isChecked ->
            PreferenceManager.setVideoBgPlaybackEnabled(context, isChecked)
        }
    }

    // --- Adapter for Horizontal Carousel ---
    inner class AvatarCarouselAdapter(
        private val context: Context,
        private val avatarList: List<String>,
        private var currentSelection: String?,
        private val onAvatarSelected: (String) -> Unit
    ) : RecyclerView.Adapter<AvatarCarouselAdapter.AvatarViewHolder>() {

        fun setSelection(uri: String) {
            currentSelection = uri
        }

        inner class AvatarViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imageView: ImageView = itemView.findViewById(R.id.iv_avatar_item)
            val overlay: View = itemView.findViewById(R.id.view_overlay)

            fun bind(path: String) {
                // Prepare loadPath. If it comes from Manager, it's already "file:///android_asset/..."
                var loadPath = path

                // Only add prefix if it looks like a raw relative path and NOT a full URI
                if (!path.startsWith("content://") &&
                    !path.startsWith("file://") &&
                    !path.startsWith("android.resource://")) {
                    loadPath = "file:///android_asset/$path"
                }

                Glide.with(context)
                    .load(Uri.parse(loadPath))
                    .placeholder(R.drawable.default_album_art)
                    .error(R.drawable.default_album_art)
                    .into(imageView)

                itemView.setOnClickListener {
                    onAvatarSelected(path) // Keep original path format for consistency
                    (bindingAdapter as? RecyclerView)?.smoothScrollToPosition(adapterPosition)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AvatarViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.item_avatar_selection, parent, false)
            return AvatarViewHolder(view)
        }

        override fun onBindViewHolder(holder: AvatarViewHolder, position: Int) {
            holder.bind(avatarList[position])
        }

        override fun getItemCount(): Int = avatarList.size
    }
}