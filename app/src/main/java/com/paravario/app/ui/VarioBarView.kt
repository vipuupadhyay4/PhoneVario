package com.paravario.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Horizontal bar, zero in the middle: green to the right for climb, red to the left for sink. */
class VarioBarView(context: Context) : View(context) {
    var vario = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = w / 2
        paint.color = 0x33888888
        canvas.drawRect(0f, 0f, w, h, paint)
        val frac = (vario / FULL_SCALE).coerceIn(-1f, 1f)
        if (frac >= 0) {
            paint.color = CLIMB
            canvas.drawRect(mid, 0f, mid + frac * mid, h, paint)
        } else {
            paint.color = SINK
            canvas.drawRect(mid + frac * mid, 0f, mid, h, paint)
        }
        paint.color = 0xFF888888.toInt()
        canvas.drawRect(mid - 2, 0f, mid + 2, h, paint)
    }

    companion object {
        const val FULL_SCALE = 5f
        val CLIMB = 0xFF00C853.toInt()
        val SINK = 0xFFD50000.toInt()
    }
}
