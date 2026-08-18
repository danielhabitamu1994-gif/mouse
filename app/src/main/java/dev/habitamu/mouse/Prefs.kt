package dev.habitamu.mouse

import android.content.Context
import android.content.SharedPreferences

/** How the floating control behaves. */
enum class PadMode {
    /** Full panel with a pad area and navigation keys; collapses to a puck. */
    TRACKPAD,

    /** Nothing but the puck: it follows the finger and springs back to its home position. */
    BUBBLE;

    companion object {
        fun of(name: String?): PadMode = entries.firstOrNull { it.name == name } ?: TRACKPAD
    }
}

/**
 * Persisted user settings, shared by [MainActivity] and [OverlayService].
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var padMode: PadMode
        get() = PadMode.of(prefs.getString(KEY_PAD_MODE, PadMode.TRACKPAD.name))
        set(value) = prefs.edit().putString(KEY_PAD_MODE, value.name).apply()

    /** Whether the touch blocker window is currently armed. */
    var blockerEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCKER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCKER_ENABLED, value).apply()

    /** Fraction of the screen height, measured from the top, that the blocker covers. */
    var blockerFraction: Float
        get() = prefs.getFloat(KEY_BLOCKER_FRACTION, DEFAULT_BLOCKER_FRACTION)
        set(value) = prefs.edit()
            .putFloat(KEY_BLOCKER_FRACTION, value.coerceIn(MIN_BLOCKER_FRACTION, MAX_BLOCKER_FRACTION))
            .apply()

    /** How far the cursor travels per pixel of finger movement. */
    var sensitivity: Float
        get() = prefs.getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)
        set(value) = prefs.edit()
            .putFloat(KEY_SENSITIVITY, value.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY))
            .apply()

    /** Size of the cursor, as a multiple of its normal size. */
    var cursorScale: Float
        get() = prefs.getFloat(KEY_CURSOR_SCALE, DEFAULT_CURSOR_SCALE)
        set(value) = prefs.edit()
            .putFloat(KEY_CURSOR_SCALE, value.coerceIn(MIN_CURSOR_SCALE, MAX_CURSOR_SCALE))
            .apply()

    /** Opacity of the cursor. */
    var cursorOpacity: Float
        get() = prefs.getFloat(KEY_CURSOR_OPACITY, DEFAULT_OPACITY)
        set(value) = prefs.edit()
            .putFloat(KEY_CURSOR_OPACITY, value.coerceIn(MIN_OPACITY, MAX_OPACITY))
            .apply()

    /** Opacity of the trackpad panel or the bubble. */
    var controlOpacity: Float
        get() = prefs.getFloat(KEY_CONTROL_OPACITY, DEFAULT_OPACITY)
        set(value) = prefs.edit()
            .putFloat(KEY_CONTROL_OPACITY, value.coerceIn(MIN_OPACITY, MAX_OPACITY))
            .apply()

    /** Where the trackpad panel was left. -1 means "not placed yet". */
    var panelX: Int
        get() = prefs.getInt(KEY_PANEL_X, UNSET)
        set(value) = prefs.edit().putInt(KEY_PANEL_X, value).apply()

    var panelY: Int
        get() = prefs.getInt(KEY_PANEL_Y, UNSET)
        set(value) = prefs.edit().putInt(KEY_PANEL_Y, value).apply()

    /** The spot the bubble always springs back to. -1 means "not placed yet". */
    var bubbleHomeX: Int
        get() = prefs.getInt(KEY_BUBBLE_X, UNSET)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_X, value).apply()

    var bubbleHomeY: Int
        get() = prefs.getInt(KEY_BUBBLE_Y, UNSET)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_Y, value).apply()

    companion object {
        private const val NAME = "mouse_settings"
        private const val KEY_PAD_MODE = "pad_mode"
        private const val KEY_BLOCKER_ENABLED = "blocker_enabled"
        private const val KEY_BLOCKER_FRACTION = "blocker_fraction"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_CURSOR_OPACITY = "cursor_opacity"
        private const val KEY_CONTROL_OPACITY = "control_opacity"
        private const val KEY_CURSOR_SCALE = "cursor_scale"
        private const val KEY_PANEL_X = "panel_x"
        private const val KEY_PANEL_Y = "panel_y"
        private const val KEY_BUBBLE_X = "bubble_home_x"
        private const val KEY_BUBBLE_Y = "bubble_home_y"

        const val UNSET = -1

        const val DEFAULT_BLOCKER_FRACTION = 0.60f
        const val MIN_BLOCKER_FRACTION = 0.10f
        const val MAX_BLOCKER_FRACTION = 0.95f

        const val DEFAULT_SENSITIVITY = 1.6f
        const val MIN_SENSITIVITY = 0.5f
        const val MAX_SENSITIVITY = 4.0f

        const val DEFAULT_CURSOR_SCALE = 1.0f
        const val MIN_CURSOR_SCALE = 0.2f
        const val MAX_CURSOR_SCALE = 2.4f

        const val DEFAULT_OPACITY = 0.75f
        const val MIN_OPACITY = 0.2f

        /** What a bubble that has been switched off with a triple tap fades to. */
        const val DIMMED_OPACITY = 0.25f

        /**
         * Android discards touches that pass through an untrusted overlay above this opacity, and
         * that includes the taps this app injects, so the overlays are never allowed past it.
         */
        const val MAX_OPACITY = 0.8f
    }
}
