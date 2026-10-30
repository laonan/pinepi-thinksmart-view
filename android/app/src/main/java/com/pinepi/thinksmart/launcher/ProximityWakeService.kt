package com.pinepi.thinksmart.launcher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/** Explicitly enabled, visible sensor service for the Android 8.1 appliance. No audio access. */
class ProximityWakeService : Service(), SensorEventListener {
    private lateinit var sensors: SensorManager
    private lateinit var power: PowerManager
    private var listening = false
    private val gate = ProximityWakeGate()

    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(SensorManager::class.java)
        power = getSystemService(PowerManager::class.java)
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(
                CHANNEL, getString(R.string.proximity_wake_channel), NotificationManager.IMPORTANCE_LOW,
            ).apply { setSound(null, null); enableVibration(false) })
            val home = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1,
                Intent(this, ProximityWakeService::class.java).setAction(ACTION_DISABLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_proximity_wake)
                .setContentTitle(getString(R.string.proximity_wake_notification))
                .setContentText(getString(R.string.proximity_wake_notification_body))
                .setContentIntent(home)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(Notification.Action.Builder(null, getString(R.string.proximity_wake_disable), stop).build())
                .build())
        } catch (_: RuntimeException) {
            fail(R.string.proximity_wake_failed)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE) setEnabled(this, false)
        if (!isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!listening) {
            // A non-wake-up sensor cannot be relied on to deliver events while the CPU sleeps.
            val sensor = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
            if (sensor == null) {
                fail(R.string.proximity_wake_unsupported)
                return START_NOT_STICKY
            }
            listening = try {
                sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            } catch (_: RuntimeException) { false }
            if (!listening) {
                fail(R.string.proximity_wake_failed)
                return START_NOT_STICKY
            }
            updateStatus(R.string.proximity_wake_running)
            Log.i(TAG, "Sensor wake service started")
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!listening || !isEnabled(this) || event.values.isEmpty()) return
        if (gate.shouldWake(event.values[0], event.sensor.maximumRange,
                power.isInteractive, SystemClock.elapsedRealtime())) {
            wakeDisplay()
        }
    }

    private fun wakeDisplay() {
        if (DisplayWake.request(this, "ProximityWake") != DisplayWake.Result.FAILED) {
            Log.i(TAG, "Proximity requested display wake")
        } else {
            fail(R.string.proximity_wake_failed)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sensors.unregisterListener(this)
        listening = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (statusResource == R.string.proximity_wake_running) {
            updateStatus(if (isEnabled(this)) R.string.proximity_wake_starting else R.string.proximity_wake_off)
        }
        Log.i(TAG, "Sensor wake service stopped")
        super.onDestroy()
    }

    private fun fail(message: Int) {
        preferences(this).edit().putBoolean(KEY_ENABLED, false).apply()
        updateStatus(message)
        stopSelf()
    }

    companion object {
        private const val TAG = "PinePiWake"
        private const val CHANNEL = "proximity_wake"
        private const val NOTIFICATION_ID = 10
        private const val KEY_ENABLED = "enabled"
        private const val ACTION_DISABLE = "com.pinepi.thinksmart.launcher.DISABLE_PROXIMITY_WAKE"
        var statusResource: Int = R.string.proximity_wake_off
            private set
        var onChanged: (() -> Unit)? = null

        private fun preferences(context: Context) = context.getSharedPreferences("proximity_wake", Context.MODE_PRIVATE)
        fun isEnabled(context: Context) = preferences(context).getBoolean(KEY_ENABLED, false)

        @SuppressLint("ImplicitSamInstance") // stopService matches the Intent component, not object/listener identity.
        fun setEnabled(context: Context, enabled: Boolean) {
            preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
            if (enabled) ensureRunning(context)
            else {
                context.stopService(Intent(context, ProximityWakeService::class.java))
                updateStatus(R.string.proximity_wake_off)
            }
        }

        /** Called while Home is visible, including after the default Home starts on boot. */
        fun ensureRunning(context: Context) {
            if (!isEnabled(context)) return
            try {
                if (statusResource != R.string.proximity_wake_running) updateStatus(R.string.proximity_wake_starting)
                context.startForegroundService(Intent(context, ProximityWakeService::class.java))
            } catch (_: RuntimeException) {
                preferences(context).edit().putBoolean(KEY_ENABLED, false).apply()
                updateStatus(R.string.proximity_wake_failed)
            }
        }

        private fun updateStatus(message: Int) {
            statusResource = message
            onChanged?.invoke()
        }
    }
}
