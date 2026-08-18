package dev.habitamu.mouse

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * The virtual pointer. The hot spot - the point that actually gets clicked - is the exact centre
 * of this view, so [OverlayService] only has to offset the window by half the view size.
 */
@SuppressLint("ViewConstructor")
class CursorView(context: Context) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#121212")
    }

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.5f)
        color = Color.WHITE
    }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.5f)
        color = Color.parseColor("#00E5FF")
    }

    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E5FF")
    }

    private val arrow = Path()

    /** Filled while a synthetic gesture is in flight, as feedback that the tap landed. */
    var gestureInFlight: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildArrow(w / 2f, h / 2f)
    }

    private fun buildArrow(tipX: Float, tipY: Float) {
        val s = dp(1.5f)
        arrow.reset()
        arrow.moveTo(tipX, tipY)
        arrow.lineTo(tipX, tipY + 17f * s)
        arrow.lineTo(tipX + 4.2f * s, tipY + 13f * s)
        arrow.lineTo(tipX + 7.1f * s, tipY + 19.4f * s)
        arrow.lineTo(tipX + 10.1f * s, tipY + 18f * s)
        arrow.lineTo(tipX + 7.2f * s, tipY + 11.8f * s)
        arrow.lineTo(tipX + 12.4f * s, tipY + 11.4f * s)
        arrow.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        canvas.drawCircle(cx, cy, dp(9f), ring)
        if (gestureInFlight) {
            canvas.drawCircle(cx, cy, dp(4.5f), ringFill)
        }

        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, outline)
    }
}
