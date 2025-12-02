package com.shubhamgupta.nebula_player.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.shubhamgupta.nebula_player.R
import java.util.concurrent.TimeUnit
import kotlin.math.max

class TrendChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Data
    private var dataPoints: List<Pair<String, Long>> = emptyList() // Label, Millis
    private var maxDuration: Long = 1
    private var averageDuration: Long = 0

    // Paints
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 6f
        style = Paint.Style.STROKE
        pathEffect = CornerPathEffect(30f) // Smooth curves
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        strokeWidth = 2f
        alpha = 50
    }

    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }

    private val avgLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800") // Orange for average
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        // FIX: Method name is setShadowLayer
        setShadowLayer(10f, 0f, 0f, Color.GRAY)
    }

    // Geometry
    private val chartPath = Path()
    private val fillPath = Path()
    private val pointsCoordinates = mutableListOf<PointF>()

    // Animation
    private var animationProgress = 0f

    // Touch
    private var touchX: Float? = null
    private var onPointSelected: ((String, String) -> Unit)? = null

    init {
        // Get theme colors
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
        val colorPrimary = typedValue.data

        linePaint.color = colorPrimary
    }

    fun setData(data: List<Pair<String, Long>>) {
        this.dataPoints = data
        if (data.isNotEmpty()) {
            this.maxDuration = max(1L, data.maxOf { it.second })
            this.averageDuration = data.map { it.second }.average().toLong()
            // Add slightly more headroom to Y axis
            this.maxDuration = (this.maxDuration * 1.2).toLong()
        }

        // Animate entry
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 1000
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener {
            animationProgress = it.animatedValue as Float
            invalidate()
        }
        animator.start()
    }

    fun setOnPointSelectedListener(listener: (String, String) -> Unit) {
        this.onPointSelected = listener
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.isEmpty()) return

        val width = width.toFloat()
        val height = height.toFloat()
        val paddingBottom = 60f
        val paddingLeft = 80f // Space for Y axis labels
        val chartWidth = width - paddingLeft - 40f
        val chartHeight = height - paddingBottom - 20f

        // 1. Draw Grid & Y-Axis Labels
        val rows = 4
        for (i in 0..rows) {
            val y = 20f + (chartHeight * i / rows)
            canvas.drawLine(paddingLeft, y, width, y, gridPaint)

            // Y-Axis Text (Time)
            val timeVal = maxDuration * (rows - i) / rows
            val timeStr = formatCompact(timeVal)

            // Align text right of padding
            axisTextPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(timeStr, paddingLeft - 10f, y + 10f, axisTextPaint)
        }

        // 2. Calculate Coordinates
        pointsCoordinates.clear()
        val stepX = chartWidth / (dataPoints.size - 1)

        dataPoints.forEachIndexed { index, pair ->
            val x = paddingLeft + (index * stepX)
            // Invert Y because canvas 0 is top
            val ratio = pair.second.toFloat() / maxDuration
            val y = (20f + chartHeight) - (chartHeight * ratio * animationProgress)
            pointsCoordinates.add(PointF(x, y))
        }

        // 3. Construct Paths
        chartPath.reset()
        fillPath.reset()

        if (pointsCoordinates.isNotEmpty()) {
            chartPath.moveTo(pointsCoordinates[0].x, pointsCoordinates[0].y)
            fillPath.moveTo(pointsCoordinates[0].x, 20f + chartHeight) // Bottom Left
            fillPath.lineTo(pointsCoordinates[0].x, pointsCoordinates[0].y) // Top Left

            for (i in 1 until pointsCoordinates.size) {
                // Cubic Bezier for smooth lines
                val p1 = pointsCoordinates[i - 1]
                val p2 = pointsCoordinates[i]
                val midX = (p1.x + p2.x) / 2
                chartPath.cubicTo(midX, p1.y, midX, p2.y, p2.x, p2.y)
                fillPath.cubicTo(midX, p1.y, midX, p2.y, p2.x, p2.y)
            }

            fillPath.lineTo(pointsCoordinates.last().x, 20f + chartHeight) // Bottom Right
            fillPath.close()
        }

        // 4. Draw Gradient Fill
        val gradient = LinearGradient(
            0f, 0f, 0f, height,
            linePaint.color, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        fillPaint.shader = gradient
        fillPaint.alpha = 100 // Semi transparent
        canvas.drawPath(fillPath, fillPaint)

        // 5. Draw Line
        canvas.drawPath(chartPath, linePaint)

        // 6. Draw Average Line (The "Linear line crossing the cuts")
        val avgRatio = averageDuration.toFloat() / maxDuration
        val avgY = (20f + chartHeight) - (chartHeight * avgRatio * animationProgress)
        canvas.drawLine(paddingLeft, avgY, width, avgY, avgLinePaint)

        // Label for Average
        axisTextPaint.textAlign = Paint.Align.LEFT
        axisTextPaint.color = avgLinePaint.color
        axisTextPaint.textSize = 24f
        canvas.drawText("Avg", paddingLeft + 10f, avgY - 10f, axisTextPaint)

        // 7. Draw X-Axis Labels
        axisTextPaint.color = Color.GRAY
        axisTextPaint.textAlign = Paint.Align.CENTER
        axisTextPaint.textSize = 30f

        dataPoints.forEachIndexed { index, pair ->
            val x = paddingLeft + (index * stepX)
            canvas.drawText(pair.first, x, height - 10f, axisTextPaint)
        }

        // 8. Interactive Touch Highlight
        touchX?.let { tx ->
            // Find closest point
            var closestDist = Float.MAX_VALUE
            var closestPoint: PointF? = null
            var closestData: Pair<String, Long>? = null

            for (i in pointsCoordinates.indices) {
                val dist = Math.abs(pointsCoordinates[i].x - tx)
                if (dist < closestDist) {
                    closestDist = dist
                    closestPoint = pointsCoordinates[i]
                    closestData = dataPoints[i]
                }
            }

            // Draw indicator
            closestPoint?.let { p ->
                // Vertical line
                canvas.drawLine(p.x, 20f, p.x, height - paddingBottom, gridPaint.apply { alpha = 200 })
                // Dot
                canvas.drawCircle(p.x, p.y, 12f, linePaint.apply { style = Paint.Style.FILL })
                canvas.drawCircle(p.x, p.y, 12f, touchPaint.apply { alpha = 100 })
                // Reset paint style
                linePaint.style = Paint.Style.STROKE

                // Trigger callback
                closestData?.let {
                    onPointSelected?.invoke(it.first, formatFull(it.second))
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchX = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun formatCompact(ms: Long): String {
        val hrs = TimeUnit.MILLISECONDS.toHours(ms)
        return if (hrs > 0) "${hrs}h" else "${TimeUnit.MILLISECONDS.toMinutes(ms)}m"
    }

    private fun formatFull(ms: Long): String {
        val hrs = TimeUnit.MILLISECONDS.toHours(ms)
        val mins = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return "${hrs}h ${mins}m"
    }
}