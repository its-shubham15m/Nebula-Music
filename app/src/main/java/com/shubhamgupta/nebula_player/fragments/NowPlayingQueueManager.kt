package com.shubhamgupta.nebula_player.fragments

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.utils.SongUtils

class NowPlayingQueueManager(private val fragment: NowPlayingFragment) {

    private var queueDialog: Dialog? = null
    private var queueAdapter: QueueAdapter? = null
    private var currentQueuePosition = 0

    fun showQueueDialog() {
        val queueSongs = fragment.getMusicService()?.getQueueSongs() ?: emptyList()
        currentQueuePosition = fragment.getCurrentQueuePosition()

        if (queueSongs.isEmpty()) {
            Toast.makeText(fragment.requireContext(), "Queue is empty", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Inflate the layout
        val dialogView = LayoutInflater.from(fragment.requireContext()).inflate(R.layout.dialog_queue, null)
        val btnCloseBottom = dialogView.findViewById<MaterialButton>(R.id.btn_close_queue_bottom)

        // Setup RecyclerView
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recycler_queue_list)
        recyclerView.layoutManager = LinearLayoutManager(fragment.requireContext())

        // 2. Set Max Height logic for RecyclerView (1/2 Screen Height)
        val displayMetrics = fragment.resources.displayMetrics
        val maxRecyclerViewHeight = (displayMetrics.heightPixels / 2)

        // Apply max height constraint to RecyclerView
        recyclerView.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        recyclerView.requestLayout()

        // Wrap logic to enforce MaxHeight if needed, but since it's inside a CardView with wrap_content
        // and we want it to scroll internally if it exceeds space, usually a ConstraintLayout or specific measure logic is best.
        // However, for simplicity with the provided XML structure, we can restrict the RecyclerView's height programmatically
        // by checking the item count or simply setting a strict constraint if the list is long.
        // A robust way for "max height" on a view without custom views is creating a measure pass,
        // but often just letting the dialog handle wrap_content up to screen limits works.
        // To strictly enforce 1/2 height limit:
        recyclerView.viewTreeObserver.addOnGlobalLayoutListener {
            if (recyclerView.height > maxRecyclerViewHeight) {
                val params = recyclerView.layoutParams
                params.height = maxRecyclerViewHeight
                recyclerView.layoutParams = params
            }
        }

        // Setup Adapter
        queueAdapter = QueueAdapter(queueSongs, currentQueuePosition) { position ->
            if (position != currentQueuePosition) {
                fragment.getMusicService()?.playFromQueue(position)
                queueDialog?.dismiss()
            }
        }
        recyclerView.adapter = queueAdapter

        // Scroll to current position
        recyclerView.post {
            (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(currentQueuePosition, 0)
        }

        // 3. Initialize standard Dialog
        queueDialog = Dialog(fragment.requireContext())
        queueDialog?.requestWindowFeature(Window.FEATURE_NO_TITLE)
        queueDialog?.setContentView(dialogView)

        // FIX: Make status bar transparent and enable edge-to-edge
        queueDialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setDecorFitsSystemWindows(false)
                } else {
                    @Suppress("DEPRECATION")
                    decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                }
                statusBarColor = Color.TRANSPARENT
            }
        }

        // 4. Close button logic
        dialogView.findViewById<View>(R.id.queue_overlay)?.setOnClickListener {
            queueDialog?.dismiss()
        }

        btnCloseBottom?.setOnClickListener {
            queueDialog?.dismiss()
        }

        queueDialog?.show()
    }

    fun refreshQueueDialog() {
        if (queueDialog?.isShowing == true && queueAdapter != null) {
            val newPosition = fragment.getCurrentQueuePosition()
            val newSongs = fragment.getMusicService()?.getQueueSongs() ?: emptyList()

            queueAdapter?.updateData(newSongs, newPosition)
            currentQueuePosition = newPosition
        }
    }

    fun dismissQueueDialog() {
        queueDialog?.dismiss()
        queueDialog = null
    }

    fun stopScrollMonitoring() {}
    fun clearCache() {}

    // --- Inner RecyclerView Adapter ---
    private inner class QueueAdapter(
        private var songs: List<Song>,
        private var currentPos: Int,
        private val onItemClick: (Int) -> Unit
    ) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

        fun updateData(newSongs: List<Song>, newPos: Int) {
            songs = newSongs
            currentPos = newPos
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue_song, parent, false)
            return QueueViewHolder(view)
        }

        override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
            val song = songs[position]
            holder.bind(song, position, position == currentPos)
        }

        override fun getItemCount(): Int = songs.size

        inner class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvTitle: TextView = itemView.findViewById(R.id.tv_song_title)
            private val tvArtist: TextView = itemView.findViewById(R.id.tv_song_artist)
            private val tvPosition: TextView = itemView.findViewById(R.id.tv_song_position)
            private val ivArt: ImageView = itemView.findViewById(R.id.iv_album_art)
            private val btnOptions: ImageView = itemView.findViewById(R.id.btn_queue_options)

            fun bind(song: Song, position: Int, isCurrent: Boolean) {
                tvTitle.text = song.title
                tvArtist.text = song.artist ?: "Unknown Artist"
                tvPosition.text = "${position + 1}"

                if (isCurrent) {
                    // Update Colors
                    tvTitle.setTextColor(Color.parseColor("#3EA6FF"))
                    tvArtist.setTextColor(Color.parseColor("#3EA6FF"))
                    tvPosition.setTextColor(Color.parseColor("#3EA6FF"))

                    // CHANGED: Use the rounded drawable instead of simple color
                    itemView.setBackgroundResource(R.drawable.bg_queue_item_selected)
                } else {
                    tvTitle.setTextColor(Color.WHITE)
                    tvArtist.setTextColor(Color.parseColor("#B3FFFFFF"))
                    tvPosition.setTextColor(ContextCompat.getColor(itemView.context, R.color.white))

                    // Clear background for unselected items
                    itemView.setBackgroundResource(android.R.color.transparent)
                }

                Glide.with(itemView.context)
                    .load(SongUtils.getAlbumArtUri(song.albumId))
                    .placeholder(R.drawable.default_album_art)
                    .error(R.drawable.default_album_art)
                    .into(ivArt)

                itemView.setOnClickListener { onItemClick(position) }

                btnOptions.setOnClickListener {
                    showQueueItemOptions(song, position)
                }
            }
        }
    }

    private fun showQueueItemOptions(song: Song, position: Int) {
        val options = arrayOf("Remove from queue", "Add to playlist", "Share")
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(song.title)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> Toast.makeText(fragment.requireContext(), "Remove feature pending", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(fragment.requireContext(), "Add to playlist pending", Toast.LENGTH_SHORT).show()
                    2 -> shareSong(song)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun shareSong(song: Song) {
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Check out \"${song.title}\" on Nebula Music")
        }
        fragment.startActivity(Intent.createChooser(shareIntent, "Share Song"))
    }
}