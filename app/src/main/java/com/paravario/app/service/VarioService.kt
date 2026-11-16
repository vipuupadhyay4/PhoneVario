package com.paravario.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.paravario.app.CalibrationActivity
import com.paravario.app.MainActivity
import com.paravario.app.R
import com.paravario.app.audio.ToneEngine
import com.paravario.app.diag.Altitude
import com.paravario.app.vario.AccelCal
import com.paravario.app.vario.FilterDefaults
import com.paravario.app.vario.Kalman2
import com.paravario.app.vario.Kalman3
import kotlin.math.abs

/**
 * Foreground service: sensors -> Kalman filter -> tone engine, with a partial wake lock so it
 * keeps running with the screen off. Picks the best altitude source the phone has:
 * barometer + IMU, barometer only, or GPS + IMU (low accuracy).
 */
class VarioService : Service(), SensorEventListener, LocationListener {

    companion object {
        private const val CHANNEL_ID = "vario"
        private const val NOTIF_ID = 1
        private const val G = 9.80665
        private const val IMU_PERIOD_US = 10_000 // 100 Hz
        private const val CALIBRATION_NS = 2_000_000_000L
        private const val GPS_TIMEOUT_MS = 5_000L
        private const val SENSOR_TIMEOUT_MS = 2_000L
        private const val GPS_JUMP_M = 30.0
        const val CLIMB_ON_BARO = 0.2
        const val CLIMB_ON_GPS = 0.5 // GPS mode is noisier, so beep only on a clearer climb

        fun detectMode(sm: SensorManager): Mode {
            val baro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
            val imu = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null && rotationSensor(sm) != null
            return when {
                baro && imu -> Mode.BARO_IMU
                baro -> Mode.BARO_ONLY
                imu -> Mode.GPS_IMU
                else -> Mode.NONE
            }
        }

        /** Game rotation vector first: it ignores the magnetometer, which metal and speakers disturb. */
        fun rotationSensor(sm: SensorManager): Sensor? =
            sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, VarioService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, VarioService::class.java))
        }
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val tone = ToneEngine()
    private var started = false

    private var mode = Mode.NONE
    private val kf3 = Kalman3(FilterDefaults.BARO_ACCEL_SIGMA, FilterDefaults.BARO_BIAS_WALK)
    private val kf2 = Kalman2(FilterDefaults.BARO_ONLY_ACCEL_SIGMA)
    private var altitudeVariance = FilterDefaults.BARO_VARIANCE
    private var cal = AccelCal.IDENTITY

    // All fields below are touched only on the worker thread.
    private val quat = FloatArray(4)
    private val rot = FloatArray(9)
    private var haveRot = false
    private var calibrated = false
    private var calStartNs = 0L
    private var calSum = 0.0
    private var calCount = 0
    private var bias0 = 0.0
    private var filterReady = false
    private var lastPredictNs = 0L
    private var lastBaroNs = 0L
    private var publishDivider = 0

    private var lastAccelMs = 0L
    private var lastBaroMs = 0L
    private var lastGpsMs = 0L
    private var hadFix = false
    private var speedKmh = Double.NaN
    private var geoidOffset = 0.0
    private var warning = Warning.NONE

    private val watchdog = object : Runnable {
        override fun run() {
            checkHealth()
            handler?.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_NOT_STICKY
        started = true
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        mode = detectMode(sensorManager)
        cal = AccelCal.decode(getSharedPreferences("app", MODE_PRIVATE).getString(CalibrationActivity.PREF_KEY, null))
            ?: AccelCal.IDENTITY
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        try {
            goForeground(hasLocation)
        } catch (e: Exception) {
            // The system refused the foreground start (e.g. started from the background).
            stopSelf()
            return START_NOT_STICKY
        }
        if (mode == Mode.NONE || (mode == Mode.GPS_IMU && !hasLocation)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "paravario:vario").apply {
            setReferenceCounted(false)
            acquire()
        }

        if (mode == Mode.GPS_IMU) {
            kf3.accelSigma = FilterDefaults.GPS_ACCEL_SIGMA
            kf3.biasWalk = FilterDefaults.GPS_BIAS_WALK
            altitudeVariance = FilterDefaults.GPS_VARIANCE
            tone.climbOn = CLIMB_ON_GPS
        } else {
            tone.climbOn = CLIMB_ON_BARO
        }

        val t = HandlerThread("vario-sensors").apply { start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        val now = SystemClock.elapsedRealtime()
        lastAccelMs = now; lastBaroMs = now; lastGpsMs = now

        if (mode == Mode.BARO_IMU || mode == Mode.BARO_ONLY) {
            sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
                sensorManager.registerListener(this, it, maxOf(it.minDelay, 5000), h)
            }
        }
        if (mode == Mode.BARO_IMU || mode == Mode.GPS_IMU) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(this, it, maxOf(it.minDelay, IMU_PERIOD_US), h)
            }
            rotationSensor(sensorManager)?.let {
                sensorManager.registerListener(this, it, maxOf(it.minDelay, IMU_PERIOD_US), h)
            }
        }
        if (hasLocation) startGps(t)

        tone.start()
        VarioState.snap = Snapshot(running = true, mode = mode, warning = Warning.CALIBRATING)
        h.post(watchdog)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler?.removeCallbacksAndMessages(null)
        if (started) {
            sensorManager.unregisterListener(this)
            try {
                locationManager.removeUpdates(this)
            } catch (_: Exception) {
            }
        }
        tone.stop()
        thread?.quitSafely()
        wakeLock?.let { if (it.isHeld) it.release() }
        VarioState.snap = Snapshot(running = false, mode = mode)
        super.onDestroy()
    }

    private fun goForeground(hasLocation: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_stat_vario)
            .setOngoing(true)
            .setContentIntent(open)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        val notification = builder.build()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> startForeground(
                NOTIF_ID, notification,
                if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasLocation ->
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else -> startForeground(NOTIF_ID, notification)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGps(t: HandlerThread) {
        try {
            if (locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, this, t.looper)
            }
        } catch (_: Exception) {
            // No GPS: in GPS mode the watchdog keeps showing "waiting for GPS".
        }
    }

    // ---- Sensors (worker thread) ----

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                System.arraycopy(e.values, 0, quat, 0, minOf(4, e.values.size))
                SensorManager.getRotationMatrixFromVector(rot, quat)
                haveRot = true
            }
            Sensor.TYPE_ACCELEROMETER -> onAccel(e)
            Sensor.TYPE_PRESSURE -> onPressure(e)
        }
    }

    private fun onAccel(e: SensorEvent) {
        lastAccelMs = SystemClock.elapsedRealtime()
        if (!haveRot) return
        // Earth-frame vertical acceleration with gravity removed.
        // Per-axis calibration first, otherwise tilting the phone looks like climb or sink.
        val ax = (e.values[0] - cal.offset[0]) * cal.scale[0]
        val ay = (e.values[1] - cal.offset[1]) * cal.scale[1]
        val azBody = (e.values[2] - cal.offset[2]) * cal.scale[2]
        val az = rot[6] * ax + rot[7] * ay + rot[8] * azBody - G

        if (!calibrated) {
            // Average for 2 s to get the starting accelerometer bias.
            if (calStartNs == 0L) calStartNs = e.timestamp
            calSum += az
            calCount++
            if (e.timestamp - calStartNs >= CALIBRATION_NS) {
                bias0 = calSum / calCount
                calibrated = true
            }
            return
        }
        if (!filterReady) return
        if (lastPredictNs == 0L) {
            lastPredictNs = e.timestamp
            return
        }
        val dt = (e.timestamp - lastPredictNs) / 1e9
        lastPredictNs = e.timestamp
        if (dt <= 0 || dt > 0.5) return
        kf3.predict(az, dt)
        tone.vario = kf3.v.toFloat()
        if (++publishDivider >= 5) {
            publishDivider = 0
            publish()
        }
    }

    private fun onPressure(e: SensorEvent) {
        lastBaroMs = SystemClock.elapsedRealtime()
        val alt = Altitude.fromPressure(e.values[0].toDouble())
        if (mode == Mode.BARO_ONLY) {
            if (!filterReady) {
                kf2.reset(alt)
                filterReady = true
            } else {
                val dt = (e.timestamp - lastBaroNs) / 1e9
                if (dt > 0 && dt < 2.0) {
                    kf2.predict(dt)
                    kf2.update(alt, FilterDefaults.BARO_VARIANCE)
                } else {
                    kf2.reset(alt)
                }
            }
            lastBaroNs = e.timestamp
            tone.vario = kf2.v.toFloat()
            publish()
        } else {
            measure(alt)
        }
    }

    /** Altitude measurement for the fused filter (from baro or GPS). */
    private fun measure(alt: Double) {
        if (!calibrated) return
        if (!filterReady) {
            kf3.reset(alt, bias0)
            filterReady = true
            lastPredictNs = 0L
        } else if (abs(alt - kf3.h) > GPS_JUMP_M) {
            kf3.reset(alt, kf3.b)
        } else {
            kf3.update(alt, altitudeVariance)
        }
        publish()
    }

    // ---- GPS (worker thread) ----

    override fun onLocationChanged(location: Location) {
        lastGpsMs = SystemClock.elapsedRealtime()
        hadFix = true
        speedKmh = if (location.hasSpeed()) location.speed * 3.6 else Double.NaN
        if (!location.hasAltitude()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && location.hasMslAltitude()) {
            geoidOffset = location.mslAltitudeMeters - location.altitude
        }
        if (mode == Mode.GPS_IMU) measure(location.altitude) else publish()
    }

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    // ---- Health and publishing (worker thread) ----

    private fun checkHealth() {
        val now = SystemClock.elapsedRealtime()
        val gpsFresh = hadFix && now - lastGpsMs < GPS_TIMEOUT_MS
        val usesImu = mode == Mode.BARO_IMU || mode == Mode.GPS_IMU
        val usesBaro = mode == Mode.BARO_IMU || mode == Mode.BARO_ONLY
        warning = when {
            tone.error != null -> Warning.AUDIO_ERROR
            usesImu && now - lastAccelMs > SENSOR_TIMEOUT_MS -> Warning.SENSOR_STOPPED
            usesBaro && now - lastBaroMs > SENSOR_TIMEOUT_MS -> Warning.SENSOR_STOPPED
            mode == Mode.GPS_IMU && hadFix && !gpsFresh -> Warning.GPS_LOST
            usesImu && !calibrated -> Warning.CALIBRATING
            mode == Mode.GPS_IMU && !hadFix -> Warning.WAITING_GPS
            else -> Warning.NONE
        }
        if (!gpsFresh) speedKmh = Double.NaN
        publish()
    }

    private fun publish() {
        val ready = filterReady && warning == Warning.NONE
        // Never beep on bad data; a distinct alarm says that something stopped.
        tone.active = ready
        tone.alarm = warning == Warning.SENSOR_STOPPED || warning == Warning.GPS_LOST
        val v: Double
        val h: Double
        if (mode == Mode.BARO_ONLY) {
            v = kf2.v; h = kf2.h
        } else {
            v = kf3.v; h = kf3.h + if (mode == Mode.GPS_IMU) geoidOffset else 0.0
        }
        VarioState.snap = Snapshot(
            running = true,
            mode = mode,
            warning = warning,
            ready = ready,
            vario = if (filterReady) v else 0.0,
            altitude = if (filterReady) h else Double.NaN,
            speedKmh = speedKmh,
            gpsFix = hadFix && SystemClock.elapsedRealtime() - lastGpsMs < GPS_TIMEOUT_MS,
        )
    }
}
