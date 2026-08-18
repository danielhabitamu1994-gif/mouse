package dev.habitamu.mouse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Owns the three overlay windows and the virtual cursor.
 *
 * Window order matters and is the order they are added: the blocker sits lowest, the cursor
 * above it so it stays visible, and the trackpad on top so it keeps working even if the user
 * drags it into the blocked region.
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
    private var trackpadParams: WindowManager.LayoutParams? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var cursorX = 0f
    private var cursorY = 0f
    private var dragAnchor: PointF? = null

    /** True while a synthetic gesture is passing through the blocked region. */
    private var blockerSuspended = false
    private var overlaysAdded = false

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

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_BLOCKER -> {
                toggleBlocker()
                trackpad?.syncState()
            }

            ACTION_REFRESH -> applySettings()

            else -> if (!addOverlays()) return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The new display size is not always published by the time this callback runs.
        handler.postDelayed({
            refreshScreenMetrics()
            applySettings()
            moveCursorBy(0f, 0f)
            clampTrackpad()
        }, ROTATION_SETTLE_MS)
    }

    override fun onDestroy() {
        isRunning = false
        broadcastState()
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
        if (overlaysAdded) {
            applySettings()
            return true
        }

        refreshScreenMetrics()
        cursorX = screenWidth / 2f
        cursorY = screenHeight * INITIAL_CURSOR_HEIGHT_FRACTION

        addBlocker()
        addCursor()
        addTrackpad()

        overlaysAdded = true
        applySettings()
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
        val size = dpInt(CURSOR_WINDOW_DP)
        val view = CursorView(this)
        val params = overlayParams(width = size, height = size, touchable = false).apply {
            alpha = CURSOR_WINDOW_ALPHA
        }
        cursorView = view
        cursorParams = params
        windowManager.addView(view, params)
        updateCursorWindow()
    }

    private fun addTrackpad() {
        val panel = TrackpadPanel(this, this)
        val params = overlayParams(
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true
        )
        trackpad = panel
        trackpadParams = params
        windowManager.addView(panel.root, params)

        // Park it in the bottom right corner once it has been measured.
        panel.root.post {
            val margin = dpInt(TRACKPAD_MARGIN_DP)
            params.x = screenWidth - panel.root.width - margin
            params.y = screenHeight - panel.root.height - margin - dpInt(NAV_BAR_ALLOWANCE_DP)
            clampTrackpad()
        }
    }

    private fun removeOverlays() {
        trackpad?.release()
        trackpad?.root?.let { safeRemove(it) }
        cursorView?.let { safeRemove(it) }
        blockerView?.let { safeRemove(it) }
        trackpad = null
        cursorView = null
        blockerView = null
        trackpadParams = null
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
        trackpad?.syncState()
    }

    private fun applyBlockerState() {
        val view = blockerView ?: return
        val params = blockerParams ?: return

        val armed = settings.blockerEnabled && !blockerSuspended
        params.height = blockerHeightPx()
        params.flags = withTouchable(params.flags, armed)
        view.visibility = if (settings.blockerEnabled) View.VISIBLE else View.GONE
        safeUpdate(view, params)
    }

    /**
     * The accessibility service injects gestures into the normal input pipeline, so they land on
     * whichever window is on top at that point - which would be our own overlays. Any overlay the
     * gesture passes through is made untouchable for its duration and restored right after.
     *
     * Only the overlays actually under the gesture are opened up: leaving the trackpad touchable
     * where it is not in the way keeps the finger that is still resting on it working, and keeps
     * the blocked region blocked for everything except the tap we asked for.
     */
    private fun openPathFor(x1: Float, y1: Float, x2: Float, y2: Float) {
        blockerSuspended = y1 < blockerHeightPx() || y2 < blockerHeightPx()
        applyBlockerState()

        val panel = trackpad?.root
        val params = trackpadParams
        if (panel != null && params != null && (overTrackpad(x1, y1) || overTrackpad(x2, y2))) {
            params.flags = withTouchable(params.flags, false)
            safeUpdate(panel, params)
        }
    }

    private fun restoreTouchBlocking() {
        blockerSuspended = false
        applyBlockerState()

        val panel = trackpad?.root
        val params = trackpadParams
        if (panel != null && params != null) {
            params.flags = withTouchable(params.flags, true)
            safeUpdate(panel, params)
        }
    }

    private fun overTrackpad(x: Float, y: Float): Boolean {
        val panel = trackpad?.root ?: return false
        val params = trackpadParams ?: return false
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

    // ------------------------------------------------------------- controller

    override fun moveCursorBy(dx: Float, dy: Float) {
        // One pixel of headroom: a tap is dispatched as a 1px path and must stay on the display.
        cursorX = (cursorX + dx).coerceIn(0f, (screenWidth - 2).coerceAtLeast(0).toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, (screenHeight - 2).coerceAtLeast(0).toFloat())
        updateCursorWindow()
    }

    private fun updateCursorWindow() {
        val view = cursorView ?: return
        val params = cursorParams ?: return
        val half = dpInt(CURSOR_WINDOW_DP) / 2
        params.x = (cursorX - half).toInt()
        params.y = (cursorY - half).toInt()
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

    override fun armDrag() {
        dragAnchor = PointF(cursorX, cursorY)
        toast(getString(R.string.hint_drag_armed))
    }

    override fun cancelDrag() {
        dragAnchor = null
    }

    override fun isDragArmed(): Boolean = dragAnchor != null

    override fun finishDragAtCursor() {
        val anchor = dragAnchor ?: return
        dragAnchor = null
        val x = cursorX
        val y = cursorY
        withInjectionPassthrough(anchor.x, anchor.y, x, y) { service, done ->
            service.drag(anchor.x, anchor.y, x, y) { delivered -> onGestureFinished(delivered, done) }
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

    override fun moveTrackpadBy(dx: Float, dy: Float) {
        val params = trackpadParams ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        clampTrackpad()
    }

    override fun onTrackpadResized() = clampTrackpad()

    override fun toggleBlocker() {
        settings.blockerEnabled = !settings.blockerEnabled
        applyBlockerState()
        updateNotification()
    }

    override fun isBlockerEnabled(): Boolean = settings.blockerEnabled

    private fun clampTrackpad() {
        val view = trackpad?.root ?: return
        val params = trackpadParams ?: return
        params.x = params.x.coerceIn(0, (screenWidth - view.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeight - view.height).coerceAtLeast(0))
        safeUpdate(view, params)
    }

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
            return
        }

        handler.removeCallbacks(restoreBlocking)
        openPathFor(x1, y1, x2, y2)
        cursorView?.gestureInFlight = true
        // Safety net: never leave the screen unblocked if a gesture callback goes missing.
        handler.postDelayed(restoreBlocking, MAX_PASSTHROUGH_MS)

        action(service) {
            handler.removeCallbacks(restoreBlocking)
            handler.postDelayed(restoreBlocking, PASSTHROUGH_TAIL_MS)
        }
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

        /** Sent to our own package when the overlays come up or go down. */
        const val ACTION_STATE_CHANGED = "dev.habitamu.mouse.OVERLAY_STATE_CHANGED"

        @Volatile
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID = "mouse_overlay"
        private const val NOTIFICATION_ID = 42

        private const val CURSOR_WINDOW_DP = 56f
        private const val TRACKPAD_MARGIN_DP = 12f
        private const val NAV_BAR_ALLOWANCE_DP = 28f

        /** Both windows stay under the 0.8 opacity the platform treats as obscuring. */
        private const val BLOCKER_WINDOW_ALPHA = 0.5f
        private const val CURSOR_WINDOW_ALPHA = 0.75f

        private const val INITIAL_CURSOR_HEIGHT_FRACTION = 0.35f
        private const val MAX_PASSTHROUGH_MS = 2_000L
        private const val PASSTHROUGH_TAIL_MS = 60L
        private const val ROTATION_SETTLE_MS = 300L

        fun start(context: android.content.Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_START))
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP))
        }

        fun refresh(context: android.content.Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_REFRESH))
        }
    }
}
