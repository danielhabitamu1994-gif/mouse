package dev.habitamu.mouse

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Turns finger movement on the pad or on the bubble into pointer gestures, the way a laptop
 * trackpad behaves:
 *
 * - slide, then lift: the cursor moves and nothing is clicked;
 * - tap without sliding: a click where the cursor is;
 * - double tap: the second tap is a press, so releasing it long-presses at the cursor;
 * - double tap where the second tap slides instead of releasing: a drag from the cursor. Slide
 *   straight away and it is a swipe, good for scrolling; rest a moment first and the drag presses
 *   and holds before it moves, which is what picking something up needs.
 *
 * Screen coordinates are used throughout, so this keeps working while the view underneath the
 * finger is itself being moved - which is exactly what the bubble does.
 */
class PointerGestureDetector(context: Context, private val listener: Listener) {

    interface Listener {
        /** Finger delta in screen pixels; the listener decides how far the cursor travels. */
        fun onPointerMove(dx: Float, dy: Float)

        fun onTap()

        fun onHold()

        /**
         * The drag has begun where the cursor currently is. [withHold] means the finger rested
         * before moving, so the drag should press and hold before sliding.
         */
        fun onDragBegin(withHold: Boolean)

        fun onDragEnd()

        /** A touch started or ended, for visual feedback and for the bubble's spring back. */
        fun onTouchStart() = Unit

        fun onTouchEnd() = Unit
    }

    private enum class State { IDLE, SLIDING, SECOND_TAP, DRAGGING }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var state = State.IDLE
    private var lastX = 0f
    private var lastY = 0f
    private var travelled = 0f
    private var lastTapAt = 0L
    private var pressedAt = 0L

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                travelled = 0f
                pressedAt = event.eventTime
                state = if (event.eventTime - lastTapAt <= doubleTapTimeout) {
                    State.SECOND_TAP
                } else {
                    State.SLIDING
                }
                listener.onTouchStart()
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                lastX = event.rawX
                lastY = event.rawY
                travelled += hypot(dx, dy)

                if (state == State.SECOND_TAP && travelled > touchSlop) {
                    // Held the second tap and started moving: that is a drag.
                    state = State.DRAGGING
                    listener.onDragBegin(event.eventTime - pressedAt >= longPressTimeout)
                }
                if (state == State.SLIDING || state == State.DRAGGING) {
                    listener.onPointerMove(dx, dy)
                }
            }

            MotionEvent.ACTION_UP -> {
                when (state) {
                    State.SLIDING -> if (travelled <= touchSlop) {
                        lastTapAt = event.eventTime
                        listener.onTap()
                    } else {
                        // Only repositioned the cursor; a later touch is not a second tap.
                        lastTapAt = 0L
                    }

                    State.SECOND_TAP -> {
                        lastTapAt = 0L
                        listener.onHold()
                    }

                    State.DRAGGING -> {
                        lastTapAt = 0L
                        listener.onDragEnd()
                    }

                    State.IDLE -> Unit
                }
                state = State.IDLE
                listener.onTouchEnd()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (state == State.DRAGGING) listener.onDragEnd()
                state = State.IDLE
                lastTapAt = 0L
                listener.onTouchEnd()
            }

            else -> return false
        }
        return true
    }
}
