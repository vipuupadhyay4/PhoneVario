package com.paravario.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToneTest {
    private val sr = 48000

    private fun render(synth: ToneSynth, seconds: Double, vario: Double, alarm: Boolean = false): ShortArray {
        val out = ShortArray((seconds * sr).toInt())
        val block = ShortArray(256)
        var pos = 0
        while (pos < out.size) {
            val n = minOf(block.size, out.size - pos)
            synth.render(block, n, vario, true, alarm, 1.0)
            System.arraycopy(block, 0, out, pos, n)
            pos += n
        }
        return out
    }

    private fun zeroFraction(x: ShortArray) = x.count { it.toInt() == 0 }.toDouble() / x.size

    @Test
    fun tableParsesAndFormatsRoundTrip() {
        val t = ToneTable.parse(ToneTable.DEFAULT_TEXT)
        assertEquals(8, t.points.size)
        val again = ToneTable.parse(t.format())
        assertEquals(t.points, again.points)
    }

    @Test
    fun tableInterpolatesAndClamps() {
        val t = ToneTable.DEFAULT
        val mid = t.climbTone(1.5)!!
        assertEquals(925.0, mid.freqHz, 1e-6)
        assertEquals(450.0, mid.cycleMs, 1e-6)
        assertEquals(1800.0, t.climbTone(20.0)!!.freqHz, 1e-6)
        assertEquals(700.0, t.climbTone(0.0)!!.freqHz, 1e-6)
        assertEquals(280.0, t.sinkTone(-3.0)!!.freqHz, 1e-6)
        assertEquals(240.0, t.sinkTone(-6.5)!!.freqHz, 1e-6)
    }

    @Test
    fun tableIgnoresCommentsAndRejectsGarbage() {
        assertEquals(1, ToneTable.parse("# my tones\n\ntone=1.0,800,500,50\n").points.size)
        assertNull(ToneTable.parse("tone=1.0,800,500,50").sinkTone(-5.0))
        try {
            ToneTable.parse("hello")
            assertTrue("should have thrown", false)
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun silentInNormalGlide() {
        val synth = ToneSynth(sr, ToneTable.DEFAULT)
        for (v in listOf(-2.0, -1.0, 0.0, 0.15)) {
            assertEquals(1.0, zeroFraction(render(synth, 0.5, v)), 0.0)
        }
    }

    @Test
    fun climbBeepsWithHalfDuty() {
        val synth = ToneSynth(sr, ToneTable.DEFAULT)
        val x = render(synth, 4.0, 2.0) // 400 ms cycle, 50% duty
        val zf = zeroFraction(x)
        assertTrue("zero fraction $zf", zf > 0.40 && zf < 0.55)
        assertTrue(x.maxOf { abs(it.toInt()) } <= ToneSynth.MAX_AMPLITUDE)
        assertTrue(x.maxOf { abs(it.toInt()) } > ToneSynth.MAX_AMPLITUDE * 0.95)
    }

    @Test
    fun sinkAlarmIsContinuous() {
        val synth = ToneSynth(sr, ToneTable.DEFAULT)
        render(synth, 0.1, -5.0)
        assertTrue(zeroFraction(render(synth, 2.0, -5.0)) < 0.02)
    }

    @Test
    fun beepsHaveNoClicks() {
        // The largest jump between two samples must stay close to that of a plain sine.
        for (v in listOf(8.0, 2.0, 0.3, -4.0)) {
            val x = render(ToneSynth(sr, ToneTable.DEFAULT), 3.0, v)
            var maxStep = 0
            for (i in 1 until x.size) maxStep = maxOf(maxStep, abs(x[i] - x[i - 1]))
            assertTrue("v=$v step=$maxStep", maxStep < 0.3 * ToneSynth.MAX_AMPLITUDE)
        }
    }

    @Test
    fun thresholdsHaveHysteresis() {
        val synth = ToneSynth(sr, ToneTable.DEFAULT)
        assertEquals(1.0, zeroFraction(render(synth, 0.3, -2.4)), 0.0)
        render(synth, 0.3, -2.6)
        assertTrue(zeroFraction(render(synth, 0.3, -2.4)) < 0.05) // still sinking tone
        render(synth, 0.3, -2.0)
        assertEquals(1.0, zeroFraction(render(synth, 0.3, -2.0)), 0.0)
    }

    @Test
    fun alarmOverridesVario() {
        val synth = ToneSynth(sr, ToneTable.DEFAULT)
        val x = render(synth, 3.0, 0.0, alarm = true)
        val zf = zeroFraction(x)
        assertTrue("alarm pips present, $zf", zf < 0.97 && zf > 0.85)
    }
}
