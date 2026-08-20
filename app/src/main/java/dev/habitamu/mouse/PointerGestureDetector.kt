package dev.habitamu.mouse

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Turns finger movement on the pad or on the bubble into pointer gestures, the way a laptop
 * trackpad behaves:
 *
 * - slide, then lift: the cursor moves, and clicks where it stopped if [tapAfterSlide] is on;
 * - tap without sliding: a click where the cursor is;
 * - double tap: the second tap is a press, so releasing it long-presses at the cursor;
 * - double tap where the second tap slides instead of releasing: a drag from the cursor. Slide
 *   straight away and it is a swipe, good for scrolling; rest a moment first and the drag presses
 *   and holds before it moves, which is what picking something up needs;
 * - triple tap, where [waitForMoreTaps] is on: [Listener.onTripleTap], and neither the click nor
 *   the long press fires.
 *
 * Waiting for more taps costs the click a double-tap timeout of delay, so it is only switched on
 * where the triple tap is needed. Screen coordinates are used throughout, so this keeps working
 * while the view underneath the finger is itself being moved - which is what the bubble does.
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

        fun onTripleTap() = Unit

        /** A touch started or ended, for visual feedback and for the bubble's spring back. */
        fun onTouchStart() = Unit

        fun onTouchEnd() = Unit
    }

    private enum class State { IDLE, SLIDING, SECOND_TAP, THIRD_TAP, DRAGGING }

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    /** Hold the click and the long press back long enough to notice a third tap. */
    var waitForMoreTaps: Boolean = false

    /** Whether the end of a slide clicks where the cursor stopped. */
    var tapAfterSlide: Boolean = false

    private var state = State.IDLE
    private var lastX = 0f
    private var lastY = 0f
    private var travelled = 0f
    private var pressedAt = 0L
    private var lastTapAt = 0L
    private var tapsSoFar = 0

    private val deferredTap = Runnable { listener.onTap() }
    private val deferredHold = Runnable { listener.onHold() }

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Whatever the previous tap was about to do, this one supersedes it.
                cancelDeferred()

                lastX = event.rawX
                lastY = event.rawY
                travelled = 0f
                pressedAt = event.eventTime

                val continues = event.eventTime - lastTapAt <= doubleTapTimeout
                val taps = if (continues) tapsSoFar else 0
                state = when {
                    taps == 1 -> State.SECOND_TAP
                    // Without the triple tap to look out for, a third tap is just another click.
                    taps >= 2 && waitForMoreTaps -> State.THIRD_TAP
                    else -> State.SLIDING
                }
                listener.onTouchStart()
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                lastX = event.rawX
                lastY = event.rawY
                travelled += hypot(dx, dy)

                val waitingTap = state == State.SECOND_TAP || state == State.THIRD_TAP
                if (waitingTap && travelled > touchSlop) {
                    // Held a repeat tap and started moving: that is a drag.
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
                        countTap(event.eventTime, 1)
                        fire(deferredTap) { listener.onTap() }
                    } else {
                        // A slide is not a tap, so no third one can be on its way: if the click
                        // is wanted here it goes out straight away, with no waiting.
                        forgetTaps()
                        if (tapAfterSlide) listener.onTap()
                    }

                    State.SECOND_TAP -> {
                        countTap(event.eventTime, 2)
                        fire(deferredHold) { listener.onHold() }
                    }

                    State.THIRD_TAP -> {
                        forgetTaps()
                        listener.onTripleTap()
                    }

                    State.DRAGGING -> {
                        forgetTaps()
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
                forgetTaps()
                listener.onTouchEnd()
            }

            else -> return false
        }
        return true
    }

    fun release() = cancelDeferred()

    private fun countTap(at: Long, count: Int) {
        lastTapAt = at
        tapsSoFar = count
    }

    private fun forgetTaps() {
        lastTapAt = 0L
        tapsSoFar = 0
    }

    private fun fire(deferred: Runnable, now: () -> Unit) {
        if (waitForMoreTaps) handler.postDelayed(deferred, doubleTapTimeout) else now()
    }

    private fun cancelDeferred() {
        handler.removeCallbacks(deferredTap)
        handler.removeCallbacks(deferredHold)
    }
}
