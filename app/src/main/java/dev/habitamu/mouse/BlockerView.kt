package dev.habitamu.mouse

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * A transparent sheet that swallows every touch inside its bounds.
 *
 * On a cracked digitiser the dead area fires ghost touches constantly; this window sits above
 * everything else in that region and returns true from [onTouchEvent], so those touches are
 * consumed here instead of reaching the app underneath.
 *
 * It draws nothing at all unless [showOutline] is set, which only happens while the settings
 * screen is open: the outline is there to show what is being adjusted, not to sit on top of
 * everything the user does afterwards.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class BlockerView(context: Context) : View(context) {

    private val wash = Paint().apply { color = Color.parseColor("#14FF5252") }

    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
        color = Color.parseColor("#FF5252")
        pathEffect = DashPathEffect(floatArrayOf(context.dp(9f), context.dp(7f)), 0f)
    }

    private val labelBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC1B1B1B")
    }

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFCDD2")
        textSize = context.dp(11f)
        textAlign = Paint.Align.CENTER
    }

    private val bounds = RectF()
    private val labelBounds = RectF()

    /** Show the dashed edge, the wash and the label. Off outside the settings screen. */
    var showOutline: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Every touch that lands in this window dies here, outline or not. */
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showOutline) return

        val inset = edge.strokeWidth / 2f
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawRect(bounds, wash)
        canvas.drawRect(bounds, edge)

        val text = context.getString(R.string.blocker_watermark)
        val textWidth = label.measureText(text)
        val padH = context.dp(10f)
        val padV = context.dp(5f)
        val centerX = width / 2f
        val baseline = height - context.dp(14f)

        labelBounds.set(
            centerX - textWidth / 2f - padH,
            baseline - label.textSize - padV,
            centerX + textWidth / 2f + padH,
            baseline + padV
        )
        val radius = labelBounds.height() / 2f
        canvas.drawRoundRect(labelBounds, radius, radius, labelBackground)
        canvas.drawText(text, centerX, baseline, label)
    }
}
