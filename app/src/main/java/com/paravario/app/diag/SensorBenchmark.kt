package com.paravario.app.diag

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlin.math.sqrt

/** Raw samples recorded from one sensor during the still test. */
class ChannelResult(
    val sensor: Sensor,
    val timestampsNs: LongArray,
    val values: DoubleArray,
)

/**
 * Registers the given sensors at the fastest rate the device allows, drops a warm-up period,
 * records for [MEASURE_SECONDS] and hands back the raw samples. Events arrive on a private
 * HandlerThread, so the UI thread never blocks.
 */
class SensorBenchmark(
    private val sensorManager: SensorManager,
    sensors: List<Sensor>,
) {
    companion object {
        const val MEASURE_SECONDS = 10
        private const val WARMUP_MS = 1000L

        fun sensorsToTest(sm: SensorManager): List<Sensor> = listOf(
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GYROSCOPE,
        ).mapNotNull { sm.getDefaultSensor(it) }
    }

    private inner class Channel(val sensor: Sensor) : SensorEventListener {
        val ts = ArrayList<Long>(8192)
        val vals = ArrayList<Double>(8192)

        @Volatile
        var count = 0

        override fun onSensorChanged(e: SensorEvent) {
            if (SystemClock.elapsedRealtimeNanos() < measureStartNs) return // warm-up
            val v = when (sensor.type) {
                Sensor.TYPE_ACCELEROMETER ->
                    sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
                else -> e.values[0].toDouble()
            }
            ts.add(e.timestamp)
            vals.add(v)
            count++
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

        fun result() = ChannelResult(sensor, ts.toLongArray(), vals.toDoubleArray())
    }

    private val channels = sensors.map { Channel(it) }
    private val thread = HandlerThread("sensor-benchmark").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var measureStartNs = Long.MAX_VALUE

    fun start(onDone: (List<ChannelResult>) -> Unit) {
        measureStartNs = SystemClock.elapsedRealtimeNanos() + WARMUP_MS * 1_000_000
        for (c in channels) {
            sensorManager.registerListener(c, c.sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
        }
        handler.postDelayed({
            channels.forEach { sensorManager.unregisterListener(it) }
            val results = channels.map { it.result() }
            thread.quitSafely()
            onDone(results)
        }, WARMUP_MS + MEASURE_SECONDS * 1000L)
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        channels.forEach { sensorManager.unregisterListener(it) }
        thread.quitSafely()
    }

    /** Seconds of real measurement so far (warm-up excluded). */
    fun elapsedSeconds(): Double =
        ((SystemClock.elapsedRealtimeNanos() - measureStartNs) / 1e9).coerceIn(0.0, MEASURE_SECONDS.toDouble())

    fun pressureCount(): Int = channels.firstOrNull { it.sensor.type == Sensor.TYPE_PRESSURE }?.count ?: 0
}
