package dev.habitamu.mouse

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.View
import kotlin.math.hypot

/**
 * The virtual pointer: a rounded arrow with a heavy white outline and a soft shadow, so it stays
 * readable on top of any app. The hot spot - the point that actually gets clicked - is the tip,
 * not the centre of the view; [hotspotX] and [hotspotY] say where that is.
 */
@SuppressLint("ViewConstructor")
class CursorView(context: Context) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = IDLE_FILL
    }

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val arrow = Path()

    /** Filled while a synthetic gesture is in flight, as feedback that the tap landed. */
    var gestureInFlight: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                fill.color = if (value) ACTIVE_FILL else IDLE_FILL
                invalidate()
            }
        }

    init {
        // A shadow layer needs software rendering.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = minOf(w, h) * SHAPE_SCALE
        buildArrow(size)

        outline.strokeWidth = size * OUTLINE_WIDTH
        outline.setShadowLayer(size * 0.07f, size * 0.035f, size * 0.05f, SHADOW)
    }

    private fun buildArrow(size: Float) {
        val points = SHAPE.map { PointF(it.x * size, it.y * size) }
        val radii = CORNER_RADII.map { it * size }

        arrow.reset()
        val count = points.size
        for (index in 0 until count) {
            val previous = points[(index + count - 1) % count]
            val corner = points[index]
            val next = points[(index + 1) % count]

            val entry = pointTowards(corner, previous, radii[index])
            val exit = pointTowards(corner, next, radii[index])

            if (index == 0) arrow.moveTo(entry.x, entry.y) else arrow.lineTo(entry.x, entry.y)
            arrow.quadTo(corner.x, corner.y, exit.x, exit.y)
        }
        arrow.close()
    }

    /** A point [distance] away from [from], in the direction of [towards]. */
    private fun pointTowards(from: PointF, towards: PointF, distance: Float): PointF {
        val dx = towards.x - from.x
        val dy = towards.y - from.y
        val length = hypot(dx, dy).coerceAtLeast(0.001f)
        val step = distance.coerceAtMost(length / 2f)
        return PointF(from.x + dx / length * step, from.y + dy / length * step)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The outline is drawn first at double width; the fill then covers its inner half, which
        // leaves an even white border and keeps the shadow outside the shape.
        canvas.drawPath(arrow, outline)
        canvas.drawPath(arrow, fill)
    }

    companion object {
        /** Fraction of the view the arrow occupies; the rest is room for the shadow. */
        private const val SHAPE_SCALE = 0.88f
        private const val OUTLINE_WIDTH = 0.15f

        private const val IDLE_FILL = 0xFF0D0D0D.toInt()
        private const val ACTIVE_FILL = 0xFF00B8D4.toInt()
        private const val SHADOW = 0x73000000

        /** Tip, right point, notch, bottom point - in fractions of the arrow's box. */
        private val SHAPE = listOf(
            PointF(0.09f, 0.04f),
            PointF(0.95f, 0.62f),
            PointF(0.53f, 0.68f),
            PointF(0.33f, 0.98f)
        )
        private val CORNER_RADII = listOf(0.10f, 0.12f, 0.11f, 0.11f)

        fun hotspotX(sizePx: Int): Float = sizePx * SHAPE_SCALE * SHAPE[0].x
        fun hotspotY(sizePx: Int): Float = sizePx * SHAPE_SCALE * SHAPE[0].y
    }
}
