package dev.habitamu.mouse

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted user settings, shared by [MainActivity] and [OverlayService].
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

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

    /** How far the cursor travels per pixel of finger movement on the trackpad. */
    var sensitivity: Float
        get() = prefs.getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)
        set(value) = prefs.edit()
            .putFloat(KEY_SENSITIVITY, value.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY))
            .apply()

    /** When true, lifting a finger off the trackpad taps at the cursor. */
    var clickOnRelease: Boolean
        get() = prefs.getBoolean(KEY_CLICK_ON_RELEASE, true)
        set(value) = prefs.edit().putBoolean(KEY_CLICK_ON_RELEASE, value).apply()

    companion object {
        private const val NAME = "mouse_settings"
        private const val KEY_BLOCKER_ENABLED = "blocker_enabled"
        private const val KEY_BLOCKER_FRACTION = "blocker_fraction"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_CLICK_ON_RELEASE = "click_on_release"

        const val DEFAULT_BLOCKER_FRACTION = 0.60f
        const val MIN_BLOCKER_FRACTION = 0.10f
        const val MAX_BLOCKER_FRACTION = 0.95f

        const val DEFAULT_SENSITIVITY = 1.6f
        const val MIN_SENSITIVITY = 0.5f
        const val MAX_SENSITIVITY = 4.0f
    }
}
