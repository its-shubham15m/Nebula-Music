package com.shubhamgupta.nebula_player.fragments

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButtonToggleGroup
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.utils.UserActivityHistory
import com.shubhamgupta.nebula_player.utils.UserActivityManager
import com.shubhamgupta.nebula_player.utils.UserProfileManager
import com.shubhamgupta.nebula_player.views.TrendChartView // Import the new View

class UserActivityFragment : Fragment() {

    private lateinit var trendChart: TrendChartView
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var selectedValueText: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_user_activity, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        trendChart = view.findViewById(R.id.trend_chart_view)
        toggleGroup = view.findViewById(R.id.toggle_time_range)
        selectedValueText = view.findViewById(R.id.tv_chart_selected_value)

        setupToolbar(view)
        setupHeader(view)
        setupStats(view)

        // Setup Chart Interaction
        trendChart.setOnPointSelectedListener { label, value ->
            // Make the UI interactive: Show what user is touching
            selectedValueText.text = "$label: $value"
            selectedValueText.animate().alpha(1f).setDuration(200).start()

            // Auto hide after 2 seconds
            selectedValueText.postDelayed({
                if (isAdded) selectedValueText.animate().alpha(0f).setDuration(500).start()
            }, 2000)
        }

        // Initial Load (Weekly)
        loadChartData(isWeekly = true)

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btn_range_week -> loadChartData(isWeekly = true)
                    R.id.btn_range_month -> loadChartData(isWeekly = false)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Fix: Lock the sidebar drawer when this fragment is active
        (activity as? MainActivity)?.setDrawerLocked(true)
    }

    private fun loadChartData(isWeekly: Boolean) {
        val rawData = if (isWeekly) {
            UserActivityHistory.getLast7DaysStats(requireContext())
        } else {
            UserActivityHistory.getLast4WeeksStats(requireContext())
        }

        // Convert History Data to Chart Data Pairs
        val chartPoints = rawData.map {
            Pair(it.label, it.value)
        }

        trendChart.setData(chartPoints)
    }

    private fun setupToolbar(view: View) {
        view.findViewById<ImageButton>(R.id.btn_back_activity).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupHeader(view: View) {
        view.findViewById<TextView>(R.id.tv_activity_name).text =
            UserProfileManager.getUserName(requireContext())

        val avatarUri = UserProfileManager.getUserAvatarUri(requireContext())
        if (avatarUri != null) {
            Glide.with(this).load(Uri.parse(avatarUri)).circleCrop()
                .into(view.findViewById(R.id.img_activity_avatar))
        }

        val streak = UserActivityManager.getStreak(requireContext())
        view.findViewById<TextView>(R.id.tv_activity_streak_badge).text = "$streak Day Streak"
    }

    private fun setupStats(view: View) {
        // Total Time
        val totalMs = UserActivityManager.getCurrentTotalUsage(requireContext())
        view.findViewById<TextView>(R.id.tv_activity_total_time).text =
            UserActivityHistory.formatDuration(totalMs)

        // Today's Time
        val savedToday = UserActivityHistory.getTodayUsage(requireContext())
        val currentSession = UserActivityManager.getCurrentTotalUsage(requireContext()) -
                requireContext().getSharedPreferences("nebula_activity_prefs", 0).getLong("total_usage_ms", 0)

        val displayToday = if (currentSession > 0) savedToday + currentSession else savedToday
        view.findViewById<TextView>(R.id.tv_activity_today).text = UserActivityHistory.formatDuration(displayToday)

        // Average
        val streak = UserActivityManager.getStreak(requireContext())
        val avg = if (streak > 0) totalMs / streak else totalMs
        view.findViewById<TextView>(R.id.tv_activity_avg).text = UserActivityHistory.formatDuration(avg)
    }
}