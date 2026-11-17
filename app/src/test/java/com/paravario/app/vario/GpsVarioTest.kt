package com.paravario.app.vario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** GPS-only vario: direction must be right, whatever the exact number. */
class GpsVarioTest {

    private class Rec(val t: Double, val truth: Double, val zone: Int, val vario: Double)

    /** Perfect 1 Hz GPS. [speed] gives the true vertical speed during second i. */
    private fun clean(seconds: Int, jumpAt: Int = -1, jump: Double = 0.0, speed: (Int) -> Double): List<Rec> {
        val g = GpsVario()
        var h = 500.0
        val out = ArrayList<Rec>()
        for (i in 0 until seconds) {
            h += speed(i)
            g.onFix(i + 1.0, h + if (jumpAt in 0..i) jump else 0.0)
            out.add(Rec(i + 1.0, speed(i), g.zone, g.vario))
        }
        return out
    }

    private fun ramp(t: Double, t0: Double, t1: Double, a: Double, b: Double) =
        a + (b - a) * ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)

    /** Glide, thermal entry at 60 s, circling climb, strong sink from 120 s, glide again. */
    private fun flight(t: Double) = when {
        t < 60 -> -1.2
        t < 62 -> ramp(t, 60.0, 62.0, -1.2, 2.0)
        t < 120 -> 2.0 + 0.7 * sin(2 * PI * (t - 62) / 20)
        t < 123 -> ramp(t, 120.0, 123.0, 2.0, -2.5)
        t < 160 -> -2.5
        t < 162 -> ramp(t, 160.0, 162.0, -2.5, -1.2)
        else -> -1.2
    }

    /** Realistic 1 Hz GPS: white noise, slow wander of a few metres and occasional jumps. */
    private fun noisy(seconds: Int, seed: Long, speed: (Double) -> Double): List<Rec> {
        val rnd = Random(seed)
        val g = GpsVario()
        var h = 1000.0
        var wander = 0.0
        var jump = 0.0
        val tau = 60.0
        val out = ArrayList<Rec>()
        for (i in 0 until seconds) {
            val t = i.toDouble()
            h += speed(t)
            wander += -wander / tau + rnd.nextGaussian() * 2.0 * sqrt(2.0 / tau)
            if (rnd.nextDouble() < 1.0 / 60) jump += rnd.nextGaussian() * 2.0
            g.onFix(t + 1, h + wander + jump + rnd.nextGaussian() * 0.5)
            out.add(Rec(t + 1, speed(t + 1), g.zone, g.vario))
        }
        return out
    }

    @Test
    fun standingStillIsSilent() {
        assertTrue(clean(120) { 0.0 }.all { it.zone == 0 })
    }

    @Test
    fun steadyClimbIsDetectedWithinThreeSecondsAndHeld() {
        val r = clean(60) { if (it >= 20) 1.0 else 0.0 }
        val first = r.first { it.zone == 1 }.t
        assertTrue("first climb at $first", first <= 23.0)
        assertTrue(r.filter { it.t > 30 }.all { it.zone == 1 })
        assertEquals(1.0, r.last().vario, 0.1)
    }

    @Test
    fun steadyDescentIsDetected() {
        val r = clean(60) { if (it >= 20) -1.2 else 0.0 }
        assertTrue(r.first { it.zone == -1 }.t <= 23.0)
        assertTrue(r.filter { it.t > 30 }.all { it.zone == -1 })
        assertEquals(-1.2, r.last().vario, 0.1)
    }

    @Test
    fun strongThermalIsFollowedQuickly() {
        val r = clean(40) { if (it >= 20) 6.0 else -1.0 }
        assertTrue(r.first { it.zone == 1 }.t <= 23.0)
        assertEquals(6.0, r.first { it.t >= 30.0 }.vario, 0.5)
    }

    @Test
    fun climbToSinkSwitchesDirection() {
        val r = clean(70) { if (it < 20) -1.2 else if (it < 40) 3.0 else -2.0 }
        assertTrue(r.first { it.zone == 1 }.t <= 24.0)
        assertTrue(r.first { it.t > 40 && it.zone == -1 }.t <= 46.0)
        assertTrue(r.filter { it.t > 50 }.all { it.zone == -1 })
    }

    @Test
    fun gpsAltitudeJumpsDoNotSound() {
        for (j in doubleArrayOf(3.0, 5.0, 10.0, -8.0)) {
            val r = clean(80, jumpAt = 40, jump = j) { 0.0 }
            assertTrue("jump $j", r.all { it.zone == 0 })
        }
    }

    @Test
    fun gapRestartsCleanly() {
        val g = GpsVario()
        for (i in 0 until 20) g.onFix(i.toDouble(), 500.0 + 2.0 * i)
        assertEquals(1, g.zone)
        g.onFix(60.0, 900.0) // fix after a long gap: start again, no tone from the altitude gap
        assertEquals(0, g.zone)
        for (i in 1..10) g.onFix(60.0 + i, 900.0)
        assertEquals(0, g.zone)
    }

    @Test
    fun flightWithNoisyGpsGivesTheRightDirection() {
        var okSum = 0.0
        for (seed in 1L..10L) {
            val r = noisy(200, seed, ::flight)
            val clear = r.filter {
                it.t > 15 && abs(it.truth) >= 1.0 && !(it.t >= 60 && it.t < 67) && !(it.t >= 120 && it.t < 128)
            }
            val ok = clear.count { it.zone == if (it.truth > 0) 1 else -1 }.toDouble() / clear.size
            val wrong = clear.count { it.zone == if (it.truth > 0) -1 else 1 }.toDouble() / clear.size
            assertTrue("seed $seed ok $ok", ok >= 0.90)
            assertTrue("seed $seed wrong $wrong", wrong <= 0.04)
            val climbDelay = r.first { it.t >= 60 && it.zone == 1 }.t - 60
            val sinkDelay = r.first { it.t >= 120 && it.zone == -1 }.t - 120
            assertTrue("seed $seed climb delay $climbDelay", climbDelay <= 10)
            assertTrue("seed $seed sink delay $sinkDelay", sinkDelay <= 12)
            okSum += ok
        }
        assertTrue("mean ok ${okSum / 10}", okSum / 10 >= 0.96)
    }

    @Test
    fun standingStillWithNoisyGpsIsMostlySilent() {
        var tones = 0.0
        for (seed in 1L..10L) {
            val r = noisy(300, seed) { 0.0 }.filter { it.t > 15 }
            tones += r.count { it.zone != 0 }.toDouble() / r.size
        }
        assertTrue("tone fraction ${tones / 10}", tones / 10 <= 0.10)
    }

    @Test
    fun lowerSensitivityNeedsClearerEvidence() {
        val normal = GpsVario()
        val careful = GpsVario().apply { thresholdScale = 1.5 }
        var firstNormal = -1
        var firstCareful = -1
        for (i in 0 until 60) {
            val z = 500.0 + 0.6 * maxOf(0, i - 10)
            normal.onFix(i.toDouble(), z)
            careful.onFix(i.toDouble(), z)
            if (firstNormal < 0 && normal.zone == 1) firstNormal = i
            if (firstCareful < 0 && careful.zone == 1) firstCareful = i
        }
        assertTrue(firstNormal in 0..firstCareful)
    }
}
