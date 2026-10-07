package com.example.activitymonitor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

class BarChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var values: List<Float> = emptyList(); set(v) { field = v; invalidate() }
    var labels: List<String> = emptyList(); set(v) { field = v; invalidate() }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; typeface = Typeface.DEFAULT }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val max = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val w = width.toFloat(); val h = height.toFloat(); val gap = 8f; val n = values.size
        val barWidth = ((w - gap * (n + 1)) / n).coerceAtLeast(3f)
        values.forEachIndexed { i, value ->
            val left = gap + i * (barWidth + gap)
            val top = h - 44f - (value / max) * (h - 70f)
            barPaint.color = themeTextColor()
            canvas.drawRoundRect(left, top, left + barWidth, h - 44f, 5f, 5f, barPaint)
            if (i < labels.size && labels[i].isNotBlank()) {
                textPaint.color = themeTextColor(); textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(labels[i], left + barWidth / 2f, h - 14f, textPaint)
            }
        }
    }

    private fun themeTextColor(): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)) {
            if (value.resourceId != 0) resources.getColor(value.resourceId, context.theme) else value.data
        } else android.graphics.Color.DKGRAY
    }
}
