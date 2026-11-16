package com.paravario.app.diag

import android.hardware.Sensor
import java.util.Locale

/** Turns raw still-test samples into a human-readable report with a simple verdict. */
object BenchmarkReport {

    // Rough guidance for a paragliding vario, not hard limits.
    private const val RATE_GOOD_HZ = 20.0
    private const val RATE_USABLE_HZ = 8.0
    private const val NOISE_GOOD_M = 0.15
    private const val NOISE_USABLE_M = 0.40

    fun format(results: List<ChannelResult>): String = buildString {
        appendLine("=== ${SensorBenchmark.MEASURE_SECONDS} s STILL TEST (fastest rate) ===")
        for (r in results) {
            appendLine()
            when (r.sensor.type) {
                Sensor.TYPE_PRESSURE -> pressure(r)
                Sensor.TYPE_ACCELEROMETER -> accelerometer(r)
                else -> rateOnly(r)
            }
        }
    }

    private fun StringBuilder.header(r: ChannelResult, label: String): RateStats? {
        appendLine("-- $label: ${r.sensor.name}")
        val rate = Stats.rate(r.timestampsNs)
        if (rate == null) {
            appendLine("Too few samples (${r.values.size}). The OS may be throttling this sensor.")
            return null
        }
        appendLine("Samples: ${r.values.size}   actual rate: ${f(rate.rateHz, 1)} Hz")
        appendLine(
            "Interval: mean ${f(rate.meanMs, 1)} ms, min ${f(rate.minMs, 1)}, " +
                "max ${f(rate.maxMs, 1)}, jitter ${f(rate.jitterMs, 1)} ms"
        )
        return rate
    }

    private fun StringBuilder.pressure(r: ChannelResult) {
        val rate = header(r, "Barometer") ?: return
        if (r.values.size < 3) return
        val t = DoubleArray(r.timestampsNs.size) { (r.timestampsNs[it] - r.timestampsNs[0]) / 1e9 }
        val p = r.values
        val alt = DoubleArray(p.size) { Altitude.fromPressure(p[it]) }
        val altFit = Stats.lineFit(t, alt)
        val (distinct, step) = Stats.quantisation(p)

        appendLine("Pressure mean: ${f(Stats.mean(p), 3)} hPa   std: ${f(Stats.std(p), 4)} hPa")
        appendLine("Altitude noise: std ${f(Stats.std(alt), 3)} m, detrended ${f(altFit.residualStd, 3)} m")
        appendLine("Drift during test: ${f(altFit.slopePerSec, 3)} m/s (should be near 0)")
        appendLine(
            "Distinct values: $distinct   smallest step: " +
                if (step.isNaN()) "n/a" else "${f(step, 4)} hPa (${f(step * 8.3, 3)} m)"
        )
        if (distinct < p.size / 4) {
            appendLine("Note: many repeated values. The sensor may hold the last value or be on-change.")
        }

        val rateVerdict = when {
            rate.rateHz >= RATE_GOOD_HZ -> "GOOD"
            rate.rateHz >= RATE_USABLE_HZ -> "USABLE (baro-only vario will lag more)"
            else -> "POOR (too slow for a responsive vario)"
        }
        val noise = altFit.residualStd
        val noiseVerdict = when {
            noise <= NOISE_GOOD_M -> "GOOD"
            noise <= NOISE_USABLE_M -> "USABLE (needs more damping)"
            else -> "POOR (very noisy, or the phone was moved or in a draught)"
        }
        appendLine("VERDICT rate: $rateVerdict")
        appendLine("VERDICT noise: $noiseVerdict")
    }

    private fun StringBuilder.accelerometer(r: ChannelResult) {
        header(r, "Accelerometer") ?: return
        appendLine("|a| mean: ${f(Stats.mean(r.values), 3)} m/s² (should be ~9.81)   noise std: ${f(Stats.std(r.values), 4)} m/s²")
    }

    private fun StringBuilder.rateOnly(r: ChannelResult) {
        val label = when (r.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> "Game rotation vector"
            Sensor.TYPE_ROTATION_VECTOR -> "Rotation vector"
            Sensor.TYPE_GYROSCOPE -> "Gyroscope"
            else -> "Sensor type ${r.sensor.type}"
        }
        header(r, label)
    }

    private fun f(v: Double, decimals: Int): String =
        if (v.isNaN()) "n/a" else "%.${decimals}f".format(Locale.US, v)
}
