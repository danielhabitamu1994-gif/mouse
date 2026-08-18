package dev.habitamu.mouse

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * The floating trackpad. Finger movement inside the pad is translated into relative cursor
 * movement; lifting the finger asks [MouseController] to click wherever the cursor ended up.
 *
 * The panel can be dragged around by its header and collapsed to a small puck so it does not
 * cover the part of the screen that still works.
 */
@SuppressLint("ClickableViewAccessibility")
class TrackpadPanel(context: Context, private val controller: MouseController) {

    val root: View = LayoutInflater.from(context).inflate(R.layout.overlay_trackpad, null)

    private val expandedPanel: View = root.findViewById(R.id.panel_expanded)
    private val collapsedPuck: View = root.findViewById(R.id.panel_collapsed)
    private val pad: View = root.findViewById(R.id.pad)
    private val padHint: TextView = root.findViewById(R.id.pad_hint)
    private val modeButton: Button = root.findViewById(R.id.btn_mode)
    private val dragButton: Button = root.findViewById(R.id.btn_drag)
    private val blockerButton: Button = root.findViewById(R.id.btn_blocker)

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var lastX = 0f
    private var lastY = 0f
    private var travelled = 0f

    /** Set when a long press already fired, so the following release does not also click. */
    private var gestureConsumed = false

    private val longPressRunnable = Runnable {
        gestureConsumed = true
        controller.longClickAtCursor()
    }

    init {
        pad.setOnTouchListener { _, event -> onPadTouch(event) }

        root.findViewById<View>(R.id.handle).setOnTouchListener(PanelDragListener(onTap = null))
        collapsedPuck.setOnTouchListener(PanelDragListener(onTap = { setCollapsed(false) }))
        root.findViewById<View>(R.id.btn_collapse).setOnClickListener { setCollapsed(true) }

        modeButton.setOnClickListener {
            controller.settings.clickOnRelease = !controller.settings.clickOnRelease
            syncState()
        }
        root.findViewById<View>(R.id.btn_long).setOnClickListener { controller.longClickAtCursor() }
        dragButton.setOnClickListener {
            if (controller.isDragArmed()) controller.cancelDrag() else controller.armDrag()
            syncState()
        }
        blockerButton.setOnClickListener {
            controller.toggleBlocker()
            syncState()
        }

        root.findViewById<View>(R.id.btn_back).setOnClickListener {
            controller.globalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        root.findViewById<View>(R.id.btn_home).setOnClickListener {
            controller.globalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
        root.findViewById<View>(R.id.btn_recents).setOnClickListener {
            controller.globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        }

        syncState()
    }

    /** Refresh every label that mirrors state owned elsewhere. */
    fun syncState() {
        val tapMode = controller.settings.clickOnRelease
        modeButton.setText(if (tapMode) R.string.pad_mode_tap else R.string.pad_mode_move)
        modeButton.isSelected = tapMode

        val armed = controller.isDragArmed()
        dragButton.isSelected = armed

        blockerButton.setText(
            if (controller.isBlockerEnabled()) R.string.pad_blocker_on else R.string.pad_blocker_off
        )
        blockerButton.isSelected = controller.isBlockerEnabled()

        padHint.setText(
            when {
                armed -> R.string.pad_hint_drag
                tapMode -> R.string.pad_hint_tap
                else -> R.string.pad_hint_move
            }
        )
    }

    fun setCollapsed(collapsed: Boolean) {
        expandedPanel.visibility = if (collapsed) View.GONE else View.VISIBLE
        collapsedPuck.visibility = if (collapsed) View.VISIBLE else View.GONE
        root.post { controller.onTrackpadResized() }
    }

    fun release() {
        handler.removeCallbacks(longPressRunnable)
    }

    private fun onPadTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                travelled = 0f
                gestureConsumed = false
                pad.isPressed = true
                // A hold on the pad is a long click at the cursor, the same as holding an icon.
                if (!controller.isDragArmed()) {
                    handler.postDelayed(longPressRunnable, longPressTimeout)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y

                travelled += hypot(dx, dy)
                if (travelled > touchSlop) handler.removeCallbacks(longPressRunnable)

                val gain = gainFor(dx, dy)
                controller.moveCursorBy(dx * gain, dy * gain)
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                pad.isPressed = false
                if (!gestureConsumed) {
                    when {
                        controller.isDragArmed() -> {
                            controller.finishDragAtCursor()
                            syncState()
                        }
                        controller.settings.clickOnRelease -> controller.clickAtCursor()
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                pad.isPressed = false
            }
        }
        return true
    }

    /**
     * Pointer acceleration: a slow finger stays precise, a fast flick crosses the screen. The
     * boost is multiplied by the user's sensitivity setting.
     */
    private fun gainFor(dx: Float, dy: Float): Float {
        val speed = hypot(dx, dy)
        val boost = 1f + min(speed / ACCELERATION_DIVISOR, MAX_ACCELERATION_BOOST)
        return controller.settings.sensitivity * boost
    }

    /** Drags the whole window by the finger delta; a tap without movement is forwarded instead. */
    private inner class PanelDragListener(private val onTap: (() -> Unit)?) : View.OnTouchListener {

        private var anchorX = 0f
        private var anchorY = 0f
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    anchorX = event.rawX
                    anchorY = event.rawY
                    moved = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - anchorX
                    val dy = event.rawY - anchorY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) moved = true
                    if (moved) {
                        controller.moveTrackpadBy(dx, dy)
                        anchorX = event.rawX
                        anchorY = event.rawY
                    }
                }

                MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()

                else -> return false
            }
            return true
        }
    }

    private companion object {
        const val ACCELERATION_DIVISOR = 22f
        const val MAX_ACCELERATION_BOOST = 1.6f
    }
}
