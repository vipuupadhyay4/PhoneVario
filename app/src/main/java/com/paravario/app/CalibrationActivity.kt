package com.paravario.app

import android.app.Activity
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.paravario.app.vario.AccelCal
import com.paravario.app.vario.SixFaceCollector
import java.util.Locale

/** One-time six-position accelerometer calibration. */
class CalibrationActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var statusView: TextView
    private val faceViews = ArrayList<TextView>()
    private var collector = SixFaceCollector()
    private var finished = false

    private val faceLabels = intArrayOf(
        R.string.face_x_up, R.string.face_x_down,
        R.string.face_y_up, R.string.face_y_down,
        R.string.face_z_up, R.string.face_z_down,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            setText(R.string.cal_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            setText(R.string.cal_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, pad / 2, 0, pad)
        })
        // Show "screen up" first: it is the easiest one.
        for (face in intArrayOf(4, 5, 2, 3, 0, 1)) {
            val tv = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setPadding(0, pad / 3, 0, pad / 3)
                tag = face
            }
            faceViews.add(tv)
            root.addView(tv)
        }
        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad, 0, pad)
        }
        root.addView(statusView)
        root.addView(Button(this).apply {
            setText(R.string.cal_restart)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            minHeight = (64 * resources.displayMetrics.density).toInt()
            setOnClickListener { restart() }
        })
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(pad + i.left, pad + i.top, pad + i.right, pad + i.bottom)
            }
            insets
        }
        setContentView(root)
        restart()
    }

    private fun restart() {
        collector = SixFaceCollector()
        finished = false
        statusView.setText(R.string.cal_status_collecting)
        renderFaces()
    }

    private fun renderFaces() {
        for (tv in faceViews) {
            val face = tv.tag as Int
            val mark = if (collector.faces[face] != null) "✓ " else "○ "
            tv.text = mark + getString(faceLabels[face])
        }
    }

    override fun onResume() {
        super.onResume()
        val acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc == null) {
            statusView.setText(R.string.status_no_sensors)
        } else {
            sensorManager.registerListener(this, acc, maxOf(acc.minDelay, 10_000))
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    override fun onSensorChanged(e: SensorEvent) {
        if (finished) return
        val face = collector.add(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
        if (face < 0) return
        renderFaces()
        if (!collector.done) return
        finished = true
        val cal = collector.result()
        if (cal == null) {
            statusView.setText(R.string.cal_status_failed)
            return
        }
        getSharedPreferences("app", MODE_PRIVATE).edit().putString(PREF_KEY, cal.encode()).apply()
        statusView.text = getString(
            R.string.cal_status_done,
            "%.2f, %.2f, %.2f".format(Locale.US, cal.offset[0], cal.offset[1], cal.offset[2]),
            "%.3f, %.3f, %.3f".format(Locale.US, cal.scale[0], cal.scale[1], cal.scale[2]),
        )
    }

    companion object {
        const val PREF_KEY = "accel_cal"
    }
}
