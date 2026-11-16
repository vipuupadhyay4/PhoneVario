package com.paravario.app.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Turns vertical speed into PCM samples. Pure Kotlin so it can be unit tested.
 * Phase-continuous sine, 5 ms fade in/out on every beep, fluid pitch inside a beep.
 */
class ToneSynth(private val sampleRate: Int, private val table: ToneTable) {
    var climbOn = 0.2
    var sinkOn = -2.5

    private enum class Zone { SILENT, CLIMB, SINK }

    private var zone = Zone.SILENT
    private var phase = 0.0
    private var gain = 0.0
    private var freq = 0.0
    private var cyclePos = 0
    private var cycleLen = 0
    private var onLen = 0
    private var beeping = false
    private var alarmPos = 0

    private val rampStep = 1.0 / (RAMP_SECONDS * sampleRate)
    private val alarmPip = (0.1 * sampleRate).toInt()
    private val alarmPeriod = 3 * sampleRate

    /**
     * Fills [out] with [n] samples. [active] = false gives silence (not ready or muted).
     * [alarm] overrides the vario with a distinct double pip every 3 s.
     */
    fun render(out: ShortArray, n: Int, vario: Double, active: Boolean, alarm: Boolean, volume: Double) {
        val tone: TonePoint? = when {
            alarm -> null
            active -> select(vario)
            else -> { zone = Zone.SILENT; null }
        }
        val amp = volume.coerceIn(0.0, 1.0) * MAX_AMPLITUDE
        val target = if (alarm) ALARM_HZ else tone?.freqHz ?: freq
        if (gain == 0.0) freq = target
        if (!alarm) alarmPos = 0

        for (i in 0 until n) {
            val on: Boolean
            if (alarm) {
                beeping = false
                on = alarmPos < alarmPip || (alarmPos >= 2 * alarmPip && alarmPos < 3 * alarmPip)
                alarmPos++
                if (alarmPos >= alarmPeriod) alarmPos = 0
            } else if (tone != null) {
                if (!beeping || cyclePos >= cycleLen) {
                    beeping = true
                    cyclePos = 0
                    cycleLen = max(1, (tone.cycleMs * sampleRate / 1000.0).toInt())
                    onLen = (cycleLen * tone.dutyPct / 100.0).toInt()
                }
                on = cyclePos < onLen
                cyclePos++
            } else {
                beeping = false
                on = false
            }
            gain = if (on) min(1.0, gain + rampStep) else max(0.0, gain - rampStep)
            freq += (target - freq) * FREQ_SMOOTH
            phase += TWO_PI * freq / sampleRate
            if (phase >= TWO_PI) phase -= TWO_PI
            out[i] = (sin(phase) * gain * amp).toInt().toShort()
        }
    }

    /** Climb/sink thresholds with hysteresis so the tone does not chatter at the edge. */
    private fun select(v: Double): TonePoint? {
        zone = when (zone) {
            Zone.CLIMB -> if (v >= climbOn - CLIMB_HYST) Zone.CLIMB else if (v <= sinkOn) Zone.SINK else Zone.SILENT
            Zone.SINK -> if (v <= sinkOn + SINK_HYST) Zone.SINK else if (v >= climbOn) Zone.CLIMB else Zone.SILENT
            Zone.SILENT -> if (v >= climbOn) Zone.CLIMB else if (v <= sinkOn) Zone.SINK else Zone.SILENT
        }
        return when (zone) {
            Zone.CLIMB -> table.climbTone(max(v, climbOn))
            Zone.SINK -> table.sinkTone(min(v, sinkOn))
            Zone.SILENT -> null
        }
    }

    companion object {
        const val MAX_AMPLITUDE = 32767 * 0.8
        const val RAMP_SECONDS = 0.005
        const val CLIMB_HYST = 0.1
        const val SINK_HYST = 0.2
        const val ALARM_HZ = 2400.0
        private const val FREQ_SMOOTH = 0.002
        private const val TWO_PI = 2 * Math.PI
    }
}
