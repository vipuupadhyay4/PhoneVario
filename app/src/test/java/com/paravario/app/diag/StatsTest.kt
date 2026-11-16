package com.paravario.app.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsTest {

    @Test
    fun altitudeAtStandardPressureIsZero() {
        assertEquals(0.0, Altitude.fromPressure(1013.25), 1e-9)
    }

    @Test
    fun altitudeMatchesStandardAtmosphere() {
        // ISA: 898.76 hPa ≈ 1000 m, 795.01 hPa ≈ 2000 m
        assertEquals(1000.0, Altitude.fromPressure(898.76), 1.0)
        assertEquals(2000.0, Altitude.fromPressure(795.01), 2.0)
    }

    @Test
    fun altitudeUsesQnh() {
        assertEquals(0.0, Altitude.fromPressure(1020.0, 1020.0), 1e-9)
    }

    @Test
    fun sampleStd() {
        assertEquals(1.2909944, Stats.std(doubleArrayOf(1.0, 2.0, 3.0, 4.0)), 1e-6)
    }

    @Test
    fun lineFitRemovesTrend() {
        val t = DoubleArray(50) { it * 0.1 }
        val x = DoubleArray(50) { 5.0 + 0.3 * t[it] }
        val fit = Stats.lineFit(t, x)
        assertEquals(0.3, fit.slopePerSec, 1e-9)
        assertEquals(0.0, fit.residualStd, 1e-9)
    }

    @Test
    fun rateFromTimestamps() {
        val ts = LongArray(26) { it * 40_000_000L } // every 40 ms
        val r = Stats.rate(ts)!!
        assertEquals(25.0, r.rateHz, 1e-9)
        assertEquals(40.0, r.meanMs, 1e-9)
        assertEquals(0.0, r.jitterMs, 1e-9)
    }

    @Test
    fun rateNeedsTwoSamples() {
        assertNull(Stats.rate(longArrayOf(5L)))
    }

    @Test
    fun quantisationStep() {
        val (distinct, step) = Stats.quantisation(doubleArrayOf(1000.00, 1000.02, 1000.02, 1000.05))
        assertEquals(3, distinct)
        assertEquals(0.02, step, 1e-9)
    }
}
