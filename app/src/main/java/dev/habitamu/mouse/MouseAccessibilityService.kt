package dev.habitamu.mouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent

/**
 * Performs the actual input on behalf of the trackpad.
 *
 * The overlays cannot inject touches themselves - only an accessibility service can, through
 * [dispatchGesture]. This service holds no state beyond its own connection; [OverlayService]
 * decides *where* to click and calls in here to make it happen.
 */
class MouseAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        broadcastState()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        broadcastState()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        broadcastState()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Single tap at ([x], [y]), in absolute screen pixels. */
    fun tap(x: Float, y: Float, onFinished: (Boolean) -> Unit) {
        dispatch(singleStroke(pointPath(x, y), TAP_DURATION_MS), onFinished)
    }

    /** Press-and-hold at ([x], [y]) for long enough to register as a long click. */
    fun longPress(x: Float, y: Float, onFinished: (Boolean) -> Unit) {
        val duration = ViewConfiguration.getLongPressTimeout().toLong() + LONG_PRESS_MARGIN_MS
        dispatch(singleStroke(pointPath(x, y), duration), onFinished)
    }

    /** Press at the first point, slide to the second, release - used for dragging and swiping. */
    fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, onFinished: (Boolean) -> Unit) {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        dispatch(singleStroke(path, DRAG_DURATION_MS), onFinished)
    }

    /** Back / Home / Recents and friends - see [AccessibilityService.GLOBAL_ACTION_BACK]. */
    fun globalAction(action: Int): Boolean = performGlobalAction(action)

    private fun singleStroke(path: Path, durationMs: Long): GestureDescription =
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()

    /**
     * A stroke needs a path with at least one segment, so a tap is drawn as a one pixel line.
     * The caller keeps the cursor inside the display bounds with room for that pixel, because
     * a path that leaves the display makes [GestureDescription] throw.
     */
    private fun pointPath(x: Float, y: Float): Path = Path().apply {
        moveTo(x, y)
        lineTo(x + 1f, y + 1f)
    }

    private fun dispatch(gesture: GestureDescription, onFinished: (Boolean) -> Unit) {
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onFinished(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = onFinished(false)
        }
        val accepted = try {
            dispatchGesture(gesture, callback, mainHandler)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Gesture rejected", e)
            false
        }
        if (!accepted) onFinished(false)
    }

    private fun broadcastState() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    companion object {
        private const val TAG = "MouseA11y"

        /** Sent to our own package whenever the service connects or disconnects. */
        const val ACTION_STATE_CHANGED = "dev.habitamu.mouse.ACCESSIBILITY_STATE_CHANGED"

        private const val TAP_DURATION_MS = 60L
        private const val DRAG_DURATION_MS = 400L
        private const val LONG_PRESS_MARGIN_MS = 250L

        @Volatile
        var instance: MouseAccessibilityService? = null
            private set

        /**
         * Whether the user has switched the service on in Settings. This is checked instead of
         * [instance] where a stale answer would be confusing, e.g. on the setup screen: the
         * system may not have bound the service yet when the setting flips.
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, MouseAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            for (entry in splitter) {
                if (ComponentName.unflattenFromString(entry) == expected) return true
            }
            return false
        }
    }
}
