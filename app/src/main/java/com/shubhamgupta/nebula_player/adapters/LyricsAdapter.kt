package com.shubhamgupta.nebula_player.adapters

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_player.R

data class LyricLine(
    val startTime: Long,
    val text: String
)

class LyricsAdapter(
    private val onLyricClick: (LyricLine) -> Unit
) : RecyclerView.Adapter<LyricsAdapter.LyricViewHolder>() {

    private var lyrics: List<LyricLine> = emptyList()
    var activeIndex: Int = -1
        private set

    fun submitList(parsedLyrics: List<LyricLine>) {
        this.lyrics = parsedLyrics
        this.activeIndex = -1
        notifyDataSetChanged()
    }

    fun updateActiveLine(index: Int) {
        if (index != activeIndex && index in lyrics.indices) {
            val prevIndex = activeIndex
            activeIndex = index

            // Notify specific items to animate/update their state
            if (prevIndex != -1) notifyItemChanged(prevIndex)
            notifyItemChanged(activeIndex)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lyric, parent, false)
        return LyricViewHolder(view)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        val line = lyrics[position]
        holder.bind(line, position == activeIndex)
    }

    override fun getItemCount(): Int = lyrics.size

    inner class LyricViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLine: TextView = itemView.findViewById(R.id.tv_lyric_line)

        fun bind(line: LyricLine, isActive: Boolean) {
            tvLine.text = line.text

            if (isActive) {
                // Active State: Bright White, Bold, Larger, Glow Effect
                tvLine.setTextColor(Color.WHITE)
                tvLine.textSize = 28f // Industry standard: Active line is significantly larger
                tvLine.setTypeface(null, Typeface.BOLD)
                tvLine.alpha = 1.0f

                // Add Glow Effect (Shadow Layer)
                // Radius: 25 creates a soft, Apple Music-style glow.
                tvLine.setShadowLayer(25f, 0f, 0f, Color.WHITE)
            } else {
                // Inactive State: Dimmed, Normal Size, No Glow
                tvLine.setTextColor(Color.parseColor("#B0FFFFFF"))
                tvLine.textSize = 18f
                tvLine.setTypeface(null, Typeface.NORMAL)
                tvLine.alpha = 0.6f

                // Remove Glow Effect
                tvLine.setShadowLayer(0f, 0f, 0f, 0)
            }

            // Handle click to seek
            itemView.setOnClickListener {
                onLyricClick(line)
            }
        }
    }

    companion object {
        fun parseLrc(lrcString: String): List<LyricLine> {
            val lines = mutableListOf<LyricLine>()
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
                            lines.add(LyricLine(timeMillis, text.trim()))
                        } catch (e: Exception) { }
                    }
                }
            }
            return lines.sortedBy { it.startTime }
        }
    }
}