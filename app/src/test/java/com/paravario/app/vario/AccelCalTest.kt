package com.paravario.app.vario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AccelCalTest {
    private val trueOffset = doubleArrayOf(0.10, -0.25, 0.42)
    private val trueScale = doubleArrayOf(1.012, 0.985, 1.03)

    /** What the faulty sensor reports for a true gravity vector. */
    private fun raw(gx: Double, gy: Double, gz: Double) = doubleArrayOf(
        gx / trueScale[0] + trueOffset[0], gy / trueScale[1] + trueOffset[1], gz / trueScale[2] + trueOffset[2],
    )

    /** Six faces, each tilted a few degrees so none is exactly level. */
    private fun faces(): List<DoubleArray> {
        val g = AccelCal.G
        val out = ArrayList<DoubleArray>()
        val tilts = doubleArrayOf(0.10, -0.15, 0.20, 0.05, -0.25, 0.12)
        for (f in 0 until 6) {
            val axis = f / 2
            val sign = if (f % 2 == 0) 1.0 else -1.0
            val v = DoubleArray(3)
            v[axis] = sign * g * cos(tilts[f])
            v[(axis + 1) % 3] = g * sin(tilts[f]) * 0.6
            v[(axis + 2) % 3] = g * sin(tilts[f]) * 0.8
            out.add(raw(v[0], v[1], v[2]))
        }
        return out
    }

    @Test
    fun fitRecoversOffsetAndScaleFromTiltedFaces() {
        val cal = AccelCal.fit(faces())
        assertNotNull(cal)
        for (i in 0 until 3) {
            assertEquals(trueOffset[i], cal!!.offset[i], 1e-6)
            assertEquals(trueScale[i], cal.scale[i], 1e-6)
        }
    }

    @Test
    fun correctedReadingsHaveLengthG() {
        val cal = AccelCal.fit(faces())!!
        val r = raw(3.0, -4.0, sqrt(AccelCal.G * AccelCal.G - 25.0))
        var n = 0.0
        for (i in 0 until 3) {
            val c = (r[i] - cal.offset[i]) * cal.scale[i]
            n += c * c
        }
        assertEquals(AccelCal.G, sqrt(n), 1e-6)
    }

    @Test
    fun fitRejectsTooFewOrBrokenReadings() {
        assertNull(AccelCal.fit(faces().take(5)))
        assertNull(AccelCal.fit(List(6) { doubleArrayOf(0.0, 0.0, 9.8) }))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val cal = AccelCal.fit(faces())!!
        val back = AccelCal.decode(cal.encode())!!
        assertTrue(cal.offset.contentEquals(back.offset) && cal.scale.contentEquals(back.scale))
        assertNull(AccelCal.decode("1,2,3"))
        assertNull(AccelCal.decode(null))
    }

    @Test
    fun collectorCapturesEachStillFaceOnce() {
        val c = SixFaceCollector(windowSize = 50)
        val f = faces()
        // Shaking is ignored
        for (i in 0 until 50) assertEquals(-1, c.add(0.0, 0.0, if (i % 2 == 0) 8.0 else 11.0))
        for ((idx, face) in f.withIndex()) {
            var got = -1
            for (i in 0 until 50) got = maxOf(got, c.add(face[0], face[1], face[2]))
            assertEquals(idx, got)
            // Same face again is not captured twice
            for (i in 0 until 50) assertEquals(-1, c.add(face[0], face[1], face[2]))
        }
        assertTrue(c.done)
        val cal = c.result()!!
        assertEquals(trueOffset[2], cal.offset[2], 1e-6)
    }
}
