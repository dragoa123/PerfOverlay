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

    // 坐标轴范围（根据第一个参数的实际范围显示）
    private var axisMin = 0f
    private var axisMax = 100f

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
        color = 0x25FFFFFF
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 0.8f
        color = 0x60FFFFFF
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = density * 8f
    }

    // 边距：左侧留给 Y 轴刻度，其他留一点点
    private val leftPad = density * 24f
    private val rightPad = density * 3f
    private val topPad = density * 6f
    private val bottomPad = density * 4f

    fun setSeries(newSeries: List<Series>) {
        series.clear()
        series.addAll(newSeries)
        invalidate()
    }

    fun setAxisRange(min: Float, max: Float) {
        axisMin = min
        axisMax = max
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

    private fun formatAxisValue(v: Float): String {
        return when {
            axisMax >= 1000f -> "${(v / 1000f).toInt()}k"
            axisMax >= 10f -> v.toInt().toString()
            else -> String.format("%.1f", v)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val chartLeft = leftPad
        val chartRight = w - rightPad
        val chartTop = topPad
        val chartBottom = h - bottomPad
        val chartW = chartRight - chartLeft
        val chartH = chartBottom - chartTop

        if (chartW <= 0f || chartH <= 0f) return

        // ---------- 水平网格 + Y 轴刻度 ----------
        val hLines = 4
        for (i in 0..hLines) {
            val y = chartTop + chartH * i / hLines
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
        }

        // 左侧 Y 轴刻度文字（从顶到底：max → min）
        val textYOffset = textPaint.textSize / 2.8f
        for (i in 0..hLines) {
            val y = chartTop + chartH * i / hLines
            val ratio = 1f - i.toFloat() / hLines
            val value = axisMin + (axisMax - axisMin) * ratio
            canvas.drawText(formatAxisValue(value), 2f, y + textYOffset, textPaint)
        }

        // ---------- 竖直网格 ----------
        val vLines = 3
        for (i in 0..vLines) {
            val x = chartLeft + chartW * i / vLines
            canvas.drawLine(x, chartTop, x, chartBottom, gridPaint)
        }

        // ---------- 坐标轴边框 ----------
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint)      // 左
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint)  // 底
        canvas.drawLine(chartLeft, chartTop, chartRight, chartTop, axisPaint)        // 顶
        canvas.drawLine(chartRight, chartTop, chartRight, chartBottom, axisPaint)    // 右

        if (series.isEmpty()) return

        // ---------- 画每条曲线 ----------
        val step = chartW / (maxPoints - 1)

        for (s in series) {
            if (s.values.size < 2) continue

            strokePaint.color = s.color
            fillPaint.color = (s.color and 0x00FFFFFF) or 0x28000000

            val path = Path()
            val fillPath = Path()
            val range = s.max - s.min

            s.values.forEachIndexed { i, v ->
                val normalized = if (range <= 0f) 0.5f
                    else ((v - s.min) / range).coerceIn(0f, 1f)
                val x = chartLeft + i * step
                val y = chartTop + (1f - normalized) * chartH

                if (i == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, chartBottom)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            val lastX = chartLeft + (s.values.size - 1) * step
            fillPath.lineTo(lastX, chartBottom)
            fillPath.close()

            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(path, strokePaint)
        }
    }
}
