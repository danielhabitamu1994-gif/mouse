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

        binding.switchBlocker.setOnCheckedChangeListener { _, checked ->
            prefs.blockerEnabled = checked
            pushSettings()
        }

        binding.switchClickOnRelease.setOnCheckedChangeListener { _, checked ->
            prefs.clickOnRelease = checked
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

        binding.seekSensitivity.max = SENSITIVITY_STEPS
        binding.seekSensitivity.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val sensitivity = sensitivityOf(progress)
                binding.valueSensitivity.text = getString(R.string.value_multiplier, sensitivity)
                if (fromUser) {
                    prefs.sensitivity = sensitivity
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
        binding.btnToggleService.postDelayed(::refreshUi, STATE_SETTLE_MS)
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
        binding.switchClickOnRelease.isChecked = prefs.clickOnRelease
        val coveragePercent = (prefs.blockerFraction * 100).roundToInt()
        binding.seekCoverage.progress = coveragePercent - COVERAGE_MIN_PERCENT
        binding.seekSensitivity.progress = progressOf(prefs.sensitivity)
        // Setting a progress that is already current fires no callback, so label the values here.
        binding.valueCoverage.text = getString(R.string.value_percent, coveragePercent)
        binding.valueSensitivity.text = getString(R.string.value_multiplier, prefs.sensitivity)
    }

    private fun refreshUi() {
        val canOverlay = Settings.canDrawOverlays(this)
        val accessibilityOn = MouseAccessibilityService.isEnabledInSettings(this)

        binding.statusOverlay.setText(if (canOverlay) R.string.status_granted else R.string.status_missing)
        binding.btnOverlayPermission.isEnabled = !canOverlay
        binding.statusAccessibility.setText(
            if (accessibilityOn) R.string.status_granted else R.string.status_missing
        )

        val running = OverlayService.isRunning
        binding.btnToggleService.setText(if (running) R.string.action_stop else R.string.action_start)
        binding.btnToggleService.isEnabled = running || (canOverlay && accessibilityOn)
        binding.statusService.setText(
            if (running) R.string.status_service_running else R.string.status_service_stopped
        )
    }

    private fun sensitivityOf(progress: Int): Float =
        Prefs.MIN_SENSITIVITY +
            (Prefs.MAX_SENSITIVITY - Prefs.MIN_SENSITIVITY) * progress / SENSITIVITY_STEPS

    private fun progressOf(sensitivity: Float): Int =
        ((sensitivity - Prefs.MIN_SENSITIVITY) /
            (Prefs.MAX_SENSITIVITY - Prefs.MIN_SENSITIVITY) * SENSITIVITY_STEPS).roundToInt()

    /** Saves implementing the two callbacks nobody needs on every slider. */
    private abstract class SimpleSeekBarListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    }

    private companion object {
        const val COVERAGE_MIN_PERCENT = 10
        const val COVERAGE_MAX_PERCENT = 95
        const val SENSITIVITY_STEPS = 100
        const val STATE_SETTLE_MS = 350L
    }
}
