package com.perfoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val data = ArrayDeque<Float>()
    private val maxPoints = 30
    private var minValue = 0f
    private var maxValue = 100f

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33FFFFFF
    }

    fun configure(min: Float, max: Float) {
        minValue = min
        maxValue = max
        invalidate()
    }

    fun addValue(v: Float) {
        data.addLast(v)
        while (data.size > maxPoints) data.removeFirst()
        invalidate()
    }

    fun clear() {
        data.clear()
        invalidate()
    }

    fun setColor(c: Int) {
        strokePaint.color = c
        fillPaint.color = (c and 0x00FFFFFF) or 0x33000000
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.size < 2) return

        val w = width.toFloat()
        val h = height.toFloat()
        val padding = strokePaint.strokeWidth
        val usableH = h - padding * 2
        val step = w / (maxPoints - 1)

        val path = Path()
        val fillPath = Path()
        data.forEachIndexed { i, v ->
            val normalized = ((v - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
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
        val lastX = (data.size - 1) * step
        fillPath.lineTo(lastX, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, strokePaint)
    }
}
