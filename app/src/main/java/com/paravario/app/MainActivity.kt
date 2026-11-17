package com.paravario.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.paravario.app.service.Mode
import com.paravario.app.service.VarioService
import com.paravario.app.service.VarioSettings
import com.paravario.app.service.VarioState
import com.paravario.app.service.Warning
import com.paravario.app.ui.VarioBarView
import java.util.Locale

/** Flight screen: big vertical speed, altitude, ground speed, start/stop. */
class MainActivity : Activity() {

    private lateinit var bannerView: TextView
    private lateinit var statusLabel: TextView
    private lateinit var gpsInfoView: TextView
    private lateinit var lossButton: Button
    private lateinit var sensButton: Button
    private lateinit var varioView: TextView
    private lateinit var barView: VarioBarView
    private lateinit var altView: TextView
    private lateinit var speedView: TextView
    private lateinit var modeView: TextView
    private lateinit var startButton: Button

    private val ui = Handler(Looper.getMainLooper())
    private var deviceMode = Mode.NONE
    private var defaultTextColor = Color.GRAY

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceMode = VarioService.detectMode(getSystemService(SENSOR_SERVICE) as SensorManager)
        VarioSettings.load(this)
        setContentView(buildLayout())
        defaultTextColor = altView.currentTextColor
        showDisclaimerOnce()
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    // ---- Start / stop ----

    private fun toggle() {
        if (VarioState.snap.running) {
            VarioService.stop(this)
            return
        }
        if (deviceMode == Mode.NONE) return
        val needed = ArrayList<String>()
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) startVario() else requestPermissions(needed.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) startVario()
    }

    private fun startVario() {
        if (deviceMode == Mode.GPS && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            // Without a barometer, precise GPS is the only altitude source.
            AlertDialog.Builder(this)
                .setMessage(R.string.need_location)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        try {
            VarioService.start(this)
        } catch (e: Exception) {
            AlertDialog.Builder(this).setMessage(e.toString()).setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // ---- Rendering ----

    private fun render() {
        val s = VarioState.snap
        startButton.setText(if (s.running) R.string.btn_stop else R.string.btn_start_vario)
        startButton.isEnabled = deviceMode != Mode.NONE

        val showVario = s.running && s.ready
        val zoneColor = when {
            !showVario || s.zone == 0 -> defaultTextColor
            s.zone > 0 -> VarioBarView.CLIMB
            else -> VarioBarView.SINK
        }
        statusLabel.setText(
            when {
                !showVario -> R.string.status_wait
                s.zone > 0 -> R.string.status_climb
                s.zone < 0 -> R.string.status_sink
                else -> R.string.status_level
            }
        )
        statusLabel.setTextColor(zoneColor)
        varioView.text = if (showVario) "%+.1f".format(Locale.US, s.vario) else "--.-"
        varioView.setTextColor(zoneColor)
        barView.vario = if (showVario) s.vario.toFloat() else 0f
        altView.text = if (s.running && !s.altitude.isNaN()) "%.0f m".format(Locale.US, s.altitude) else "-- m"
        speedView.text = if (s.running && !s.speedKmh.isNaN()) "%.0f km/h".format(Locale.US, s.speedKmh) else "-- km/h"

        modeView.setText(
            when (deviceMode) {
                Mode.BARO_IMU -> R.string.mode_baro_imu
                Mode.BARO_ONLY -> R.string.mode_baro_only
                Mode.GPS -> R.string.mode_gps
                Mode.NONE -> R.string.mode_none
            }
        )
        gpsInfoView.text = if (s.running && s.mode == Mode.GPS && s.gpsFix) {
            getString(
                R.string.gps_info,
                s.gpsRateHz,
                if (s.gpsNoise.isNaN()) "–" else "%.1f".format(Locale.US, s.gpsNoise),
                if (s.gpsAccuracy.isNaN()) "–" else "%.0f".format(Locale.US, s.gpsAccuracy),
            )
        } else ""
        lossButton.setText(
            when (VarioSettings.lossMode) {
                VarioSettings.LOSS_STRONG -> R.string.loss_strong
                VarioSettings.LOSS_OFF -> R.string.loss_off
                else -> R.string.loss_all
            }
        )
        sensButton.setText(
            when (VarioSettings.sensitivity) {
                0 -> R.string.sens_low
                2 -> R.string.sens_high
                else -> R.string.sens_normal
            }
        )

        val warn = when {
            deviceMode == Mode.NONE -> R.string.mode_none
            !s.running -> if (needsCalibration()) R.string.warn_not_calibrated else 0
            s.warning == Warning.SENSOR_STOPPED -> R.string.warn_sensor
            s.warning == Warning.GPS_LOST -> R.string.warn_gps_lost
            s.warning == Warning.AUDIO_ERROR -> R.string.warn_audio
            s.warning == Warning.CALIBRATING -> R.string.warn_calibrating
            s.warning == Warning.WAITING_GPS -> R.string.warn_waiting_gps
            s.warning == Warning.GPS_WEAK -> R.string.warn_gps_weak
            else -> 0
        }
        if (warn == 0) {
            bannerView.visibility = View.INVISIBLE
        } else {
            bannerView.setText(warn)
            val soft = !s.running || s.warning == Warning.CALIBRATING || s.warning == Warning.WAITING_GPS ||
                s.warning == Warning.GPS_WEAK
            bannerView.setBackgroundColor(if (soft && deviceMode != Mode.NONE) AMBER else VarioBarView.SINK)
            bannerView.setTextColor(if (soft && deviceMode != Mode.NONE) Color.BLACK else Color.WHITE)
            bannerView.visibility = View.VISIBLE
        }
    }

    private fun needsCalibration() = deviceMode == Mode.BARO_IMU &&
        getSharedPreferences("app", MODE_PRIVATE).getString(CalibrationActivity.PREF_KEY, null) == null

    private fun showDisclaimerOnce() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("disclaimer_ok", false)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer_text)
            .setCancelable(false)
            .setPositiveButton(R.string.disclaimer_ok) { _, _ ->
                prefs.edit().putBoolean("disclaimer_ok", true).apply()
            }
            .show()
    }

    // ---- Layout ----

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun bigButton(textRes: Int, onClick: () -> Unit) = Button(this).apply {
        setText(textRes)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        minHeight = dp(64) // glove-friendly
        setOnClickListener { onClick() }
    }

    private fun valueView(sizeSp: Float) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        gravity = Gravity.CENTER
    }

    private fun buildLayout(): View {
        val pad = dp(16)
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        bannerView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.INVISIBLE
        }
        root.addView(bannerView, LinearLayout.LayoutParams(match, wrap))

        statusLabel = valueView(26f).apply { setTypeface(Typeface.DEFAULT, Typeface.BOLD) }
        root.addView(statusLabel, LinearLayout.LayoutParams(match, wrap))

        varioView = valueView(96f).apply { text = "--.-" }
        root.addView(varioView, LinearLayout.LayoutParams(match, 0, 1f))

        root.addView(TextView(this).apply {
            text = "m/s"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
        })

        barView = VarioBarView(this)
        root.addView(barView, LinearLayout.LayoutParams(match, dp(28)).apply { topMargin = dp(8); bottomMargin = dp(16) })

        altView = valueView(40f)
        speedView = valueView(40f)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(altView, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(speedView, LinearLayout.LayoutParams(0, wrap, 1f))
        root.addView(row)

        gpsInfoView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(gpsInfoView)

        modeView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(8))
        }
        root.addView(modeView)

        startButton = bigButton(R.string.btn_start_vario) { toggle() }
        root.addView(startButton, LinearLayout.LayoutParams(match, dp(72)))

        lossButton = bigButton(R.string.loss_all) {
            VarioSettings.lossMode = (VarioSettings.lossMode + 1) % 3
            VarioSettings.save(this)
        }
        sensButton = bigButton(R.string.sens_normal) {
            VarioSettings.sensitivity = (VarioSettings.sensitivity + 1) % 3
            VarioSettings.save(this)
        }
        for (b in listOf(lossButton, sensButton)) b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        if (deviceMode == Mode.GPS) {
            val settingsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            settingsRow.addView(lossButton, LinearLayout.LayoutParams(0, wrap, 1f))
            settingsRow.addView(sensButton, LinearLayout.LayoutParams(0, wrap, 1f))
            root.addView(settingsRow)
        }

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(
            bigButton(R.string.btn_test_tones) { startActivity(Intent(this, ToneTestActivity::class.java)) },
            LinearLayout.LayoutParams(0, wrap, 1f)
        )
        row2.addView(
            bigButton(R.string.btn_diagnostics) { startActivity(Intent(this, DiagnosticsActivity::class.java)) },
            LinearLayout.LayoutParams(0, wrap, 1f)
        )
        root.addView(row2)
        if (deviceMode == Mode.BARO_IMU) {
            // The motion sensor is only used together with a barometer.
            root.addView(
                bigButton(R.string.btn_calibrate) { startActivity(Intent(this, CalibrationActivity::class.java)) },
                LinearLayout.LayoutParams(match, wrap)
            )
        }

        // targetSdk 35+ forces edge-to-edge on Android 15+, so pad for the system bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(pad + i.left, pad + i.top, pad + i.right, pad + i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    pad + insets.systemWindowInsetLeft, pad + insets.systemWindowInsetTop,
                    pad + insets.systemWindowInsetRight, pad + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        return root
    }

    private companion object {
        const val REQ_PERMISSIONS = 1
        val AMBER = 0xFFFFC107.toInt()
    }
}
