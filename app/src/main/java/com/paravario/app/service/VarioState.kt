package com.paravario.app.service

enum class Mode { NONE, BARO_IMU, BARO_ONLY, GPS_IMU }

enum class Warning { NONE, CALIBRATING, WAITING_GPS, GPS_LOST, SENSOR_STOPPED, AUDIO_ERROR }

data class Snapshot(
    val running: Boolean = false,
    val mode: Mode = Mode.NONE,
    val warning: Warning = Warning.NONE,
    val ready: Boolean = false,
    val vario: Double = 0.0,
    val altitude: Double = Double.NaN,
    val speedKmh: Double = Double.NaN,
    val gpsFix: Boolean = false,
)

/** Latest live data. Written by the service, polled by the UI. */
object VarioState {
    @Volatile var snap = Snapshot()
}
