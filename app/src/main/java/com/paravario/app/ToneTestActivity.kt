package com.paravario.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.paravario.app.audio.ToneEngine
import java.util.Locale

/** Slider that plays the vario sound for any vertical speed, for tuning on the ground. */
class ToneTestActivity : Activity() {

    private val tone = ToneEngine()
    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            setText(R.string.tone_test_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        })
        label = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(label)
        val seek = SeekBar(this).apply {
            max = 200 // -10.0 .. +10.0 m/s in 0.1 steps
            progress = 100
            setPadding(pad, pad * 2, pad, pad * 2)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) = showVario(progress)
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        root.addView(seek)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(pad + i.left, pad + i.top, pad + i.right, pad + i.bottom)
            }
            insets
        }
        setContentView(root)
        showVario(100)
    }

    private fun showVario(progress: Int) {
        val v = (progress - 100) / 10f
        tone.vario = v
        label.text = "%+.1f".format(Locale.US, v)
    }

    override fun onResume() {
        super.onResume()
        tone.sinkOn = -0.5
        tone.active = true
        tone.start()
    }

    override fun onPause() {
        super.onPause()
        tone.stop()
    }
}
