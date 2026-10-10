package com.perfoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class MultiSparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    class Series(val color: Int, val min: Float, val max: Float) {
        val values = ArrayDeque<Float>()
    }

    private val maxPoints = 30
    private val series = ArrayList<Series>()
    private val density = context.resources.displayMetrics.density

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 0.5f
        color = 0x22FFFFFF
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 0.8f
        color = 0x55FFFFFF
    }

    fun setSeries(newSeries: List<Series>) {
        series.clear()
        series.addAll(newSeries)
        invalidate()
    }

    fun addValues(values: List<Float>) {
        values.forEachIndexed { i, v ->
            if (i < series.size) {
                val s = series[i]
                s.values.addLast(v)
                while (s.values.size > maxPoints) s.values.removeFirst()
            }
        }
        invalidate()
    }

    fun prefillAll(values: List<Float>) {
        series.forEachIndexed { i, s ->
            s.values.clear()
            val v = values.getOrElse(i) { 0f }
            repeat(maxPoints) { s.values.addLast(v) }
        }
        invalidate()
    }

    fun clear() {
        series.forEach { it.values.clear() }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // 网格：3 条水平线（25% / 50% / 75%）
        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        // 左右 Y 轴
        canvas.drawLine(1f, 0f, 1f, h, axisPaint)
        canvas.drawLine(w - 1f, 0f, w - 1f, h, axisPaint)

        if (series.isEmpty()) return

        val padding = density * 2f
        val usableH = h - padding * 2
        val step = w / (maxPoints - 1)

        for (s in series) {
            if (s.values.size < 2) continue

            strokePaint.color = s.color
            fillPaint.color = (s.color and 0x00FFFFFF) or 0x22000000

            val path = Path()
            val fillPath = Path()
            val range = s.max - s.min
            s.values.forEachIndexed { i, v ->
                val normalized = if (range <= 0f) 0.5f
                    else ((v - s.min) / range).coerceIn(0f, 1f)
                val x = i * step
                val y = padding + (1f - normalized) * usableH
                if (i == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            val lastX = (s.values.size - 1) * step
            fillPath.lineTo(lastX, h)
            fillPath.close()

            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(path, strokePaint)
        }
    }
}
