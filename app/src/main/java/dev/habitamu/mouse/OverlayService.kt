package dev.habitamu.mouse

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Owns the three overlay windows and the virtual cursor.
 *
 * Window order matters and is the order they are added: the blocker sits lowest, the cursor
 * above it so it stays visible, and the floating control on top so it keeps working even when it
 * is over the blocked region.
 */
class OverlayService : Service(), MouseController {

    override val settings: Prefs by lazy { Prefs(this) }

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var blockerView: BlockerView? = null
    private var blockerParams: WindowManager.LayoutParams? = null
    private var cursorView: CursorView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var trackpad: TrackpadPanel? = null
    private var padParams: WindowManager.LayoutParams? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var cursorX = 0f
    private var cursorY = 0f
    private var dragAnchor: PointF? = null
    private var dragHolds = false

    /** True while a synthetic gesture is passing through the blocked region. */
    private var blockerSuspended = false

    /** True while a gesture is in flight and the overlays over it must not obscure it. */
    private var injecting = false
    private var overlaysAdded = false
    private var placingBubble = false
    private var springBack: ValueAnimator? = null
    private var lastMode = PadMode.TRACKPAD

    private val restoreBlocking = Runnable { restoreTouchBlocking() }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        isRunning = true
        broadcastState()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification has to go up on every entry, including restarts by the system.
        goForeground()

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!addOverlays()) return START_NOT_STICKY

        when (intent?.action) {
            ACTION_TOGGLE_BLOCKER -> {
                toggleBlocker()
                trackpad?.syncState()
            }

            ACTION_PREVIEW -> {
                blockerView?.showOutline = intent.getBooleanExtra(EXTRA_VISIBLE, false)
            }

            ACTION_PLACE_BUBBLE -> startPlacingBubble()

            else -> applySettings()
        }

        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The new display size is not always published by the time this callback runs.
        handler.postDelayed({
            refreshScreenMetrics()
            applyBlockerState()
            moveCursorBy(0f, 0f)
            clampPad()
        }, ROTATION_SETTLE_MS)
    }

    override fun onDestroy() {
        isRunning = false
        broadcastState()
        springBack?.cancel()
        handler.removeCallbacksAndMessages(null)
        removeOverlays()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- windows

    private fun addOverlays(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.error_overlay_permission))
            stopSelf()
            return false
        }
        if (overlaysAdded) return true

        refreshScreenMetrics()
        cursorX = screenWidth / 2f
        cursorY = screenHeight * INITIAL_CURSOR_HEIGHT_FRACTION

        addBlocker()
        addCursor()
        addPad()

        overlaysAdded = true
        lastMode = settings.padMode
        applySettings()
        positionPadForMode()
        return true
    }

    private fun addBlocker() {
        val view = BlockerView(this)
        val params = overlayParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = blockerHeightPx(),
            touchable = true
        ).apply {
            // Kept below the platform's obscuring threshold so that the taps we inject through
            // the accessibility service are never discarded as "touch passed through overlay".
            alpha = BLOCKER_WINDOW_ALPHA
        }
        blockerView = view
        blockerParams = params
        windowManager.addView(view, params)
    }

    private fun addCursor() {
        val size = cursorSizePx()
        val view = CursorView(this)
        val params = overlayParams(width = size, height = size, touchable = false)
        cursorView = view
        cursorParams = params
        windowManager.addView(view, params)
        updateCursorWindow()
    }

    private fun addPad() {
        val panel = TrackpadPanel(this, this)
        val params = overlayParams(
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true
        )
        trackpad = panel
        padParams = params
        windowManager.addView(panel.root, params)
    }

    private fun removeOverlays() {
        trackpad?.root?.let { safeRemove(it) }
        cursorView?.let { safeRemove(it) }
        blockerView?.let { safeRemove(it) }
        trackpad = null
        cursorView = null
        blockerView = null
        padParams = null
        cursorParams = null
        blockerParams = null
        overlaysAdded = false
    }

    private fun overlayParams(width: Int, height: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        return WindowManager.LayoutParams(width, height, overlayType, flags, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START }
    }

    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun applySettings() {
        applyBlockerState()
        applyCursorSize()
        applyOpacity()

        val mode = settings.padMode
        trackpad?.applyMode(mode)
        if (mode != lastMode) {
            lastMode = mode
            placingBubble = false
            positionPadForMode()
        }
        trackpad?.syncState()
        updateNotification()
    }

    private fun applyOpacity(padTransparent: Boolean = false) {
        cursorParams?.let { params ->
            // The cursor sits exactly on the point being tapped, so it always has to step aside.
            params.alpha = if (injecting) 0f else settings.overlayOpacity
            cursorView?.let { safeUpdate(it, params) }
        }
        padParams?.let { params ->
            params.alpha = if (padTransparent) 0f else settings.overlayOpacity
            trackpad?.root?.let { safeUpdate(it, params) }
        }
    }

    private fun applyCursorSize() {
        val view = cursorView ?: return
        val params = cursorParams ?: return
        val size = cursorSizePx()
        if (params.width == size) return
        params.width = size
        params.height = size
        updateCursorWindow()
    }

    private fun cursorSizePx(): Int = dpInt(CURSOR_BASE_DP * settings.cursorScale)

    private fun applyBlockerState() {
        val view = blockerView ?: return
        val params = blockerParams ?: return

        val armed = settings.blockerEnabled && !blockerSuspended
        params.height = blockerHeightPx()
        params.flags = withTouchable(params.flags, armed)
        // Transparent while a gesture goes through it - see openPathFor.
        params.alpha = if (blockerSuspended) 0f else BLOCKER_WINDOW_ALPHA
        view.visibility = if (settings.blockerEnabled) View.VISIBLE else View.GONE
        safeUpdate(view, params)
    }

    /**
     * Clears our own windows out of the way of a gesture we are about to inject.
     *
     * Two things have to happen, and missing either one loses the tap. The overlays under the
     * gesture stop being touchable, or they swallow it. And they stop being visible, because
     * Android discards a touch that passes through overlays whose combined opacity goes over
     * 0.8 - the blocker at 0.5 plus the cursor sitting right on the target came to 0.875, which
     * is why taps inside the blocked area went nowhere while taps outside it worked.
     *
     * Only the overlays actually in the path are cleared: leaving the pad alone where it is not
     * in the way keeps a finger that is still resting on it working, and keeps the blocked
     * region blocked for everything except the gesture we asked for.
     */
    private fun openPathFor(x1: Float, y1: Float, x2: Float, y2: Float) {
        injecting = true
        blockerSuspended = y1 < blockerHeightPx() || y2 < blockerHeightPx()
        applyBlockerState()

        val padInTheWay = overPad(x1, y1) || overPad(x2, y2)
        if (padInTheWay) {
            padParams?.let { it.flags = withTouchable(it.flags, false) }
        }
        // applyOpacity pushes both the alpha and the flag change above in one window update.
        applyOpacity(padTransparent = padInTheWay)
    }

    private fun restoreTouchBlocking() {
        injecting = false
        blockerSuspended = false
        applyBlockerState()

        padParams?.let { it.flags = withTouchable(it.flags, true) }
        applyOpacity()
    }

    private fun overPad(x: Float, y: Float): Boolean {
        val panel = trackpad?.root ?: return false
        val params = padParams ?: return false
        return x >= params.x && x <= params.x + panel.width &&
            y >= params.y && y <= params.y + panel.height
    }

    private fun withTouchable(flags: Int, touchable: Boolean): Int =
        if (touchable) {
            flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

    private fun blockerHeightPx(): Int =
        (screenHeight * settings.blockerFraction).toInt().coerceAtLeast(1)

    private fun refreshScreenMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            screenWidth = size.x
            screenHeight = size.y
        }
    }

    private fun safeUpdate(view: View, params: WindowManager.LayoutParams) {
        try {
            if (view.isAttachedToWindow) windowManager.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Window update skipped", e)
        }
    }

    private fun safeRemove(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Window already removed", e)
        }
    }

    // ------------------------------------------------------------- the pad

    private fun positionPadForMode() {
        val params = padParams ?: return
        val (width, height) = measuredPadSize()
        val margin = dpInt(PAD_MARGIN_DP)

        val stored = if (settings.padMode == PadMode.BUBBLE) {
            settings.bubbleHomeX to settings.bubbleHomeY
        } else {
            settings.panelX to settings.panelY
        }

        if (stored.first == Prefs.UNSET || stored.second == Prefs.UNSET) {
            params.x = screenWidth - width - margin
            params.y = (screenHeight * DEFAULT_PAD_HEIGHT_FRACTION).toInt() - height / 2
        } else {
            params.x = stored.first
            params.y = stored.second
        }
        clampPad(width, height)
    }

    private fun measuredPadSize(): Pair<Int, Int> {
        val root = trackpad?.root ?: return 0 to 0
        root.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST)
        )
        return root.measuredWidth to root.measuredHeight
    }

    private fun padSize(): Pair<Int, Int> {
        val root = trackpad?.root ?: return 0 to 0
        return if (root.width > 0 && root.height > 0) {
            root.width to root.height
        } else {
            measuredPadSize()
        }
    }

    private fun clampPad(width: Int = padSize().first, height: Int = padSize().second) {
        val root = trackpad?.root ?: return
        val params = padParams ?: return
        params.x = params.x.coerceIn(0, (screenWidth - width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeight - height).coerceAtLeast(0))
        safeUpdate(root, params)
    }

    override fun movePadBy(dx: Float, dy: Float) {
        springBack?.cancel()
        val params = padParams ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        clampPad()
    }

    override fun onPadReleased() {
        val params = padParams ?: return
        when {
            placingBubble -> {
                settings.bubbleHomeX = params.x
                settings.bubbleHomeY = params.y
                placingBubble = false
                trackpad?.syncState()
                toast(getString(R.string.hint_bubble_placed))
            }

            settings.padMode == PadMode.BUBBLE -> springPadHome()

            else -> {
                settings.panelX = params.x
                settings.panelY = params.y
            }
        }
    }

    /**
     * The bubble always returns to its home spot; the cursor stays where the stroke left it, so
     * long journeys can be made with several strokes.
     */
    private fun springPadHome() {
        val params = padParams ?: return
        val root = trackpad?.root ?: return
        val (width, height) = padSize()
        val homeX = settings.bubbleHomeX.takeIf { it != Prefs.UNSET }
            ?: (screenWidth - width - dpInt(PAD_MARGIN_DP))
        val homeY = settings.bubbleHomeY.takeIf { it != Prefs.UNSET }
            ?: ((screenHeight * DEFAULT_PAD_HEIGHT_FRACTION).toInt() - height / 2)

        val fromX = params.x
        val fromY = params.y
        if (fromX == homeX && fromY == homeY) return

        springBack?.cancel()
        springBack = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SPRING_BACK_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                params.x = (fromX + (homeX - fromX) * progress).toInt()
                params.y = (fromY + (homeY - fromY) * progress).toInt()
                safeUpdate(root, params)
            }
            start()
        }
    }

    override fun onPadResized() {
        val root = trackpad?.root ?: return
        val params = padParams ?: return
        val oldWidth = root.width
        val oldHeight = root.height
        val (width, height) = measuredPadSize()

        // Pin the corner nearest the screen edge, so a panel opened from a bubble in the bottom
        // right grows to the left and upwards instead of shoving against the edge.
        if (oldWidth > 0 && params.x + oldWidth / 2 > screenWidth / 2) {
            params.x += oldWidth - width
        }
        if (oldHeight > 0 && params.y + oldHeight / 2 > screenHeight / 2) {
            params.y += oldHeight - height
        }
        clampPad(width, height)
    }

    override fun isPlacingBubble(): Boolean = placingBubble

    private fun startPlacingBubble() {
        placingBubble = true
        trackpad?.syncState()
        toast(getString(R.string.hint_place_bubble))
    }

    // ------------------------------------------------------------- pointer

    override fun moveCursorBy(dx: Float, dy: Float) {
        // One pixel of headroom: a tap is dispatched as a 1px path and must stay on the display.
        cursorX = (cursorX + dx).coerceIn(0f, (screenWidth - 2).coerceAtLeast(0).toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, (screenHeight - 2).coerceAtLeast(0).toFloat())
        updateCursorWindow()
    }

    private fun updateCursorWindow() {
        val view = cursorView ?: return
        val params = cursorParams ?: return
        val size = cursorSizePx()
        params.x = (cursorX - CursorView.hotspotX(size)).toInt()
        params.y = (cursorY - CursorView.hotspotY(size)).toInt()
        safeUpdate(view, params)
    }

    override fun clickAtCursor() {
        val x = cursorX
        val y = cursorY
        withInjectionPassthrough(x, y, x, y) { service, done ->
            service.tap(x, y) { delivered -> onGestureFinished(delivered, done) }
        }
    }

    override fun longClickAtCursor() {
        val x = cursorX
        val y = cursorY
        withInjectionPassthrough(x, y, x, y) { service, done ->
            service.longPress(x, y) { delivered -> onGestureFinished(delivered, done) }
        }
    }

    override fun beginDrag(withHold: Boolean) {
        dragAnchor = PointF(cursorX, cursorY)
        dragHolds = withHold
        cursorView?.gestureInFlight = true
    }

    override fun endDrag() {
        val anchor = dragAnchor ?: return
        dragAnchor = null
        val x = cursorX
        val y = cursorY
        withInjectionPassthrough(anchor.x, anchor.y, x, y) { service, done ->
            service.drag(anchor.x, anchor.y, x, y, dragHolds) { delivered ->
                onGestureFinished(delivered, done)
            }
        }
    }

    override fun globalAction(action: Int) {
        val service = MouseAccessibilityService.instance
        if (service == null) {
            toast(getString(R.string.error_accessibility_off))
            return
        }
        service.globalAction(action)
    }

    override fun toggleBlocker() {
        settings.blockerEnabled = !settings.blockerEnabled
        applyBlockerState()
        updateNotification()
    }

    override fun isBlockerEnabled(): Boolean = settings.blockerEnabled

    private fun withInjectionPassthrough(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        action: (MouseAccessibilityService, () -> Unit) -> Unit
    ) {
        val service = MouseAccessibilityService.instance
        if (service == null) {
            toast(getString(R.string.error_accessibility_off))
            cursorView?.gestureInFlight = false
            return
        }

        handler.removeCallbacks(restoreBlocking)
        openPathFor(x1, y1, x2, y2)
        cursorView?.gestureInFlight = true
        // Safety net: never leave the screen unblocked if a gesture callback goes missing.
        handler.postDelayed(restoreBlocking, MAX_PASSTHROUGH_MS)

        // A window flag change only reaches the window manager on the next frames. Injecting
        // straight away would race it and the gesture would land on the overlay we just opened.
        handler.postDelayed({
            action(service) {
                handler.removeCallbacks(restoreBlocking)
                handler.postDelayed(restoreBlocking, PASSTHROUGH_TAIL_MS)
            }
        }, INJECTION_SETTLE_MS)
    }

    private fun onGestureFinished(delivered: Boolean, done: () -> Unit) {
        cursorView?.gestureInFlight = false
        if (!delivered) toast(getString(R.string.error_gesture_failed))
        done()
    }

    private fun broadcastState() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ----------------------------------------------------------- notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun goForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val blockerLabel = getString(
            if (settings.blockerEnabled) R.string.action_blocker_off else R.string.action_blocker_on
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                getString(
                    if (settings.blockerEnabled) {
                        R.string.notification_text_blocking
                    } else {
                        R.string.notification_text_idle
                    }
                )
            )
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(0, blockerLabel, servicePendingIntent(ACTION_TOGGLE_BLOCKER, 1))
            .addAction(0, getString(R.string.action_stop), servicePendingIntent(ACTION_STOP, 2))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, OverlayService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        private const val TAG = "MouseOverlay"

        const val ACTION_START = "dev.habitamu.mouse.START"
        const val ACTION_STOP = "dev.habitamu.mouse.STOP"
        const val ACTION_REFRESH = "dev.habitamu.mouse.REFRESH"
        const val ACTION_TOGGLE_BLOCKER = "dev.habitamu.mouse.TOGGLE_BLOCKER"
        const val ACTION_PREVIEW = "dev.habitamu.mouse.PREVIEW"
        const val ACTION_PLACE_BUBBLE = "dev.habitamu.mouse.PLACE_BUBBLE"
        private const val EXTRA_VISIBLE = "visible"

        /** Sent to our own package when the overlays come up or go down. */
        const val ACTION_STATE_CHANGED = "dev.habitamu.mouse.OVERLAY_STATE_CHANGED"

        @Volatile
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID = "mouse_overlay"
        private const val NOTIFICATION_ID = 42

        private const val CURSOR_BASE_DP = 56f
        private const val PAD_MARGIN_DP = 10f

        /** The blocker is invisible outside the settings screen; this is only for touch policy. */
        private const val BLOCKER_WINDOW_ALPHA = 0.5f

        private const val INITIAL_CURSOR_HEIGHT_FRACTION = 0.35f
        private const val DEFAULT_PAD_HEIGHT_FRACTION = 0.75f
        private const val MAX_PASSTHROUGH_MS = 2_500L
        /** Long enough for the cleared flags and opacities to reach the window manager. */
        private const val INJECTION_SETTLE_MS = 80L
        private const val PASSTHROUGH_TAIL_MS = 60L
        private const val ROTATION_SETTLE_MS = 300L
        private const val SPRING_BACK_MS = 180L

        fun start(context: Context) = send(context, ACTION_START)

        fun stop(context: Context) = send(context, ACTION_STOP)

        fun refresh(context: Context) = send(context, ACTION_REFRESH)

        fun placeBubble(context: Context) = send(context, ACTION_PLACE_BUBBLE)

        /** Show or hide the blocker outline, which is only ever drawn on the settings screen. */
        fun setPreview(context: Context, visible: Boolean) {
            if (!isRunning) return
            context.startService(
                Intent(context, OverlayService::class.java)
                    .setAction(ACTION_PREVIEW)
                    .putExtra(EXTRA_VISIBLE, visible)
            )
        }

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, OverlayService::class.java).setAction(action))
        }
    }
}
