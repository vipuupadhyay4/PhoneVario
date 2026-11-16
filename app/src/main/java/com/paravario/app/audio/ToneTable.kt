package com.paravario.app.audio

import java.util.Locale

data class TonePoint(val vario: Double, val freqHz: Double, val cycleMs: Double, val dutyPct: Double)

/**
 * Tone table in XC Tracer format: `tone=<climb m/s>,<freq Hz>,<cycle ms>,<duty %>`.
 * Points with negative vario are the sink tones, the rest are the climb tones;
 * each side is interpolated linearly on its own and clamped at its ends.
 */
class ToneTable(points: List<TonePoint>) {
    val points: List<TonePoint> = points.sortedBy { it.vario }
    private val climb = this.points.filter { it.vario >= 0 }
    private val sink = this.points.filter { it.vario < 0 }

    fun climbTone(v: Double): TonePoint? = interpolate(climb, v)
    fun sinkTone(v: Double): TonePoint? = interpolate(sink, v)

    fun format(): String = points.joinToString("\n") {
        "tone=%.2f,%.0f,%.0f,%.0f".format(Locale.US, it.vario, it.freqHz, it.cycleMs, it.dutyPct)
    }

    private fun interpolate(list: List<TonePoint>, v: Double): TonePoint? {
        if (list.isEmpty()) return null
        if (v <= list.first().vario) return list.first()
        if (v >= list.last().vario) return list.last()
        for (i in 1 until list.size) {
            val a = list[i - 1]
            val b = list[i]
            if (v <= b.vario) {
                val f = (v - a.vario) / (b.vario - a.vario)
                return TonePoint(
                    v,
                    a.freqHz + f * (b.freqHz - a.freqHz),
                    a.cycleMs + f * (b.cycleMs - a.cycleMs),
                    a.dutyPct + f * (b.dutyPct - a.dutyPct),
                )
            }
        }
        return list.last()
    }

    companion object {
        const val DEFAULT_TEXT = "tone=-10.0,200,100,100\n" +
            "tone=-3.0,280,100,100\n" +
            "tone=-2.5,300,500,100\n" +
            "tone=0.2,700,600,50\n" +
            "tone=1.0,850,500,50\n" +
            "tone=2.0,1000,400,50\n" +
            "tone=4.0,1300,280,50\n" +
            "tone=8.0,1800,160,50"

        /** Parses XC Tracer style lines. Blank lines and lines starting with # are ignored. */
        fun parse(text: String): ToneTable {
            val pts = ArrayList<TonePoint>()
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                require(line.startsWith("tone=")) { "Bad tone line: $line" }
                val parts = line.substring(5).split(",").map { it.trim().toDouble() }
                require(parts.size == 4) { "Bad tone line: $line" }
                require(parts[1] > 0 && parts[2] > 0 && parts[3] in 0.0..100.0) { "Bad tone values: $line" }
                pts.add(TonePoint(parts[0], parts[1], parts[2], parts[3]))
            }
            return ToneTable(pts)
        }

        val DEFAULT: ToneTable by lazy { parse(DEFAULT_TEXT) }
    }
}
