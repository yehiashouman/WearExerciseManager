package com.yehiashouman.wearexercisemanager.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Reads the on-watch heart-rate sensor while a workout is running.
 *
 * [onHeartRate] receives `null` whenever no trustworthy value is available (missing permission, no
 * sensor, or the watch reports that it lost skin contact) so the UI can fall back to "-- bpm"
 * instead of showing a stale reading.
 */
class HeartRateMonitor(context: Context, private val onHeartRate: (Double?) -> Unit) : SensorEventListener {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    private var registered = false

    /** True when this watch exposes a heart-rate sensor at all. */
    val isAvailable: Boolean get() = sensor != null

    /** Starts sampling. Returns false when the sensor cannot be used, without throwing. */
    fun start(): Boolean {
        if (registered) return true
        val target = sensor
        if (manager == null || target == null) {
            Log.w(TAG, "No heart-rate sensor on this device")
            onHeartRate(null)
            return false
        }
        if (!HeartRatePermissions.granted(appContext)) {
            Log.w(TAG, "Heart-rate permission not granted; skipping sensor registration")
            onHeartRate(null)
            return false
        }
        registered = runCatching {
            manager.registerListener(this, target, SensorManager.SENSOR_DELAY_NORMAL)
        }.onFailure { Log.w(TAG, "Could not register the heart-rate listener", it) }.getOrDefault(false)
        if (!registered) onHeartRate(null)
        return registered
    }

    /** Unregisters the listener. Safe to call more than once and when never started. */
    fun stop() {
        if (!registered) return
        registered = false
        runCatching { manager?.unregisterListener(this) }
            .onFailure { Log.w(TAG, "Could not unregister the heart-rate listener", it) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor?.type != Sensor.TYPE_HEART_RATE) return
        val value = event.values?.firstOrNull()?.toDouble() ?: return
        // A zero or negative reading means the sensor has no valid measurement yet.
        onHeartRate(if (value > 0) value else null)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type != Sensor.TYPE_HEART_RATE) return
        if (accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT ||
            accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
        ) {
            onHeartRate(null)
        }
    }

    private companion object {
        const val TAG = "HeartRateMonitor"
    }
}
