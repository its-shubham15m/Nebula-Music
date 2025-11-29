package com.shubhamgupta.nebula_player.fragments

import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.utils.SongUtils

class NowPlayingQueueManager(private val fragment: NowPlayingFragment) {

    private var queueDialog: BottomSheetDialog? = null
    private var queueAdapter: QueueAdapter? = null
    private var currentQueuePosition = 0

    fun showQueueDialog() {
        val queueSongs = fragment.getMusicService()?.getQueueSongs() ?: emptyList()
        currentQueuePosition = fragment.getCurrentQueuePosition()

        if (queueSongs.isEmpty()) {
            Toast.makeText(fragment.requireContext(), "Queue is empty", Toast.LENGTH_SHORT).show()
            return
        }

        // Inflate the existing layout
        val dialogView = LayoutInflater.from(fragment.requireContext()).inflate(R.layout.dialog_queue, null)
        val btnCloseBottom = dialogView.findViewById<MaterialButton>(R.id.btn_close_queue_bottom)

        // Find and replace the scrollview container with a RecyclerView for professional performance
        // We look for the ScrollView by ID used in original file
        val scrollView = dialogView.findViewById<View>(R.id.queue_scroll_view)

        // Setup RecyclerView
        val recyclerView = RecyclerView(fragment.requireContext())
        recyclerView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        recyclerView.layoutManager = LinearLayoutManager(fragment.requireContext())

        // Replace logic: Remove ScrollView, Add RecyclerView
        if (scrollView != null && scrollView.parent is ViewGroup) {
            val parent = scrollView.parent as ViewGroup
            val index = parent.indexOfChild(scrollView)
            val layoutParams = scrollView.layoutParams
            recyclerView.layoutParams = layoutParams

            parent.removeView(scrollView)
            parent.addView(recyclerView, index)
        }

        // Setup Adapter
        queueAdapter = QueueAdapter(queueSongs, currentQueuePosition) { position ->
            if (position != currentQueuePosition) {
                fragment.getMusicService()?.playFromQueue(position)
                queueDialog?.dismiss()
            }
        }
        recyclerView.adapter = queueAdapter

        // Scroll to current song
        recyclerView.scrollToPosition(currentQueuePosition)

        queueDialog = BottomSheetDialog(fragment.requireContext())
        queueDialog?.setContentView(dialogView)

        queueDialog?.setOnShowListener { dialogInterface ->
            val bottomSheet = (dialogInterface as BottomSheetDialog).findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                val displayMetrics = fragment.requireContext().resources.displayMetrics
                val screenHeight = displayMetrics.heightPixels
                val targetHeight = (screenHeight * 0.65).toInt()

                val layoutParams = it.layoutParams
                layoutParams?.height = targetHeight
                it.layoutParams = layoutParams

                behavior.isHideable = false
                behavior.peekHeight = targetHeight
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
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

            // If list changed or position changed, update adapter
            queueAdapter?.updateData(newSongs, newPosition)
            currentQueuePosition = newPosition
        }
    }

    fun dismissQueueDialog() {
        queueDialog?.dismiss()
        queueDialog = null
    }

    fun stopScrollMonitoring() {
        // No longer needed with RecyclerView
    }

    fun clearCache() {
        // No longer needed with RecyclerView
    }

    // --- Inner RecyclerView Adapter for Professional Performance ---
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
                    tvTitle.setTextColor(Color.parseColor("#FF018786")) // Teal/Accent
                    tvArtist.setTextColor(Color.parseColor("#FF018786"))
                    tvPosition.setTextColor(Color.parseColor("#FF018786"))
                    itemView.setBackgroundColor(Color.parseColor("#1A018786"))
                } else {
                    tvTitle.setTextColor(Color.WHITE)
                    tvArtist.setTextColor(Color.parseColor("#B3FFFFFF"))
                    tvPosition.setTextColor(ContextCompat.getColor(itemView.context, R.color.white))
                    itemView.setBackgroundResource(android.R.color.transparent)
                }

                // Efficient image loading
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