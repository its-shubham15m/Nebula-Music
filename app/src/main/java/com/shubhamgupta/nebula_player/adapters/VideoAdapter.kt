package com.shubhamgupta.nebula_player.adapters

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.models.Video
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.Locale

// Sealed class to handle both Videos and Folders in one list
sealed class VideoUiModel {
    data class VideoItem(val video: Video) : VideoUiModel()
    // FIX: Renamed 'firstVideo' to 'representative' to match VideosFragment sorting logic
    data class FolderItem(val name: String, val count: Int, val representative: Video?) : VideoUiModel()
}

class VideoAdapter(
    private val context: Context,
    private val onItemClick: (VideoUiModel) -> Unit,
    private val onDeleteRequest: (Video) -> Unit
) : ListAdapter<VideoUiModel, VideoAdapter.VideoViewHolder>(VideoDiffCallback()) {

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardContainer: View = itemView.findViewById(R.id.video_card_container)
        val thumbnail: ImageView = itemView.findViewById(R.id.video_thumbnail)
        val title: TextView = itemView.findViewById(R.id.video_title)
        val fileInfo: TextView = itemView.findViewById(R.id.video_file_info)
        val duration: TextView = itemView.findViewById(R.id.video_duration)
        val resolution: TextView = itemView.findViewById(R.id.video_resolution)
        val options: ImageButton = itemView.findViewById(R.id.btn_options)
        val playIcon: ImageView? = itemView.findViewById(R.id.icon_play_overlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is VideoUiModel.VideoItem -> bindVideo(holder, item.video)
            is VideoUiModel.FolderItem -> bindFolder(holder, item)
        }
    }

    private fun bindVideo(holder: VideoViewHolder, video: Video) {
        holder.title.text = video.title
        holder.duration.text = formatDuration(video.duration)
        holder.resolution.text = formatResolution(video.resolution)

        val file = File(video.path)
        val sizeString = formatFileSize(file.length())
        val folderName = file.parentFile?.name ?: "Unknown"
        holder.fileInfo.text = "$sizeString • $folderName"

        holder.duration.isVisible = true
        holder.resolution.isVisible = true
        holder.options.isVisible = true
        holder.fileInfo.isVisible = true
        holder.playIcon?.isVisible = true

        Glide.with(context)
            .load(video.uri)
            .apply(RequestOptions().transform(CenterCrop(), RoundedCorners(16)))
            .placeholder(android.R.color.darker_gray)
            .into(holder.thumbnail)

        val playListener = View.OnClickListener { onItemClick(VideoUiModel.VideoItem(video)) }
        holder.cardContainer.setOnClickListener(playListener)
        holder.title.setOnClickListener(playListener)
        holder.fileInfo.setOnClickListener(playListener)
        holder.itemView.setOnClickListener(null)

        holder.options.setOnClickListener { showVideoOptions(it, video) }
    }

    private fun bindFolder(holder: VideoViewHolder, folder: VideoUiModel.FolderItem) {
        holder.title.text = folder.name
        holder.duration.text = "${folder.count} videos"
        holder.fileInfo.text = "Folder"

        holder.resolution.isVisible = false
        holder.options.isVisible = false
        holder.playIcon?.isVisible = false
        holder.duration.isVisible = true
        holder.fileInfo.isVisible = true

        // FIX: Using 'representative' instead of 'firstVideo'
        if (folder.representative != null) {
            Glide.with(context)
                .load(folder.representative.uri)
                .apply(RequestOptions().transform(CenterCrop(), RoundedCorners(16)))
                .placeholder(R.drawable.ic_playlist)
                .into(holder.thumbnail)
        } else {
            holder.thumbnail.setImageResource(R.drawable.ic_playlist)
        }

        val openFolderListener = View.OnClickListener { onItemClick(folder) }
        holder.cardContainer.setOnClickListener(openFolderListener)
        holder.title.setOnClickListener(openFolderListener)
        holder.fileInfo.setOnClickListener(openFolderListener)
        holder.itemView.setOnClickListener(null)
    }

    private fun showVideoOptions(view: View, video: Video) {
        val popup = PopupMenu(context, view)
        popup.menu.add(0, 1, 0, "Play")
        popup.menu.add(0, 2, 0, "Add to Playlist")
        popup.menu.add(0, 3, 0, "Share")
        popup.menu.add(0, 4, 0, "Delete")
        popup.menu.add(0, 5, 0, "Properties")

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> onItemClick(VideoUiModel.VideoItem(video))
                2 -> addToPlaylist(video)
                3 -> shareVideo(video)
                4 -> confirmDelete(video)
                5 -> showProperties(video)
            }
            true
        }
        popup.show()
    }

    private fun addToPlaylist(video: Video) {
        val options = arrayOf("+ Create New Playlist", "Favorites")
        AlertDialog.Builder(context)
            .setTitle("Add to Playlist")
            .setItems(options) { _, which ->
                if (which == 0) {
                    val input = EditText(context)
                    AlertDialog.Builder(context)
                        .setTitle("New Playlist")
                        .setView(input)
                        .setPositiveButton("Create") { _, _ ->
                            Toast.makeText(context, "Playlist '${input.text}' created", Toast.LENGTH_SHORT).show()
                        }
                        .show()
                } else {
                    Toast.makeText(context, "Added to Favorites", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun shareVideo(video: Video) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, video.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Video"))
        } catch (e: Exception) {
            Toast.makeText(context, "Error sharing video", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(video: Video) {
        AlertDialog.Builder(context)
            .setTitle("Delete Video?")
            .setMessage("Delete '${video.title}' permanently?")
            .setPositiveButton("Delete") { _, _ -> onDeleteRequest(video) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProperties(video: Video) {
        val sizeMB = File(video.path).length() / (1024 * 1024).toFloat()
        val info = "File: ${video.title}\nSize: ${String.format("%.2f", sizeMB)} MB\nPath: ${video.path}"
        Toast.makeText(context, info, Toast.LENGTH_LONG).show()
    }

    @SuppressLint("DefaultLocale")
    private fun formatDuration(durationMillis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMillis) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun formatFileSize(sizeBytes: Long): String {
        val sizeMB = sizeBytes / (1024.0 * 1024.0)
        return if (sizeMB >= 1024) {
            String.format(Locale.US, "%.1f GB", sizeMB / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", sizeMB)
        }
    }

    private fun formatResolution(resolutionString: String?): String {
        if (resolutionString.isNullOrEmpty()) return ""
        try {
            val cleaned = resolutionString.replace(" ", "").lowercase(Locale.US)
            val parts = cleaned.split("x", "*")
            if (parts.size >= 2) {
                val dim1 = parts[0].toIntOrNull() ?: 0
                val dim2 = parts[1].toIntOrNull() ?: 0
                val height = if (dim1 < dim2) dim1 else dim2
                return when {
                    height >= 2160 -> "4K"
                    height >= 1440 -> "1440p"
                    height >= 1080 -> "1080p"
                    height >= 720 -> "720p"
                    else -> "${height}p"
                }
            }
        } catch (e: Exception) { return resolutionString }
        return resolutionString
    }

    class VideoDiffCallback : DiffUtil.ItemCallback<VideoUiModel>() {
        override fun areItemsTheSame(oldItem: VideoUiModel, newItem: VideoUiModel): Boolean {
            return when {
                oldItem is VideoUiModel.VideoItem && newItem is VideoUiModel.VideoItem -> oldItem.video.id == newItem.video.id
                oldItem is VideoUiModel.FolderItem && newItem is VideoUiModel.FolderItem -> oldItem.name == newItem.name
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: VideoUiModel, newItem: VideoUiModel): Boolean {
            return oldItem == newItem
        }
    }
}