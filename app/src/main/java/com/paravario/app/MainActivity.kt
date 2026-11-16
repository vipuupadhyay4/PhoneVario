package com.paravario.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.paravario.app.diag.BenchmarkReport
import com.paravario.app.diag.DeviceReport
import com.paravario.app.diag.SensorBenchmark

/** Phase 0: diagnostics only. Lists sensors and runs a 10 s still test on the barometer. */
class MainActivity : Activity() {

    private lateinit var sensorManager: SensorManager
    private lateinit var statusView: TextView
    private lateinit var reportView: TextView
    private lateinit var startButton: Button

    private val ui = Handler(Looper.getMainLooper())
    private var benchmark: SensorBenchmark? = null
    private var deviceReport = ""
    private var benchmarkReport = ""

    private val progressTick = object : Runnable {
        override fun run() {
            val b = benchmark ?: return
            statusView.text = getString(
                R.string.status_measuring,
                b.elapsedSeconds(),
                SensorBenchmark.MEASURE_SECONDS,
                b.pressureCount(),
            )
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Some phones throttle sensors when the screen dims, which would spoil the rate test.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        setContentView(buildLayout())
        deviceReport = DeviceReport.build(this, sensorManager)
        val hasBaro = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
        statusView.text = getString(if (hasBaro) R.string.status_ready else R.string.status_no_baro)
        renderReport()
    }

    override fun onPause() {
        super.onPause()
        if (benchmark != null) {
            cancelBenchmark()
            statusView.text = getString(R.string.status_cancelled)
        }
    }

    private fun startBenchmark() {
        if (benchmark != null) return
        val sensors = SensorBenchmark.sensorsToTest(sensorManager)
        if (sensors.isEmpty()) {
            statusView.text = getString(R.string.status_no_sensors)
            return
        }
        benchmarkReport = ""
        renderReport()
        startButton.isEnabled = false
        val b = SensorBenchmark(sensorManager, sensors)
        benchmark = b
        try {
            startChecked(b)
        } catch (e: Exception) {
            b.cancel()
            benchmark = null
            startButton.isEnabled = true
            statusView.text = getString(R.string.status_error, e.toString())
            return
        }
        ui.post(progressTick)
    }

    private fun startChecked(b: SensorBenchmark) {
        b.start { results ->
            // Never let a report bug kill the app: show the error in the report instead.
            val text = try {
                BenchmarkReport.format(results)
            } catch (e: Exception) {
                "=== TEST ERROR ===\n$e\n"
            }
            ui.post {
                if (benchmark !== b) return@post
                benchmark = null
                ui.removeCallbacks(progressTick)
                startButton.isEnabled = true
                benchmarkReport = text
                statusView.text = getString(R.string.status_done)
                renderReport()
            }
        }
    }

    private fun cancelBenchmark() {
        benchmark?.cancel()
        benchmark = null
        ui.removeCallbacks(progressTick)
        startButton.isEnabled = true
    }

    private fun fullReport(): String = buildString {
        append(deviceReport)
        if (benchmarkReport.isNotEmpty()) {
            appendLine()
            append(benchmarkReport)
        }
        appendLine()
        appendLine("=== ADB CHECK ===")
        appendLine(DeviceReport.DUMPSYS_CMD)
    }

    private fun renderReport() {
        reportView.text = fullReport()
    }

    private fun copyReport() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("vario-diagnostics", fullReport()))
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    // ---- Layout (plain Views, no dependencies) ----

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun bigButton(textRes: Int, onClick: () -> Unit) = Button(this).apply {
        setText(textRes)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        minHeight = dp(64) // glove-friendly
        setOnClickListener { onClick() }
    }

    private fun buildLayout(): View {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            setText(R.string.title_phase0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
        })

        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusView)

        startButton = bigButton(R.string.btn_start) { startBenchmark() }
        val copyButton = bigButton(R.string.btn_copy) { copyReport() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(startButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(copyButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        reportView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
        }
        val scroll = ScrollView(this).apply { addView(reportView) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // targetSdk 35+ forces edge-to-edge on Android 15+, so pad for the system bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(pad + i.left, pad + i.top, pad + i.right, pad + i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    pad + insets.systemWindowInsetLeft, pad + insets.systemWindowInsetTop,
                    pad + insets.systemWindowInsetRight, pad + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        return root
    }
}
