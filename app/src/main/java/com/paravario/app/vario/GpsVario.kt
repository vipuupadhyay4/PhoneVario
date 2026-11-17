package com.paravario.app.vario

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One altitude -> vertical speed Kalman filter (state = altitude, vertical speed) with
 * rejection of GPS altitude jumps. [accelSigma] sets how quickly it follows changes.
 */
class TrendFilter(private val accelSigma: Double) {
    var h = 0.0; private set
    var v = 0.0; private set
    private var p00 = 100.0
    private var p01 = 0.0
    private var p11 = 4.0
    private var jump = 0

    val sigma: Double get() = sqrt(max(p11, 0.0))

    fun reset(z: Double) {
        h = z; v = 0.0
        p00 = 100.0; p01 = 0.0; p11 = 4.0
        jump = 0
    }

    fun update(z: Double, dt: Double, r: Double) {
        val q = accelSigma * accelSigma
        h += v * dt
        val n00 = p00 + 2 * dt * p01 + dt * dt * p11 + q * dt * dt * dt * dt / 4
        val n01 = p01 + dt * p11 + q * dt * dt * dt / 2
        val n11 = p11 + q * dt * dt
        p00 = n00; p01 = n01; p11 = n11

        val y = z - h
        val s = p00 + r
        val gate = max(GATE_MIN, GATE_K * sqrt(s))
        if (abs(y) > gate) {
            // A sudden step is far more likely a GPS altitude jump than real motion: move the
            // altitude, keep the speed. If the next fix steps the same way again, it is real.
            val sign = if (y > 0) 1 else -1
            if (jump != sign) {
                jump = sign
                h = z
                p00 = r + 1.0
                return
            }
        } else {
            jump = 0
        }
        val k0 = p00 / s
        val k1 = p01 / s
        h += k0 * y
        v += k1 * y
        val o00 = p00
        val o01 = p01
        p00 = o00 - k0 * o00
        p01 = o01 - k0 * o01
        p11 -= k1 * o01
    }

    private companion object {
        const val GATE_MIN = 2.0
        const val GATE_K = 4.0
    }
}

/**
 * Vario for phones without a barometer: decides "gaining height / losing height / level"
 * from GPS altitude alone, so it does not depend on how the phone is held or carried.
 *
 * Two filters run on the same fixes. The fast one catches strong changes early, the slow one
 * confirms weak but steady ones. Thresholds grow with the measured GPS noise, so poor
 * reception gives silence instead of wrong tones.
 */
class GpsVario {
    /** 1.0 = normal. Larger = needs clearer evidence (fewer false tones, slower). */
    var thresholdScale = 1.0

    /** +1 gaining height, -1 losing height, 0 level or not sure. */
    var zone = 0; private set
    var vario = 0.0; private set

    /** Estimated fix-to-fix GPS altitude noise in metres. */
    var noise = NOISE_DEFAULT; private set
    var rateHz = 0.0; private set
    var fixes = 0; private set

    val ready: Boolean get() = fixes >= MIN_FIXES

    private val fast = TrendFilter(SIGMA_FAST)
    private val slow = TrendFilter(SIGMA_SLOW)
    private var started = false
    private var lastT = 0.0
    private var z1 = 0.0
    private var z2 = 0.0
    private var haveZ2 = false
    private var dt1 = 0.0
    private val d2 = DoubleArray(NOISE_WINDOW)
    private var d2Count = 0
    private var d2Pos = 0

    fun reset() {
        started = false
        fixes = 0; zone = 0; vario = 0.0; rateHz = 0.0
    }

    /** [t] in seconds (monotonic), [z] = GPS altitude in metres. */
    fun onFix(t: Double, z: Double) {
        if (!started || t - lastT > MAX_GAP) {
            started = true
            fast.reset(z); slow.reset(z)
            lastT = t; z1 = z; haveZ2 = false; dt1 = 0.0
            fixes = 1; zone = 0; vario = 0.0
            return
        }
        val dt = t - lastT
        if (dt < 0.05) return
        lastT = t
        fixes++
        rateHz = if (rateHz == 0.0) 1.0 / dt else rateHz + 0.2 * (1.0 / dt - rateHz)

        // Noise from second differences: steady motion cancels out, noise does not.
        if (haveZ2 && dt1 > 0 && abs(dt - dt1) < 0.3 * max(dt, dt1)) {
            d2[d2Pos] = abs(z - 2 * z1 + z2)
            d2Pos = (d2Pos + 1) % NOISE_WINDOW
            if (d2Count < NOISE_WINDOW) d2Count++
            if (d2Count >= 8) {
                val sorted = d2.copyOf(d2Count)
                sorted.sort()
                noise = (sorted[d2Count / 2] / 1.652).coerceIn(NOISE_MIN, NOISE_MAX)
            }
        }
        z2 = z1; z1 = z; haveZ2 = true; dt1 = dt

        val r = noise * noise
        fast.update(z, dt, r)
        slow.update(z, dt, r)
        decide()
    }

    private fun decide() {
        val vf = fast.v
        val vs = slow.v
        if (fixes < MIN_FIXES) {
            zone = 0; vario = vs
            return
        }
        val upFast = max(UP_FAST * thresholdScale, K_SIGMA * thresholdScale * fast.sigma)
        val upSlow = max(UP_SLOW * thresholdScale, K_SIGMA * thresholdScale * slow.sigma)
        val downFast = -(upFast + 0.2)
        val downSlow = -(upSlow + 0.1)
        var up = vf >= upFast || vs >= upSlow
        var down = vf <= downFast || vs <= downSlow
        if (up && down) { // the filters disagree: the fast one knows the latest direction
            up = vf > 0
            down = !up
        }
        zone = when (zone) {
            1 -> {
                val keep = vs >= upSlow * HOLD && vf > downFast
                if (down && !up) -1 else if (keep || up) 1 else 0
            }
            -1 -> {
                val keep = vs <= downSlow * HOLD && vf < upFast
                if (up && !down) 1 else if (keep || down) -1 else 0
            }
            else -> if (up) 1 else if (down) -1 else 0
        }
        vario = if (zone != 0 && (vf > 0) == (zone > 0) && abs(vf) > abs(vs)) vf else vs
    }

    companion object {
        const val SIGMA_FAST = 0.5
        const val SIGMA_SLOW = 0.12
        const val UP_FAST = 0.8
        const val UP_SLOW = 0.3
        const val K_SIGMA = 2.5
        const val HOLD = 0.5
        const val NOISE_MIN = 0.3
        const val NOISE_MAX = 3.0
        const val NOISE_DEFAULT = 0.8
        const val MIN_FIXES = 5
        const val MAX_GAP = 5.0
        private const val NOISE_WINDOW = 30
    }
}
