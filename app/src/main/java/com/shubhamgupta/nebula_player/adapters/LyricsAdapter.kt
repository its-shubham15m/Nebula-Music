package com.shubhamgupta.nebula_player.adapters

import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_player.R

data class LyricLine(
    val startTime: Long,
    val text: String,
    val endTime: Long
)

class LyricsAdapter(
    private val onLyricClick: (LyricLine) -> Unit
) : RecyclerView.Adapter<LyricsAdapter.LyricViewHolder>() {

    private var lyrics: List<LyricLine> = emptyList()
    var activeIndex: Int = -1
        private set

    // Current player time to calculate fill progress
    private var currentPlaybackTime: Long = 0

    fun submitList(parsedLyrics: List<LyricLine>) {
        this.lyrics = parsedLyrics
        this.activeIndex = -1
        notifyDataSetChanged()
    }

    // Called frequently by NowPlayingFragment to animate the fill
    fun updateCurrentTime(time: Long) {
        currentPlaybackTime = time
        if (activeIndex != -1 && activeIndex < lyrics.size) {
            // Only re-bind the active item payload to update the color fill
            notifyItemChanged(activeIndex, "PAYLOAD_TIME_UPDATE")
        }
    }

    fun updateActiveLine(index: Int) {
        if (index != activeIndex && index in lyrics.indices) {
            val prevIndex = activeIndex
            activeIndex = index

            // Refresh previous line to scale down
            if (prevIndex != -1) notifyItemChanged(prevIndex)
            // Refresh new line to scale up
            notifyItemChanged(activeIndex)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lyric, parent, false)
        val tv = view.findViewById<TextView>(R.id.tv_lyric_line)

        // Center the text horizontally for that Apple Music look
        tv.gravity = Gravity.CENTER
        // Ensure the TextView doesn't clip the glow/shadow
        tv.clipToOutline = false

        return LyricViewHolder(view)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        holder.bind(lyrics[position], position == activeIndex)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.contains("PAYLOAD_TIME_UPDATE")) {
            holder.updateKaraokeFill(lyrics[position], currentPlaybackTime)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = lyrics.size

    inner class LyricViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLine: TextView = itemView.findViewById(R.id.tv_lyric_line)

        fun bind(line: LyricLine, isActive: Boolean) {
            tvLine.text = line.text

            // Set a constant base text size. We will SCALE it visually.
            // Changing textSize here causes layout jumps ("floating" bug).
            tvLine.textSize = 24f
            tvLine.setTypeface(null, Typeface.BOLD)

            // Pivot center so it zooms from the middle
            tvLine.pivotX = itemView.width / 2f
            tvLine.pivotY = tvLine.height / 2f

            val params = itemView.layoutParams as RecyclerView.LayoutParams

            if (isActive) {
                // ACTIVE STATE
                // Scale up
                tvLine.scaleX = 1.2f
                tvLine.scaleY = 1.2f
                tvLine.alpha = 1.0f

                // Remove blur
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    tvLine.setRenderEffect(null)
                }

                // Add Soft Glow Shadow
                tvLine.setShadowLayer(20f, 0f, 0f, Color.parseColor("#80FFFFFF"))

                // Add margin to prevent overlap when scaled up
                params.topMargin = 32
                params.bottomMargin = 32

                // Apply the karaoke fill immediately
                updateKaraokeFill(line, currentPlaybackTime)

            } else {
                // INACTIVE STATE
                // Scale down
                tvLine.scaleX = 1.0f
                tvLine.scaleY = 1.0f

                // Dim significantly
                tvLine.alpha = 0.5f

                // Remove shadow
                tvLine.setShadowLayer(0f, 0f, 0f, 0)

                // Reset margins
                params.topMargin = 0
                params.bottomMargin = 0

                // Plain white text (dimmed by alpha)
                tvLine.setTextColor(Color.WHITE)

                // Apple Music-style Blur for background lines
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val blurEffect = android.graphics.RenderEffect.createBlurEffect(
                        4f, 4f, android.graphics.Shader.TileMode.CLAMP
                    )
                    tvLine.setRenderEffect(blurEffect)
                }
            }
            itemView.layoutParams = params

            itemView.setOnClickListener {
                onLyricClick(line)
            }
        }

        fun updateKaraokeFill(line: LyricLine, currentTime: Long) {
            val duration = line.endTime - line.startTime
            if (duration <= 0) return

            // Calculate progress 0.0 -> 1.0
            val progress = (currentTime - line.startTime).toFloat() / duration.toFloat()
            val clampedProgress = progress.coerceIn(0f, 1f)

            val fullText = line.text
            val charCount = fullText.length

            // Calculate how many characters should be "lit up"
            // This creates the word-by-word effect that flows across lines
            val highlightEndIndex = (charCount * clampedProgress).toInt()

            val spannable = SpannableString(fullText)

            // 1. Filled Part (Bright White)
            if (highlightEndIndex > 0) {
                spannable.setSpan(
                    ForegroundColorSpan(Color.WHITE),
                    0,
                    highlightEndIndex,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            // 2. Unfilled Part (Dimmer/Transparent White)
            // This matches the "unsung" part of the active line
            if (highlightEndIndex < charCount) {
                spannable.setSpan(
                    ForegroundColorSpan(Color.parseColor("#4DFFFFFF")), // ~30% opacity
                    highlightEndIndex,
                    charCount,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            tvLine.text = spannable
        }
    }

    companion object {
        fun parseLrc(lrcString: String): List<LyricLine> {
            val tempLines = mutableListOf<LyricLine>()
            val regex = Regex("\\[(\\d+):(\\d+(\\.\\d+)?)\\](.*)")

            lrcString.lines().forEach { line ->
                if (line.isNotBlank()) {
                    val match = regex.find(line)
                    if (match != null) {
                        val (minStr, secStr, _, text) = match.destructured
                        try {
                            val min = minStr.toLong()
                            val sec = secStr.toDouble()
                            val timeMillis = (min * 60 * 1000) + (sec * 1000).toLong()
                            tempLines.add(LyricLine(timeMillis, text.trim(), 0L))
                        } catch (e: Exception) { }
                    }
                }
            }

            val sortedLines = tempLines.sortedBy { it.startTime }
            val finalLines = mutableListOf<LyricLine>()

            for (i in sortedLines.indices) {
                val current = sortedLines[i]
                val nextStartTime = if (i < sortedLines.size - 1) {
                    sortedLines[i + 1].startTime
                } else {
                    current.startTime + 4000 // Default 4s for last line
                }
                finalLines.add(LyricLine(current.startTime, current.text, nextStartTime))
            }
            return finalLines
        }
    }
}