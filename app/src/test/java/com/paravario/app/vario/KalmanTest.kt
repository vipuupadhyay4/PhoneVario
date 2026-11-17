package com.paravario.app.vario

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Replays synthetic flights through the filters and checks lag, overshoot and noise.
 * Profile: still air, then at t=20 s the climb goes 0 -> +2 m/s in 0.5 s,
 * then at t=50 s it drops to -1 m/s.
 */
class KalmanTest {

    private class Sample(val t: Double, val est: Double, val truth: Double)

    private fun accelAt(t: Double) = when {
        t < 20.0 -> 0.0
        t < 20.5 -> 4.0
        t < 50.0 -> 0.0
        t < 50.75 -> -4.0
        else -> 0.0
    }

    /** Fused filter: IMU at 100 Hz, altitude source every [measEvery] IMU samples. */
    private fun runFused(
        accelSigma: Double, biasWalk: Double, r: Double, measEvery: Int,
        altNoise: Double, accelNoise: Double, seed: Long,
    ): List<Sample> {
        val rnd = Random(seed)
        val kf = Kalman3(accelSigma, biasWalk)
        val dt = 0.01
        var h = 500.0
        var v = 0.0
        kf.reset(h)
        val out = ArrayList<Sample>()
        for (i in 0 until 6000) {
            val t = i * dt
            val a = accelAt(t)
            h += v * dt + 0.5 * a * dt * dt
            v += a * dt
            kf.predict(a + 0.15 + rnd.nextGaussian() * accelNoise, dt) // 0.15 m/s² sensor bias
            if (i % measEvery == 0) kf.update(h + rnd.nextGaussian() * altNoise, r)
            out.add(Sample(t, kf.v, v))
        }
        return out
    }

    private fun runBaroOnly(altNoise: Double, seed: Long): List<Sample> {
        val rnd = Random(seed)
        val kf = Kalman2(FilterDefaults.BARO_ONLY_ACCEL_SIGMA)
        var h = 500.0
        var v = 0.0
        kf.reset(h)
        val out = ArrayList<Sample>()
        for (i in 0 until 1500) { // 25 Hz barometer
            val t = i * 0.04
            for (k in 0 until 4) {
                val a = accelAt(t + k * 0.01)
                h += v * 0.01 + 0.5 * a * 1e-4
                v += a * 0.01
            }
            kf.predict(0.04)
            kf.update(h + rnd.nextGaussian() * altNoise, FilterDefaults.BARO_VARIANCE)
            out.add(Sample(t + 0.04, kf.v, v))
        }
        return out
    }

    private fun rms(s: List<Sample>, from: Double, to: Double): Double {
        val w = s.filter { it.t > from && it.t < to }
        return sqrt(w.sumOf { (it.est - it.truth) * (it.est - it.truth) } / w.size)
    }

    private fun baroFused(altNoise: Double, accelNoise: Double, seed: Long = 1) = runFused(
        FilterDefaults.BARO_ACCEL_SIGMA, FilterDefaults.BARO_BIAS_WALK, FilterDefaults.BARO_VARIANCE,
        4, altNoise, accelNoise, seed,
    )

    @Test
    fun baroFusedStepSettlesWithinOneSecondWithoutOvershoot() {
        val s = baroFused(0.0, 0.0)
        assertTrue("bias must be learned before the step", abs(s.first { it.t >= 19.0 }.est) < 0.05)
        assertTrue("overshoot under 10%", s.filter { it.t in 20.0..30.0 }.maxOf { it.est } < 2.2)
        assertTrue("settled within 1 s", s.filter { it.t in 21.0..30.0 }.all { abs(it.est - 2.0) < 0.2 })
    }

    @Test
    fun baroFusedNoiseIsLow() {
        for (seed in 1L..5L) {
            assertTrue(rms(baroFused(0.25, 0.08, seed), 25.0, 49.0) < 0.15)
        }
    }

    @Test
    fun baroOnlyStepSettlesWithinThreeSeconds() {
        val s = runBaroOnly(0.0, 1)
        assertTrue(s.filter { it.t in 20.0..30.0 }.maxOf { it.est } < 2.3)
        assertTrue(s.filter { it.t in 23.0..30.0 }.all { abs(it.est - 2.0) < 0.2 })
    }

    @Test
    fun baroOnlyNoiseIsLow() {
        for (seed in 1L..5L) {
            assertTrue(rms(runBaroOnly(0.25, seed), 25.0, 49.0) < 0.25)
        }
    }

    @Test
    fun fusedFilterRecoversFromBiasStep() {
        // A sudden 0.5 m/s² accelerometer error (e.g. attitude error in a turn) must fade out.
        val kf = Kalman3(FilterDefaults.BARO_ACCEL_SIGMA, FilterDefaults.BARO_BIAS_WALK)
        kf.reset(500.0)
        val dt = 0.01
        for (i in 0 until 6000) {
            kf.predict(if (i >= 1000) 0.5 else 0.0, dt)
            if (i % 4 == 0) kf.update(500.0, FilterDefaults.BARO_VARIANCE)
        }
        assertTrue(abs(kf.v) < 0.1)
        assertTrue(abs(kf.b - 0.5) < 0.1)
    }
}
