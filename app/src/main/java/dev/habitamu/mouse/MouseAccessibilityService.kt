package dev.habitamu.mouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs

/**
 * Everything that only an accessibility service can do:
 *
 * - inject taps, long presses and drags with [dispatchGesture];
 * - Back, Home and Recents with [performGlobalAction];
 * - find where the keyboard is, so the floating control can get out of its way;
 * - hear both volume keys pressed together, which is how a switched-off bubble comes back.
 *
 * It holds no state beyond that; [OverlayService] decides what to do and calls in here.
 */
class MouseAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var keyboardTop = NO_KEYBOARD
    private var volumeUpAt = 0L
    private var volumeDownAt = 0L
    private var volumeUpHeld = false
    private var volumeDownHeld = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The flags are declared in the service's XML, but the system can still be holding an
        // older copy of it - after an app update, typically - and then key events never arrive.
        // Asking for them again here costs nothing and fixes that case.
        serviceInfo?.let { info ->
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
        }
        instance = this
        broadcastState()
    }

    /** Whether the system is actually sending us key events, for the setup screen to report. */
    fun canFilterKeys(): Boolean {
        val flags = serviceInfo?.flags ?: return false
        return flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = checkKeyboard()

    override fun onInterrupt() = Unit

    // -------------------------------------------------------------- keyboard

    /**
     * The top edge of the keyboard in screen pixels, or [NO_KEYBOARD] when there is none. The
     * window list is the only reliable way to learn this from a service: an overlay window is not
     * told about the keyboard's insets on most devices.
     */
    fun keyboardTop(): Int = keyboardTop

    private fun checkKeyboard() {
        val top = try {
            var found = NO_KEYBOARD
            val bounds = Rect()
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                window.getBoundsInScreen(bounds)
                if (bounds.height() > 0) found = minOf(found, bounds.top)
            }
            found
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read the window list", e)
            NO_KEYBOARD
        }

        if (top == keyboardTop) return
        keyboardTop = top
        keyboardListener?.invoke(top)
    }

    // --------------------------------------------------------- volume combo

    /**
     * Both volume keys count as the shortcut, and there are two ways to give it: hold one and
     * press the other, or press them one after the other quickly. Most phones have a single
     * rocker where pressing both ends at once is awkward, so the second way is the one that
     * usually gets used.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                volumeUpHeld = down
                if (down && event.repeatCount == 0) volumeUpAt = event.eventTime
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                volumeDownHeld = down
                if (down && event.repeatCount == 0) volumeDownAt = event.eventTime
            }

            else -> return false
        }
        if (!down) return false

        val bothHeld = volumeUpHeld && volumeDownHeld
        val oneAfterTheOther = volumeUpAt > 0L && volumeDownAt > 0L &&
            abs(volumeUpAt - volumeDownAt) <= VOLUME_COMBO_MS
        if (!bothHeld && !oneAfterTheOther) return false

        volumeUpAt = 0L
        volumeDownAt = 0L
        val listener = shortcutListener ?: return false
        listener()
        // Swallowed, so this key at least does not also move the volume.
        return true
    }

    // -------------------------------------------------------------- gestures

    /** Single tap at ([x], [y]), in absolute screen pixels. */
    fun tap(x: Float, y: Float, onFinished: (Boolean) -> Unit) {
        dispatch(singleStroke(pointPath(x, y), TAP_DURATION_MS), onFinished)
    }

    /** Press-and-hold at ([x], [y]) for long enough to register as a long click. */
    fun longPress(x: Float, y: Float, onFinished: (Boolean) -> Unit) {
        val duration = ViewConfiguration.getLongPressTimeout().toLong() + LONG_PRESS_MARGIN_MS
        dispatch(singleStroke(pointPath(x, y), duration), onFinished)
    }

    /**
     * Press at the first point, slide to the second, release. The whole gesture is sent once the
     * finger is already up: a drag dispatched piece by piece as the finger moves was tried and
     * did not scroll at all on device.
     */
    fun drag(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        hold: Boolean,
        onFinished: (Boolean) -> Unit
    ) {
        val slide = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        if (!hold || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            dispatch(singleStroke(slide, DRAG_DURATION_MS), onFinished)
            return
        }

        val press = GestureDescription.StrokeDescription(pointPath(fromX, fromY), 0L, DRAG_HOLD_MS, true)
        dispatch(GestureDescription.Builder().addStroke(press).build()) { pressed ->
            if (!pressed) {
                onFinished(false)
                return@dispatch
            }
            // A continued stroke has to start where the previous one ended, which is the far
            // corner of the one pixel press path.
            val continued = Path().apply {
                moveTo(fromX + 1f, fromY + 1f)
                lineTo(toX, toY)
            }
            val move = press.continueStroke(continued, 0L, DRAG_DURATION_MS, false)
            dispatch(GestureDescription.Builder().addStroke(move).build(), onFinished)
        }
    }

    /** Back / Home / Recents and friends - see [AccessibilityService.GLOBAL_ACTION_BACK]. */
    fun globalAction(action: Int): Boolean = performGlobalAction(action)

    // -------------------------------------------------------------- helpers

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

        /** No keyboard on screen. */
        const val NO_KEYBOARD = Int.MAX_VALUE

        private const val TAP_DURATION_MS = 60L
        private const val DRAG_DURATION_MS = 400L
        private const val LONG_PRESS_MARGIN_MS = 250L

        /** Long enough that the target treats the press as a long press before the slide. */
        private const val DRAG_HOLD_MS = 700L

        /** How close together the two volume keys count as one shortcut. */
        private const val VOLUME_COMBO_MS = 800L

        @Volatile
        var instance: MouseAccessibilityService? = null
            private set

        /** Called with the keyboard's top edge, or [NO_KEYBOARD], whenever it changes. */
        @Volatile
        var keyboardListener: ((Int) -> Unit)? = null

        /** Called when both volume keys are pressed together. */
        @Volatile
        var shortcutListener: (() -> Unit)? = null

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
