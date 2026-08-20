package dev.habitamu.mouse

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.habitamu.mouse.databinding.ActivityMainBinding
import kotlin.math.roundToInt

/**
 * Setup and settings. Every control here is deliberately in the lower half of the screen, since
 * the whole point of the app is that the top of the screen may not respond to touch.
 *
 * While this screen is in front, the blocker draws its outline so the blocked area can be seen
 * and adjusted. It stops as soon as the screen goes away.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshUi()
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.btnOverlayPermission.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnToggleService.setOnClickListener {
            if (OverlayService.isRunning) stopOverlays() else startOverlays()
        }

        binding.radioMode.setOnCheckedChangeListener { _, checkedId ->
            prefs.padMode =
                if (checkedId == R.id.radio_bubble) PadMode.BUBBLE else PadMode.TRACKPAD
            pushSettings()
            refreshUi()
        }

        binding.btnPlaceBubble.setOnClickListener { OverlayService.placeBubble(this) }

        binding.switchTapAfterSlide.setOnCheckedChangeListener { _, checked ->
            prefs.tapAfterSlide = checked
            pushSettings()
        }

        binding.switchBlocker.setOnCheckedChangeListener { _, checked ->
            prefs.blockerEnabled = checked
            pushSettings()
        }

        binding.seekCoverage.max = COVERAGE_MAX_PERCENT - COVERAGE_MIN_PERCENT
        binding.seekCoverage.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val percent = progress + COVERAGE_MIN_PERCENT
                binding.valueCoverage.text = getString(R.string.value_percent, percent)
                if (fromUser) {
                    prefs.blockerFraction = percent / 100f
                    pushSettings()
                }
            }
        })

        binding.seekSensitivity.max = SLIDER_STEPS
        binding.seekSensitivity.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val sensitivity = scale(progress, Prefs.MIN_SENSITIVITY, Prefs.MAX_SENSITIVITY)
                binding.valueSensitivity.text = getString(R.string.value_multiplier, sensitivity)
                if (fromUser) {
                    prefs.sensitivity = sensitivity
                    pushSettings()
                }
            }
        })

        binding.seekCursorSize.max = SLIDER_STEPS
        binding.seekCursorSize.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val scale = scale(progress, Prefs.MIN_CURSOR_SCALE, Prefs.MAX_CURSOR_SCALE)
                binding.valueCursorSize.text = percentage(scale)
                if (fromUser) {
                    prefs.cursorScale = scale
                    pushSettings()
                }
            }
        })

        binding.seekCursorHide.max = Prefs.MAX_CURSOR_HIDE_SECONDS
        binding.seekCursorHide.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.valueCursorHide.text = hideDelay(progress)
                if (fromUser) {
                    prefs.cursorHideSeconds = progress
                    pushSettings()
                }
            }
        })

        binding.seekCursorOpacity.max = SLIDER_STEPS
        binding.seekCursorOpacity.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val opacity = scale(progress, Prefs.MIN_OPACITY, Prefs.MAX_OPACITY)
                binding.valueCursorOpacity.text = percentage(opacity)
                if (fromUser) {
                    prefs.cursorOpacity = opacity
                    pushSettings()
                }
            }
        })

        binding.seekControlOpacity.max = SLIDER_STEPS
        binding.seekControlOpacity.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val opacity = scale(progress, Prefs.MIN_OPACITY, Prefs.MAX_OPACITY)
                binding.valueControlOpacity.text = percentage(opacity)
                if (fromUser) {
                    prefs.controlOpacity = opacity
                    pushSettings()
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(OverlayService.ACTION_STATE_CHANGED)
            addAction(MouseAccessibilityService.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        loadSettingsIntoUi()
        refreshUi()
        OverlayService.setPreview(this, true)
    }

    override fun onPause() {
        OverlayService.setPreview(this, false)
        super.onPause()
    }

    private fun startOverlays() {
        if (!Settings.canDrawOverlays(this)) {
            binding.statusOverlay.setText(R.string.status_missing)
            return
        }
        if (!MouseAccessibilityService.isEnabledInSettings(this)) {
            binding.statusAccessibility.setText(R.string.status_missing)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // The overlays live in a foreground service, which needs a visible notification.
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        OverlayService.start(this)
        binding.btnToggleService.postDelayed({
            refreshUi()
            OverlayService.setPreview(this, true)
        }, STATE_SETTLE_MS)
    }

    private fun stopOverlays() {
        OverlayService.stop(this)
        binding.btnToggleService.postDelayed(::refreshUi, STATE_SETTLE_MS)
    }

    private fun pushSettings() {
        if (OverlayService.isRunning) OverlayService.refresh(this)
    }

    private fun loadSettingsIntoUi() {
        binding.switchBlocker.isChecked = prefs.blockerEnabled
        binding.switchTapAfterSlide.isChecked = prefs.tapAfterSlide
        binding.radioMode.check(
            if (prefs.padMode == PadMode.BUBBLE) R.id.radio_bubble else R.id.radio_trackpad
        )

        val coveragePercent = (prefs.blockerFraction * 100).roundToInt()
        binding.seekCoverage.progress = coveragePercent - COVERAGE_MIN_PERCENT
        binding.seekSensitivity.progress =
            progressOf(prefs.sensitivity, Prefs.MIN_SENSITIVITY, Prefs.MAX_SENSITIVITY)
        binding.seekCursorSize.progress =
            progressOf(prefs.cursorScale, Prefs.MIN_CURSOR_SCALE, Prefs.MAX_CURSOR_SCALE)
        binding.seekCursorHide.progress = prefs.cursorHideSeconds
        binding.seekCursorOpacity.progress =
            progressOf(prefs.cursorOpacity, Prefs.MIN_OPACITY, Prefs.MAX_OPACITY)
        binding.seekControlOpacity.progress =
            progressOf(prefs.controlOpacity, Prefs.MIN_OPACITY, Prefs.MAX_OPACITY)

        // Setting a progress that is already current fires no callback, so label the values here.
        binding.valueCoverage.text = getString(R.string.value_percent, coveragePercent)
        binding.valueSensitivity.text = getString(R.string.value_multiplier, prefs.sensitivity)
        binding.valueCursorSize.text = percentage(prefs.cursorScale)
        binding.valueCursorHide.text = hideDelay(prefs.cursorHideSeconds)
        binding.valueCursorOpacity.text = percentage(prefs.cursorOpacity)
        binding.valueControlOpacity.text = percentage(prefs.controlOpacity)
    }

    private fun refreshUi() {
        val canOverlay = Settings.canDrawOverlays(this)
        val accessibilityOn = MouseAccessibilityService.isEnabledInSettings(this)

        binding.statusOverlay.setText(if (canOverlay) R.string.status_granted else R.string.status_missing)
        binding.btnOverlayPermission.isEnabled = !canOverlay
        binding.statusAccessibility.setText(
            if (accessibilityOn) R.string.status_granted else R.string.status_missing
        )

        val service = MouseAccessibilityService.instance
        binding.statusShortcut.setText(
            when {
                service?.canFilterKeys() != true -> R.string.status_unavailable
                service.hasSeenVolumeKey() -> R.string.status_working
                else -> R.string.status_untested
            }
        )

        val running = OverlayService.isRunning
        binding.btnToggleService.setText(if (running) R.string.action_stop else R.string.action_start)
        binding.btnToggleService.isEnabled = running || (canOverlay && accessibilityOn)
        binding.statusService.setText(
            if (running) R.string.status_service_running else R.string.status_service_stopped
        )
        binding.btnPlaceBubble.isEnabled = running && prefs.padMode == PadMode.BUBBLE
    }

    private fun hideDelay(seconds: Int): String =
        if (seconds == 0) getString(R.string.value_never) else getString(R.string.value_seconds, seconds)

    private fun percentage(fraction: Float): String =
        getString(R.string.value_percentage, (fraction * 100).roundToInt())

    private fun scale(progress: Int, min: Float, max: Float): Float =
        min + (max - min) * progress / SLIDER_STEPS

    private fun progressOf(value: Float, min: Float, max: Float): Int =
        ((value - min) / (max - min) * SLIDER_STEPS).roundToInt()

    /** Saves implementing the two callbacks nobody needs on every slider. */
    private abstract class SimpleSeekBarListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    }

    private companion object {
        const val COVERAGE_MIN_PERCENT = 10
        const val COVERAGE_MAX_PERCENT = 95
        const val SLIDER_STEPS = 100
        const val STATE_SETTLE_MS = 350L
    }
}
