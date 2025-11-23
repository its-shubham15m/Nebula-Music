package com.shubhamgupta.nebula_player.adapters

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_player.R

data class LyricLine(
    val startTime: Long,
    val text: String,
    val endTime: Long // Added to calculate duration
)

class LyricsAdapter(
    private val onLyricClick: (LyricLine) -> Unit
) : RecyclerView.Adapter<LyricsAdapter.LyricViewHolder>() {

    private var lyrics: List<LyricLine> = emptyList()
    var activeIndex: Int = -1
        private set

    // Store current player time to calculate gradient progress
    private var currentPlaybackTime: Long = 0

    fun submitList(parsedLyrics: List<LyricLine>) {
        this.lyrics = parsedLyrics
        this.activeIndex = -1
        notifyDataSetChanged()
    }

    // Called frequently by NowPlayingFragment to animate the wipe
    fun updateCurrentTime(time: Long) {
        currentPlaybackTime = time
        if (activeIndex != -1 && activeIndex < lyrics.size) {
            // Only re-bind the active item to update the gradient shader
            notifyItemChanged(activeIndex, "PAYLOAD_TIME_UPDATE")
        }
    }

    fun updateActiveLine(index: Int) {
        if (index != activeIndex && index in lyrics.indices) {
            val prevIndex = activeIndex
            activeIndex = index

            // Refresh previous line to remove active state
            if (prevIndex != -1) notifyItemChanged(prevIndex)
            // Refresh new line to apply active state
            notifyItemChanged(activeIndex)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lyric, parent, false)
        return LyricViewHolder(view)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        // Standard bind
        holder.bind(lyrics[position], position == activeIndex)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.contains("PAYLOAD_TIME_UPDATE")) {
            // Efficient update: only update the gradient, don't reset text/listeners
            holder.updateGradientOnly(lyrics[position], currentPlaybackTime)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = lyrics.size

    inner class LyricViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLine: TextView = itemView.findViewById(R.id.tv_lyric_line)

        fun bind(line: LyricLine, isActive: Boolean) {
            tvLine.text = line.text

            if (isActive) {
                // Base Active Style
                tvLine.textSize = 28f
                tvLine.setTypeface(null, Typeface.BOLD)
                tvLine.alpha = 1.0f
                // Base outer glow (ShadowLayer) - Static glow for readability
                tvLine.setShadowLayer(25f, 0f, 0f, Color.WHITE)

                // Apply the Gradient/Fill logic
                updateGradientOnly(line, currentPlaybackTime)

            } else {
                // Inactive Style
                tvLine.setTextColor(Color.parseColor("#B0FFFFFF"))
                tvLine.textSize = 18f
                tvLine.setTypeface(null, Typeface.NORMAL)
                tvLine.alpha = 0.6f
                tvLine.setShadowLayer(0f, 0f, 0f, 0)
                // Clear any shaders
                tvLine.paint.shader = null
            }

            itemView.setOnClickListener {
                onLyricClick(line)
            }
        }

        fun updateGradientOnly(line: LyricLine, currentTime: Long) {
            // Calculate duration
            val duration = line.endTime - line.startTime

            // CONDITION: Apply "Apple Music" wipe only if line is longer than 4 seconds (4000ms)
            if (duration > 4000) {
                val width = tvLine.paint.measureText(tvLine.text.toString())
                if (width > 0) {
                    // Calculate percentage (0.0 to 1.0)
                    val progress = (currentTime - line.startTime).toFloat() / duration.toFloat()
                    val clampedProgress = progress.coerceIn(0f, 1f)

                    // Create a gradient that transitions from Bright White to Dim White
                    // The transition point moves based on time.
                    // Colors: [Active Color, Active Color, Inactive Color, Inactive Color]
                    // Positions: [0, progress, progress + slight_blur, 1]

                    val gradient = LinearGradient(
                        0f, 0f, width, 0f,
                        intArrayOf(
                            Color.WHITE,          // Filled part
                            Color.WHITE,
                            Color.parseColor("#80FFFFFF"), // Unfilled part (dimmer)
                            Color.parseColor("#80FFFFFF")
                        ),
                        floatArrayOf(
                            clampedProgress,
                            clampedProgress,
                            clampedProgress + 0.05f, // Small fade edge
                            1f
                        ),
                        Shader.TileMode.CLAMP
                    )

                    tvLine.setTextColor(Color.WHITE) // Fallback
                    tvLine.paint.shader = gradient
                    tvLine.invalidate()
                }
            } else {
                // Short line: Just Solid White (Standard Glow from bind)
                tvLine.paint.shader = null
                tvLine.setTextColor(Color.WHITE)
            }
        }
    }

    companion object {
        fun parseLrc(lrcString: String): List<LyricLine> {
            val tempLines = mutableListOf<LyricLine>()
            val regex = Regex("\\[(\\d+):(\\d+(\\.\\d+)?)\\](.*)")

            // 1. Parse lines
            lrcString.lines().forEach { line ->
                if (line.isNotBlank()) {
                    val match = regex.find(line)
                    if (match != null) {
                        val (minStr, secStr, _, text) = match.destructured
                        try {
                            val min = minStr.toLong()
                            val sec = secStr.toDouble()
                            val timeMillis = (min * 60 * 1000) + (sec * 1000).toLong()
                            // Store with dummy end time initially
                            tempLines.add(LyricLine(timeMillis, text.trim(), 0L))
                        } catch (e: Exception) { }
                    }
                }
            }

            // 2. Sort by start time
            val sortedLines = tempLines.sortedBy { it.startTime }

            // 3. Calculate End Times
            val finalLines = mutableListOf<LyricLine>()
            for (i in sortedLines.indices) {
                val current = sortedLines[i]
                val nextStartTime = if (i < sortedLines.size - 1) {
                    sortedLines[i + 1].startTime
                } else {
                    current.startTime + 5000 // Default 5s for last line
                }

                finalLines.add(
                    LyricLine(current.startTime, current.text, nextStartTime)
                )
            }

            return finalLines
        }
    }
}