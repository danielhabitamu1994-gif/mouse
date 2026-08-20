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
 * readable on top of any app.
 *
 * The arrow is taller than it is wide, and the view is sized to match rather than squared off,
 * which is what keeps the shape from looking stretched sideways. The hot spot - the point that
 * actually gets clicked - is the tip, so the window is offset by [hotspotX] and [hotspotY], and
 * [widthFor] gives the window width that goes with a given height.
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
        buildArrow(h.toFloat())

        // Drawn at double width; the fill then covers the inner half, so the visible band is half.
        outline.strokeWidth = h * INSET * 2f
        outline.setShadowLayer(h * 0.05f, h * 0.025f, h * 0.035f, SHADOW)
    }

    private fun buildArrow(viewHeight: Float) {
        val height = viewHeight * SHAPE_HEIGHT
        val width = height * SHAPE_ASPECT
        val originX = viewHeight * INSET
        val originY = viewHeight * INSET

        val points = SHAPE.map { PointF(originX + it.x * width, originY + it.y * height) }
        val radii = CORNER_RADII.map { it * height }

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
        canvas.drawPath(arrow, outline)
        canvas.drawPath(arrow, fill)
    }

    companion object {
        /** Width over height of the arrow itself, taken from the artwork. */
        private const val SHAPE_ASPECT = 0.84f

        /** Half the outline width, and the margin the shape keeps from the view edges. */
        private const val INSET = 0.045f

        /** Room on the right and below for the shadow to fall into. */
        private const val SHADOW_PAD = 0.06f

        private const val SHAPE_HEIGHT = 1f - 2 * INSET - SHADOW_PAD

        private const val IDLE_FILL = 0xFF0D0D0D.toInt()
        private const val ACTIVE_FILL = 0xFF00B8D4.toInt()
        private const val SHADOW = 0x73000000

        /** Tip, right point, notch, bottom point - in fractions of the arrow's own box. */
        private val SHAPE = listOf(
            PointF(0.00f, 0.00f),
            PointF(1.00f, 0.56f),
            PointF(0.51f, 0.69f),
            PointF(0.11f, 1.00f)
        )
        private val CORNER_RADII = listOf(0.10f, 0.13f, 0.11f, 0.11f)

        /** The window width that goes with a window of this height. */
        fun widthFor(heightPx: Int): Int =
            (heightPx * (SHAPE_HEIGHT * SHAPE_ASPECT + 2 * INSET + SHADOW_PAD)).toInt()

        fun hotspotX(heightPx: Int): Float = heightPx * INSET

        fun hotspotY(heightPx: Int): Float = heightPx * INSET
    }
}
