package com.paravario.app.service

import android.content.Context

enum class Mode { NONE, BARO_IMU, BARO_ONLY, GPS }

enum class Warning { NONE, CALIBRATING, WAITING_GPS, GPS_WEAK, GPS_LOST, SENSOR_STOPPED, AUDIO_ERROR }

data class Snapshot(
    val running: Boolean = false,
    val mode: Mode = Mode.NONE,
    val warning: Warning = Warning.NONE,
    val ready: Boolean = false,
    val vario: Double = 0.0,
    /** +1 gaining height, -1 losing height, 0 level or unsure. */
    val zone: Int = 0,
    val altitude: Double = Double.NaN,
    val speedKmh: Double = Double.NaN,
    val gpsFix: Boolean = false,
    val gpsRateHz: Double = 0.0,
    val gpsNoise: Double = Double.NaN,
    val gpsAccuracy: Double = Double.NaN,
)

/** Latest live data. Written by the service, polled by the UI. */
object VarioState {
    @Volatile var snap = Snapshot()
}

/** The few user choices, kept in preferences. The service reads them live. */
object VarioSettings {
    const val LOSS_ALL = 0
    const val LOSS_STRONG = 1
    const val LOSS_OFF = 2
    const val STRONG_SINK = -2.0

    @Volatile var lossMode = LOSS_ALL

    /** 0 = low (fewest false tones), 1 = normal, 2 = high (reacts to weaker changes). */
    @Volatile var sensitivity = 1

    val thresholdScale: Double
        get() = when (sensitivity) {
            0 -> 1.5
            2 -> 0.7
            else -> 1.0
        }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        lossMode = p.getInt("loss_mode", LOSS_ALL)
        sensitivity = p.getInt("sensitivity", 1)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .putInt("loss_mode", lossMode)
            .putInt("sensitivity", sensitivity)
            .apply()
    }
}
