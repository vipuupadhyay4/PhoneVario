package com.paravario.app.diag

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import java.util.Locale

/** Static facts about the phone and its sensors. No measurement involved. */
object DeviceReport {

    const val DUMPSYS_CMD = "adb shell dumpsys sensorservice | grep -i -A3 press"

    private val MOTION_TYPES = listOf(
        Sensor.TYPE_ACCELEROMETER to "Accelerometer",
        Sensor.TYPE_GYROSCOPE to "Gyroscope",
        Sensor.TYPE_GAME_ROTATION_VECTOR to "Game rotation vector (no magnetometer)",
        Sensor.TYPE_ROTATION_VECTOR to "Rotation vector",
        Sensor.TYPE_GRAVITY to "Gravity",
        Sensor.TYPE_LINEAR_ACCELERATION to "Linear acceleration",
        Sensor.TYPE_MAGNETIC_FIELD to "Magnetometer",
    )

    fun build(ctx: Context, sm: SensorManager): String = buildString {
        appendLine("=== DEVICE ===")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
        val pm = ctx.packageManager
        appendLine("GPS hardware: ${yesNo(pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS))}")
        appendLine("Barometer feature flag: ${yesNo(pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_BAROMETER))}")
        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        appendLine(
            "Battery optimisation: " +
                if (power.isIgnoringBatteryOptimizations(ctx.packageName)) "app is exempt"
                else "app is NOT exempt (only matters from Phase 1)"
        )
        appendLine()

        appendLine("=== BAROMETER ===")
        val baros = sm.getSensorList(Sensor.TYPE_PRESSURE)
        if (baros.isEmpty()) {
            appendLine("NOT FOUND. Without a barometer this phone cannot be a reliable vario.")
            appendLine("(A GPS + accelerometer 'low accuracy' mode may be offered later.)")
        } else {
            baros.forEachIndexed { i, s ->
                if (i > 0) appendLine()
                describe(s)
            }
        }
        appendLine()

        appendLine("=== MOTION SENSORS ===")
        for ((type, label) in MOTION_TYPES) {
            val s = sm.getDefaultSensor(type)
            if (s == null) {
                appendLine("$label: not present")
            } else {
                appendLine("$label: ${s.name} / ${s.vendor}, ${maxRate(s)}")
            }
        }
    }

    private fun StringBuilder.describe(s: Sensor) {
        appendLine("Name: ${s.name}")
        appendLine("Vendor: ${s.vendor}   version: ${s.version}")
        appendLine("Max rate (advertised): ${maxRate(s)}")
        if (s.maxDelay > 0) appendLine("Slowest rate: %.1f Hz".format(Locale.US, 1e6 / s.maxDelay))
        appendLine("Resolution: %.4f hPa (= %.3f m)".format(Locale.US, s.resolution, s.resolution * 8.3))
        appendLine("Range: %.0f hPa   power: %.3f mA".format(Locale.US, s.maximumRange, s.power))
        appendLine("Reporting mode: ${reportingMode(s.reportingMode)}   wake-up: ${yesNo(s.isWakeUpSensor)}")
        appendLine("FIFO: reserved ${s.fifoReservedEventCount}, max ${s.fifoMaxEventCount}")
    }

    private fun maxRate(s: Sensor): String =
        if (s.minDelay > 0) "max %.0f Hz (minDelay %d µs)".format(Locale.US, 1e6 / s.minDelay, s.minDelay)
        else "no fixed rate (on-change)"

    private fun reportingMode(m: Int) = when (m) {
        Sensor.REPORTING_MODE_CONTINUOUS -> "continuous"
        Sensor.REPORTING_MODE_ON_CHANGE -> "on-change"
        Sensor.REPORTING_MODE_ONE_SHOT -> "one-shot"
        Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "special"
        else -> "unknown ($m)"
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"
}
