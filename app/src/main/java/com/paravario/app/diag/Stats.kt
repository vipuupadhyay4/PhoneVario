package com.paravario.app.diag

import kotlin.math.pow
import kotlin.math.sqrt

object Altitude {
    const val ISA_P0_HPA = 1013.25

    /** Barometric altitude in metres: h = 44330 * (1 - (p/p0)^(1/5.255)). */
    fun fromPressure(pHpa: Double, p0Hpa: Double = ISA_P0_HPA): Double =
        44330.0 * (1.0 - (pHpa / p0Hpa).pow(1.0 / 5.255))
}

data class RateStats(
    val rateHz: Double,
    val meanMs: Double,
    val minMs: Double,
    val maxMs: Double,
    val jitterMs: Double,
)

data class LineFit(val slopePerSec: Double, val residualStd: Double)

object Stats {

    fun mean(x: DoubleArray): Double {
        require(x.isNotEmpty())
        return x.sum() / x.size
    }

    /** Sample standard deviation (n-1). NaN when fewer than 2 values. */
    fun std(x: DoubleArray): Double {
        if (x.size < 2) return Double.NaN
        val m = mean(x)
        var s = 0.0
        for (v in x) {
            val d = v - m
            s += d * d
        }
        return sqrt(s / (x.size - 1))
    }

    /**
     * Least-squares line x = a + b*t. Returns slope and the std of the residuals.
     * Detrending removes slow weather/temperature drift so only sensor noise remains.
     */
    fun lineFit(t: DoubleArray, x: DoubleArray): LineFit {
        require(t.size == x.size)
        val n = x.size
        if (n < 3) return LineFit(Double.NaN, Double.NaN)
        val mt = mean(t)
        val mx = mean(x)
        var stt = 0.0
        var stx = 0.0
        for (i in 0 until n) {
            val dt = t[i] - mt
            stt += dt * dt
            stx += dt * (x[i] - mx)
        }
        val slope = if (stt > 0) stx / stt else 0.0
        var s = 0.0
        for (i in 0 until n) {
            val r = x[i] - (mx + slope * (t[i] - mt))
            s += r * r
        }
        return LineFit(slope, sqrt(s / (n - 2)))
    }

    /** Sample rate from event timestamps in nanoseconds. Null when fewer than 2 samples. */
    fun rate(timestampsNs: LongArray): RateStats? {
        val n = timestampsNs.size
        if (n < 2) return null
        val span = (timestampsNs[n - 1] - timestampsNs[0]) / 1e9
        if (span <= 0) return null
        val dts = DoubleArray(n - 1) { (timestampsNs[it + 1] - timestampsNs[it]) / 1e6 }
        return RateStats(
            rateHz = (n - 1) / span,
            meanMs = mean(dts),
            minMs = dts.min(),
            maxMs = dts.max(),
            jitterMs = if (dts.size >= 2) std(dts) else 0.0,
        )
    }

    /** Number of distinct values and the smallest non-zero step between them (sensor quantisation). */
    fun quantisation(x: DoubleArray): Pair<Int, Double> {
        val sorted = x.distinct().sorted()
        var step = Double.NaN
        for (i in 1 until sorted.size) {
            val d = sorted[i] - sorted[i - 1]
            if (d > 0 && (step.isNaN() || d < step)) step = d
        }
        return sorted.size to step
    }
}
