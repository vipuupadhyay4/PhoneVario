package com.paravario.app.vario

import kotlin.math.abs
import kotlin.math.sqrt

/** Per-axis accelerometer correction: corrected = (raw - offset) * scale. */
class AccelCal(val offset: DoubleArray, val scale: DoubleArray) {

    fun encode(): String = (offset + scale).joinToString(",")

    companion object {
        const val G = 9.80665
        val IDENTITY = AccelCal(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 1.0, 1.0))

        fun decode(text: String?): AccelCal? {
            val p = text?.split(",")?.mapNotNull { it.toDoubleOrNull() } ?: return null
            if (p.size != 6) return null
            return AccelCal(doubleArrayOf(p[0], p[1], p[2]), doubleArrayOf(p[3], p[4], p[5]))
        }

        /**
         * Fits offset and scale from readings taken with the phone at rest in at least six
         * different orientations. At rest the corrected vector must have length g, which gives
         * A·x² + B·y² + C·z² + D·x + E·y + F·z = 1, linear in its six unknowns.
         * The phone does not have to be exactly level in any position.
         * Returns null when the readings do not give a sane result.
         */
        fun fit(samples: List<DoubleArray>): AccelCal? {
            if (samples.size < 6) return null
            // Normal equations (M'M) p = M'1
            val n = 6
            val a = Array(n) { DoubleArray(n + 1) }
            for (s in samples) {
                val row = doubleArrayOf(s[0] * s[0], s[1] * s[1], s[2] * s[2], s[0], s[1], s[2])
                for (i in 0 until n) {
                    for (j in 0 until n) a[i][j] += row[i] * row[j]
                    a[i][n] += row[i]
                }
            }
            val p = solve(a) ?: return null
            if (p[0] <= 0 || p[1] <= 0 || p[2] <= 0) return null
            val offset = DoubleArray(3) { -p[it + 3] / (2 * p[it]) }
            var sum = 1.0
            for (i in 0 until 3) sum += p[i] * offset[i] * offset[i]
            val k = G * G / sum
            val scale = DoubleArray(3) { sqrt(p[it] * k) }
            for (i in 0 until 3) {
                if (offset[i].isNaN() || scale[i].isNaN()) return null
                if (abs(offset[i]) > 2.0 || scale[i] < 0.85 || scale[i] > 1.15) return null
            }
            return AccelCal(offset, scale)
        }

        /** Gaussian elimination with partial pivoting on an n x (n+1) augmented matrix. */
        private fun solve(a: Array<DoubleArray>): DoubleArray? {
            val n = a.size
            // Scale columns so squared terms (~100) and linear terms (~10) are comparable.
            val colScale = DoubleArray(n) { j -> sqrt(a[j][j]).takeIf { it > 0 } ?: 1.0 }
            for (i in 0 until n) {
                for (j in 0 until n) a[i][j] /= colScale[i] * colScale[j]
                a[i][n] /= colScale[i]
            }
            for (c in 0 until n) {
                var best = c
                for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[best][c])) best = r
                if (abs(a[best][c]) < 1e-12) return null
                val tmp = a[c]; a[c] = a[best]; a[best] = tmp
                for (r in c + 1 until n) {
                    val f = a[r][c] / a[c][c]
                    for (j in c..n) a[r][j] -= f * a[c][j]
                }
            }
            val x = DoubleArray(n)
            for (i in n - 1 downTo 0) {
                var s = a[i][n]
                for (j in i + 1 until n) s -= a[i][j] * x[j]
                x[i] = s / a[i][i]
            }
            return DoubleArray(n) { x[it] / colScale[it] }
        }
    }
}

/**
 * Collects one resting reading per phone face. Feed it raw accelerometer samples; when the
 * phone has been still for about a second, the face that points up is recorded.
 * Face index = axis * 2 + (0 if that axis points up, 1 if it points down).
 */
class SixFaceCollector(private val windowSize: Int = 100) {
    val faces = arrayOfNulls<DoubleArray>(6)
    private val sum = DoubleArray(3)
    private val sumSq = DoubleArray(3)
    private var count = 0

    val done: Boolean get() = faces.all { it != null }

    /** Returns the index of a newly captured face, or -1. */
    fun add(x: Double, y: Double, z: Double): Int {
        val v = doubleArrayOf(x, y, z)
        for (i in 0 until 3) {
            sum[i] += v[i]
            sumSq[i] += v[i] * v[i]
        }
        if (++count < windowSize) return -1
        val mean = DoubleArray(3) { sum[it] / count }
        var still = true
        for (i in 0 until 3) {
            val variance = sumSq[i] / count - mean[i] * mean[i]
            if (variance > STILL_STD * STILL_STD) still = false
            sum[i] = 0.0
            sumSq[i] = 0.0
        }
        count = 0
        if (!still) return -1
        var axis = 0
        for (i in 1 until 3) if (abs(mean[i]) > abs(mean[axis])) axis = i
        if (abs(mean[axis]) < MIN_DOMINANT) return -1 // too far between two faces
        val face = axis * 2 + if (mean[axis] > 0) 0 else 1
        if (faces[face] != null) return -1
        faces[face] = mean
        return face
    }

    fun result(): AccelCal? = if (done) AccelCal.fit(faces.map { it!! }) else null

    companion object {
        const val STILL_STD = 0.12 // m/s²
        const val MIN_DOMINANT = 8.0 // m/s², i.e. within about 35° of that face
    }
}
