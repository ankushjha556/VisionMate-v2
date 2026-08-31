package com.ankushjha.visionmate.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.ankushjha.visionmate.util.Closeness
import com.ankushjha.visionmate.util.Detection
import com.ankushjha.visionmate.util.ObstacleAssessment
import kotlin.math.max
import kotlin.math.min

/**
 * Draws detection boxes + labels over the PreviewView.
 *
 * Boxes arrive in the *upright analysis bitmap* coordinate space; this view
 * maps them onto itself using the same center-crop (FILL_CENTER) transform
 * CameraX PreviewView applies, so boxes align with what the user sees.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class OverlayItem(
        val detection: Detection,
        val closeness: Closeness
    )

    private var bitmapW = 0
    private var bitmapH = 0
    private var items: List<OverlayItem> = emptyList()
    private var assessment: ObstacleAssessment? = null

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val boxBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCC000000.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        isFakeBoldText = true
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun update(bitmapWidth: Int, bitmapHeight: Int, newItems: List<OverlayItem>, assessment: ObstacleAssessment?) {
        bitmapW = bitmapWidth
        bitmapH = bitmapHeight
        items = newItems
        this.assessment = assessment
        postInvalidate()
    }

    fun clear() {
        items = emptyList()
        assessment = null
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (bitmapW <= 0 || bitmapH <= 0 || items.isEmpty()) return

        // FILL_CENTER mapping: scale = max(viewW/bmpW, viewH/bmpH), centered.
        val scale = max(width.toFloat() / bitmapW, height.toFloat() / bitmapH)
        val drawW = bitmapW * scale
        val drawH = bitmapH * scale
        val offX = (width - drawW) / 2f
        val offY = (height - drawH) / 2f

        for (item in items) {
            val d = item.detection
            val left = offX + d.x1 * scale
            val top = offY + d.y1 * scale
            val right = offX + d.x2 * scale
            val bottom = offY + d.y2 * scale
            val rect = RectF(left, top, right, bottom)

            when (item.closeness) {
                Closeness.STOP -> {
                    fillPaint.color = 0x33FF5252
                    boxPaint.color = 0xFFFF5252.toInt()
                    canvas.drawRect(rect, fillPaint)
                }
                Closeness.CLOSE -> {
                    fillPaint.color = 0x26FFB300
                    boxPaint.color = 0xFFFFB300.toInt()
                    canvas.drawRect(rect, fillPaint)
                }
                Closeness.MID -> boxPaint.color = 0xCC4DD0C4.toInt()
                Closeness.CLEAR -> boxPaint.color = 0x889AA3B2.toInt()
            }
            canvas.drawRect(rect, boxPaint)

            val label = "${d.label} ${(d.score * 100).toInt()}%"
            val textW = textPaint.measureText(label)
            canvas.drawRect(left, top - 42f, left + textW + 16f, top, boxBgPaint)
            canvas.drawText(label, left + 8f, top - 10f, textPaint)
        }

        // Center guide: subtle brackets showing the "ahead" column the obstacle
        // engine watches. Purely visual, no accessibility burden.
        val cx = width / 2f
        val cyTop = height * 0.30f
        val cyBot = height * 0.92f
        boxPaint.color = 0x664DD0C4.toInt()
        boxPaint.strokeWidth = 3f
        canvas.drawLine(cx - width * 0.14f, cyTop, cx - width * 0.14f, cyBot, boxPaint)
        canvas.drawLine(cx + width * 0.14f, cyTop, cx + width * 0.14f, cyBot, boxPaint)
    }
}
