package dev.habitamu.mouse

/**
 * What the floating control is allowed to ask for. Implemented by [OverlayService], which owns
 * the cursor position, the windows, and the connection to the accessibility service.
 */
interface MouseController {

    val settings: Prefs

    /** Move the virtual cursor by a screen-space delta, clamped to the display. */
    fun moveCursorBy(dx: Float, dy: Float)

    fun clickAtCursor()

    fun longClickAtCursor()

    /**
     * Start a drag where the cursor is. [withHold] presses and holds before moving, for picking
     * something up; without it the gesture is a swipe, for scrolling.
     */
    fun beginDrag(withHold: Boolean)

    fun endDrag()

    /** See AccessibilityService.GLOBAL_ACTION_* constants. */
    fun globalAction(action: Int)

    /** Move the floating window itself. */
    fun movePadBy(dx: Float, dy: Float)

    /** The user let go of the window after dragging it. */
    fun onPadReleased()

    /** The panel is about to change size; reposition it so its anchored corner stays put. */
    fun onPadResized()

    fun toggleBlocker()

    fun isBlockerEnabled(): Boolean

    /** True while the user is choosing where the bubble lives. */
    fun isPlacingBubble(): Boolean
}
