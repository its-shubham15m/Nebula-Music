package com.shubhamgupta.nebula_player.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.utils.UserActivityHistory
import com.shubhamgupta.nebula_player.utils.UserActivityManager
import com.shubhamgupta.nebula_player.utils.UserProfileManager

class UserActivityFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_user_activity, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar(view)
        setupProfileHeader(view)
        setupStats(view)
        setupChart(view)
    }

    private fun setupToolbar(view: View) {
        view.findViewById<ImageButton>(R.id.btn_back_activity).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupProfileHeader(view: View) {
        val nameView = view.findViewById<TextView>(R.id.tv_activity_name)
        val avatarView = view.findViewById<ImageView>(R.id.img_activity_avatar)

        nameView.text = UserProfileManager.getUserName(requireContext())

        val avatarUri = UserProfileManager.getUserAvatarUri(requireContext())
        if (avatarUri != null) {
            Glide.with(this)
                .load(Uri.parse(avatarUri))
                .placeholder(R.drawable.default_album_art)
                .circleCrop()
                .into(avatarView)
        }
    }

    private fun setupStats(view: View) {
        // Streak
        val streak = UserActivityManager.getStreak(requireContext())
        view.findViewById<TextView>(R.id.tv_activity_streak).text = streak.toString()

        // Total Time
        val totalTime = UserActivityHistory.getTotalUsageFormatted(requireContext())
        view.findViewById<TextView>(R.id.tv_activity_total_time).text = totalTime

        // Today
        val todayTime = UserActivityHistory.getTodayUsageFormatted(requireContext())
        view.findViewById<TextView>(R.id.tv_activity_today).text = todayTime
    }

    private fun setupChart(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.chart_container)
        val stats = UserActivityHistory.getLast7DaysStats(requireContext())

        // Dynamically create bars
        // We use weights to space them evenly

        val primaryColor = getThemeColor(android.R.attr.colorPrimary)
        val secondaryColor = getThemeColor(R.color.colorSurface)

        for (dayStat in stats) {
            val barLayout = LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }

            // The Bar View
            val barView = View(context).apply {
                // Min height 4dp so it's visible even if 0
                val heightPercent = if (dayStat.relativeHeight < 0.05f) 0.05f else dayStat.relativeHeight

                // Calculate height in dp based on container height (approx 140dp available for bars)
                val targetHeight = (140 * heightPercent).toInt()
                val density = resources.displayMetrics.density

                layoutParams = LinearLayout.LayoutParams(
                    (12 * density).toInt(), // Width of bar
                    (targetHeight * density).toInt() // Height
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }

                // Highlight today (last item)
                val isToday = dayStat == stats.last()
                background = ContextCompat.getDrawable(context, R.drawable.bg_streak_badge)?.mutate()
                backgroundTintList = ColorStateList.valueOf(if (isToday) primaryColor else 0xFFCCCCCC.toInt())
            }

            // The Day Label (Mon, Tue...)
            val labelView = TextView(context).apply {
                text = dayStat.dateLabel
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Color.GRAY)
            }

            barLayout.addView(barView)
            barLayout.addView(labelView)

            container.addView(barLayout)
        }
    }

    private fun getThemeColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}