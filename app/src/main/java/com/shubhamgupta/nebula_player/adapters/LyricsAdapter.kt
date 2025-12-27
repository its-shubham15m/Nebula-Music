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
    val endTime: Long,
    val isPlaceholder: Boolean = false
)

class LyricsAdapter(
    private val onLyricClick: (LyricLine) -> Unit
) : RecyclerView.Adapter<LyricsAdapter.LyricViewHolder>() {

    private var lyrics: List<LyricLine> = emptyList()
    var activeIndex: Int = -1
        private set

    // Current player time to calculate fill progress
    private var currentPlaybackTime: Long = 0

    // Scale factors
    private val activeScale = 1.3f
    private val inactiveScale = 1.0f

    fun submitList(parsedLyrics: List<LyricLine>) {
        this.lyrics = parsedLyrics
        this.activeIndex = -1
        notifyDataSetChanged()
    }

    fun updateCurrentTime(time: Long) {
        currentPlaybackTime = time
        if (activeIndex != -1 && activeIndex < lyrics.size) {
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

            // --- ALIGNMENT FIX ---
            // Consistently use Start alignment for EVERYTHING (dots included)
            tvLine.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            tvLine.textAlignment = View.TEXT_ALIGNMENT_TEXT_START

            // Set pivot to start so scaling grows from the left side
            // (Standard for left-aligned lyrics)
            tvLine.pivotX = 0f
            tvLine.pivotY = tvLine.height / 2f

            if (isActive) {
                // ACTIVE STATE
                tvLine.animate()
                    .scaleX(activeScale)
                    .scaleY(activeScale)
                    .alpha(1.0f)
                    .setDuration(300)
                    .start()

                tvLine.setTypeface(null, Typeface.BOLD)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    tvLine.setRenderEffect(null)
                }
                tvLine.setShadowLayer(20f, 0f, 0f, Color.parseColor("#80FFFFFF"))

                updateKaraokeFill(line, currentPlaybackTime)

            } else {
                // INACTIVE STATE
                tvLine.animate()
                    .scaleX(inactiveScale)
                    .scaleY(inactiveScale)
                    .alpha(0.5f)
                    .setDuration(300)
                    .start()

                tvLine.setTypeface(null, Typeface.NORMAL)
                tvLine.setTextColor(Color.WHITE)
                tvLine.setShadowLayer(0f, 0f, 0f, 0)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val blurEffect = android.graphics.RenderEffect.createBlurEffect(
                        4f, 4f, android.graphics.Shader.TileMode.CLAMP
                    )
                    tvLine.setRenderEffect(blurEffect)
                }
            }

            itemView.setOnClickListener {
                onLyricClick(line)
            }
        }

        fun updateKaraokeFill(line: LyricLine, currentTime: Long) {
            val duration = line.endTime - line.startTime
            if (duration <= 0) {
                tvLine.setTextColor(Color.WHITE)
                return
            }

            // --- TIMING LOGIC (70% fill for dots) ---
            val effectiveDuration = if (line.isPlaceholder) {
                (duration * 0.70f)
            } else {
                duration.toFloat()
            }

            val progress = (currentTime - line.startTime).toFloat() / effectiveDuration
            val clampedProgress = progress.coerceIn(0f, 1f)

            val fullText = line.text
            val charCount = fullText.length

            val highlightEndIndex = (charCount * clampedProgress).toInt()

            val spannable = SpannableString(fullText)

            if (highlightEndIndex > 0) {
                spannable.setSpan(
                    ForegroundColorSpan(Color.WHITE),
                    0,
                    highlightEndIndex,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            if (highlightEndIndex < charCount) {
                spannable.setSpan(
                    ForegroundColorSpan(Color.parseColor("#4DFFFFFF")),
                    highlightEndIndex,
                    charCount,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            tvLine.text = spannable
        }
    }

    companion object {
        private const val GAP_THRESHOLD_MS = 8000L
        private const val DOTS_DURATION_MS = 4000L

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
                            if (text.isNotBlank()) {
                                tempLines.add(LyricLine(timeMillis, text.trim(), 0L))
                            }
                        } catch (e: Exception) { }
                    }
                }
            }

            val sortedLines = tempLines.sortedBy { it.startTime }
            val finalLines = mutableListOf<LyricLine>()

            for (i in sortedLines.indices) {
                val current = sortedLines[i]

                val nextEventTime = if (i < sortedLines.size - 1) {
                    sortedLines[i + 1].startTime
                } else {
                    current.startTime + 5000
                }

                val gap = nextEventTime - current.startTime

                if (gap > GAP_THRESHOLD_MS) {
                    val textEndTime = nextEventTime - DOTS_DURATION_MS
                    finalLines.add(LyricLine(current.startTime, current.text, textEndTime, false))

                    // Add dots placeholder
                    finalLines.add(LyricLine(textEndTime, "• • •", nextEventTime, true))
                } else {
                    finalLines.add(LyricLine(current.startTime, current.text, nextEventTime, false))
                }
            }
            return finalLines
        }
    }
}