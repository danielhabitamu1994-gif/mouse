package dev.habitamu.mouse

/**
 * What the trackpad is allowed to ask for. Implemented by [OverlayService], which owns the
 * cursor position, the windows, and the connection to the accessibility service.
 */
interface MouseController {

    val settings: Prefs

    /** Move the virtual cursor by a screen-space delta, clamped to the display. */
    fun moveCursorBy(dx: Float, dy: Float)

    fun clickAtCursor()

    fun longClickAtCursor()

    /** Remember the cursor position as the start of a drag. */
    fun armDrag()

    fun cancelDrag()

    /** Drag from the armed anchor to wherever the cursor is now. */
    fun finishDragAtCursor()

    fun isDragArmed(): Boolean

    /** See AccessibilityService.GLOBAL_ACTION_* constants. */
    fun globalAction(action: Int)

    /** Move the trackpad window itself. */
    fun moveTrackpadBy(dx: Float, dy: Float)

    /** The trackpad changed size (collapsed or expanded); keep it on screen. */
    fun onTrackpadResized()

    fun toggleBlocker()

    fun isBlockerEnabled(): Boolean
}
