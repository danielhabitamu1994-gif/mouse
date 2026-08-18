package dev.habitamu.mouse

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
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
 * The floating control, in either of its two shapes:
 *
 * - [PadMode.TRACKPAD]: a panel with a pad area and navigation keys, which collapses to a puck.
 * - [PadMode.BUBBLE]: nothing but the puck. It follows the finger, the cursor moves with it, and
 *   [MouseController.onPadReleased] springs it back to its home spot afterwards.
 *
 * Both shapes read gestures through the same [PointerGestureDetector], so tap, double-tap-hold
 * and drag behave identically wherever the finger lands.
 */
@SuppressLint("ClickableViewAccessibility")
class TrackpadPanel(context: Context, private val controller: MouseController) :
    PointerGestureDetector.Listener {

    val root: View = LayoutInflater.from(context).inflate(R.layout.overlay_trackpad, null)

    private val expandedPanel: View = root.findViewById(R.id.panel_expanded)
    private val collapsedPuck: View = root.findViewById(R.id.panel_collapsed)
    private val pad: View = root.findViewById(R.id.pad)
    private val padHint: TextView = root.findViewById(R.id.pad_hint)
    private val blockerButton: Button = root.findViewById(R.id.btn_blocker)

    private val detector = PointerGestureDetector(context, this)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var mode = PadMode.TRACKPAD

    /** Matches the layout, which starts with the panel showing. */
    private var collapsed = false

    /**
     * A bubble switched off with a triple tap stays on screen, faded, and still listens for the
     * triple tap that brings it back - but it drives nothing in the meantime.
     */
    private var active = true

    fun applyMode(newMode: PadMode) {
        mode = newMode
        // Only the bubble has the triple tap, and only it pays for it in click latency.
        detector.waitForMoreTaps = newMode == PadMode.BUBBLE
        if (newMode == PadMode.BUBBLE) {
            setCollapsed(true)
        } else if (!active) {
            setActive(true)
        }
        syncState()
    }

    private fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        // Deliberately not disabling the view: a disabled view gets no touches, and the switched
        // off bubble still has to notice the triple tap that brings it back. Fading the window is
        // the whole of the change, and the controller does that.
        controller.onControlActiveChanged(value)
    }

    fun release() = detector.release()

    /** Refresh every label that mirrors state owned elsewhere. */
    fun syncState() {
        blockerButton.setText(
            if (controller.isBlockerEnabled()) R.string.pad_blocker_on else R.string.pad_blocker_off
        )
        blockerButton.isSelected = controller.isBlockerEnabled()
        padHint.setText(R.string.pad_hint)
        collapsedPuck.isActivated = controller.isPlacingBubble()
    }

    fun setCollapsed(value: Boolean) {
        // The bubble is all there is in bubble mode; it never opens into the panel.
        if (!value && mode == PadMode.BUBBLE) return

        val changed = collapsed != value
        collapsed = value
        // Applied every time rather than only on a change, so the flag can never drift out of
        // step with what is actually on screen.
        expandedPanel.visibility = if (value) View.GONE else View.VISIBLE
        collapsedPuck.visibility = if (value) View.VISIBLE else View.GONE
        // Reposition before the layout pass runs, so the panel does not flash at the wrong edge.
        if (changed) controller.onPadResized()
    }

    // ------------------------------------------------------------- gestures

    override fun onTripleTap() {
        if (mode == PadMode.BUBBLE) setActive(!active)
    }

    override fun onPointerMove(dx: Float, dy: Float) {
        if (!active) return
        val gain = gainFor(dx, dy)
        controller.moveCursorBy(dx * gain, dy * gain)
        // In bubble mode the puck itself follows the finger, like a stick you keep stroking.
        if (mode == PadMode.BUBBLE) controller.movePadBy(dx, dy)
    }

    override fun onTap() {
        if (active) controller.clickAtCursor()
    }

    override fun onHold() {
        if (active) controller.longClickAtCursor()
    }

    override fun onDragBegin(withHold: Boolean) {
        if (active) controller.beginDrag(withHold)
    }

    override fun onDragEnd() {
        if (active) controller.endDrag()
    }

    override fun onTouchStart() {
        pad.isPressed = true
        collapsedPuck.isPressed = true
    }

    override fun onTouchEnd() {
        pad.isPressed = false
        collapsedPuck.isPressed = false
        if (mode == PadMode.BUBBLE && active) controller.onPadReleased()
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

    // -------------------------------------------------------- window moving

    /** Drags the whole window by the finger delta. */
    private val windowDrag = object : View.OnTouchListener {
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
                        controller.movePadBy(dx, dy)
                        anchorX = event.rawX
                        anchorY = event.rawY
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> controller.onPadReleased()

                else -> return false
            }
            return true
        }
    }

    /** The puck in trackpad mode: drag to move it, tap to open the panel. */
    private val puckDrag = object : View.OnTouchListener {
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
                        controller.movePadBy(dx, dy)
                        anchorX = event.rawX
                        anchorY = event.rawY
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) controller.onPadReleased() else setCollapsed(false)
                }

                MotionEvent.ACTION_CANCEL -> controller.onPadReleased()

                else -> return false
            }
            return true
        }
    }

    init {
        pad.setOnTouchListener { _, event -> detector.onTouch(event) }

        collapsedPuck.setOnTouchListener { _, event ->
            when {
                // Choosing the bubble's home spot: the finger drags the window, nothing else.
                controller.isPlacingBubble() -> windowDrag.onTouch(collapsedPuck, event)
                mode == PadMode.BUBBLE -> detector.onTouch(event)
                else -> puckDrag.onTouch(collapsedPuck, event)
            }
        }

        root.findViewById<View>(R.id.handle).setOnTouchListener(windowDrag)
        root.findViewById<View>(R.id.btn_collapse).setOnClickListener { setCollapsed(true) }
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

        applyMode(controller.settings.padMode)
    }

    private companion object {
        const val ACCELERATION_DIVISOR = 22f
        const val MAX_ACCELERATION_BOOST = 1.6f
    }
}
