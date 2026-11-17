package com.paravario.app.vario

/**
 * Fused vario filter. State = [altitude h, vertical speed v, accelerometer bias b].
 * Predict with earth-frame vertical acceleration (IMU rate), update with an altitude
 * measurement from any source: phone barometer, external barometer or GPS.
 * The bias state absorbs accelerometer offset and slow attitude errors.
 */
class Kalman3(var accelSigma: Double, var biasWalk: Double) {
    var h = 0.0; private set
    var v = 0.0; private set
    var b = 0.0; private set

    private var p00 = 100.0
    private var p01 = 0.0
    private var p02 = 0.0
    private var p11 = 1.0
    private var p12 = 0.0
    private var p22 = 0.05

    fun reset(altitude: Double, bias: Double = 0.0) {
        h = altitude; v = 0.0; b = bias
        p00 = 100.0; p01 = 0.0; p02 = 0.0; p11 = 1.0; p12 = 0.0; p22 = 0.05
    }

    /** [accel] = measured vertical acceleration (m/s², gravity removed), [dt] in seconds. */
    fun predict(accel: Double, dt: Double) {
        val a = accel - b
        h += v * dt + 0.5 * a * dt * dt
        v += a * dt

        val c = -0.5 * dt * dt
        // rows of F*P, with F = [[1, dt, c], [0, 1, -dt], [0, 0, 1]]
        val r00 = p00 + dt * p01 + c * p02
        val r01 = p01 + dt * p11 + c * p12
        val r02 = p02 + dt * p12 + c * p22
        val r11 = p11 - dt * p12
        val r12 = p12 - dt * p22
        // F*P*F'
        val n00 = r00 + dt * r01 + c * r02
        val n01 = r01 - dt * r02
        val n02 = r02
        val n11 = r11 - dt * r12
        val n12 = r12
        val q = accelSigma * accelSigma
        p00 = n00 + q * dt * dt * dt * dt / 4
        p01 = n01 + q * dt * dt * dt / 2
        p02 = n02
        p11 = n11 + q * dt * dt
        p12 = n12
        p22 += biasWalk * dt
    }

    /** Altitude measurement [z] (m) with variance [r] (m²). Returns the innovation. */
    fun update(z: Double, r: Double): Double {
        val s = p00 + r
        val k0 = p00 / s
        val k1 = p01 / s
        val k2 = p02 / s
        val y = z - h
        h += k0 * y
        v += k1 * y
        b += k2 * y
        val o00 = p00; val o01 = p01; val o02 = p02
        p00 = o00 - k0 * o00
        p01 = o01 - k0 * o01
        p02 = o02 - k0 * o02
        p11 -= k1 * o01
        p12 -= k1 * o02
        p22 -= k2 * o02
        return y
    }
}

/**
 * Baro-only fallback. State = [altitude, vertical speed]; unknown acceleration is modelled
 * as white noise with std [accelSigma]. Larger sigma = faster but noisier.
 */
class Kalman2(var accelSigma: Double) {
    var h = 0.0; private set
    var v = 0.0; private set

    private var p00 = 100.0
    private var p01 = 0.0
    private var p11 = 1.0

    fun reset(altitude: Double) {
        h = altitude; v = 0.0
        p00 = 100.0; p01 = 0.0; p11 = 1.0
    }

    fun predict(dt: Double) {
        h += v * dt
        val q = accelSigma * accelSigma
        val n00 = p00 + 2 * dt * p01 + dt * dt * p11
        val n01 = p01 + dt * p11
        p00 = n00 + q * dt * dt * dt * dt / 4
        p01 = n01 + q * dt * dt * dt / 2
        p11 += q * dt * dt
    }

    fun update(z: Double, r: Double): Double {
        val s = p00 + r
        val k0 = p00 / s
        val k1 = p01 / s
        val y = z - h
        h += k0 * y
        v += k1 * y
        val o00 = p00; val o01 = p01
        p00 = o00 - k0 * o00
        p01 = o01 - k0 * o01
        p11 -= k1 * o01
        return y
    }
}

/** Default noise settings per altitude source, found by simulation. To be tuned on real logs. */
object FilterDefaults {
    // Phone or external barometer + IMU
    const val BARO_ACCEL_SIGMA = 0.3
    const val BARO_BIAS_WALK = 1e-3
    const val BARO_VARIANCE = 0.09 // (0.3 m)²

    // Barometer without IMU
    const val BARO_ONLY_ACCEL_SIGMA = 1.0
}
