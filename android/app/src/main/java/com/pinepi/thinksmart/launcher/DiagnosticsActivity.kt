package com.pinepi.thinksmart.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView

/** Preserved hardware probes, reached only through the hidden settings entry. */
class DiagnosticsActivity : Activity() {
    private lateinit var systemNavigation: SystemNavigation
    private lateinit var audio: LocalAudioSession
    private lateinit var bluetooth: BluetoothMonitor
    private lateinit var sensors: SensorManager
    private var proximity: Sensor? = null
    private var pendingRecording = false
    private var notice = 0
    private var renderingWake = false
    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            findViewById<TextView>(R.id.proximity_value).text = getString(
                R.string.proximity_value, event.values[0], event.sensor.maximumRange,
            )
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        applyHomeImmersiveMode()
        volumeControlStream = AudioManager.STREAM_MUSIC
        systemNavigation = SystemNavigation(this)
        bluetooth = (application as LauncherApplication).bluetooth
        audio = LocalAudioSession(this) { renderAudio() }
        findViewById<RadioGroup>(R.id.input_source).setOnCheckedChangeListener { _, _ ->
            pendingRecording = false
            notice = 0
            audio.cancel()
        }
        findViewById<View>(R.id.speaker_test).setOnClickListener {
            notice = 0
            bluetooth.refreshPowerState()
            if (bluetooth.state.allowsAudio) audio.playTestTone()
            else showPlaybackBlocked()
            renderAudio()
        }
        sensors = getSystemService(SensorManager::class.java)
        proximity = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
            ?: sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        findViewById<CheckBox>(R.id.proximity_wake_enabled).setOnCheckedChangeListener { _, enabled ->
            if (!renderingWake) ProximityWakeService.setEnabled(this, enabled)
        }
        renderProximityWake()

        findViewById<View>(R.id.record_start).setOnClickListener { beginRecording() }
        findViewById<View>(R.id.record_finish).setOnClickListener {
            notice = 0
            audio.finishRecording()
        }
        findViewById<View>(R.id.audio_cancel).setOnClickListener {
            pendingRecording = false
            notice = 0
            audio.cancel()
        }
        findViewById<View>(R.id.audio_play).setOnClickListener {
            notice = 0
            if (audio.state == LocalAudioSession.State.PLAYING) {
                audio.stopPlayback()
            } else {
                bluetooth.refreshPowerState()
                if (bluetooth.state.allowsAudio) audio.play(findViewById<CheckBox>(R.id.enhance_playback).isChecked)
                else showPlaybackBlocked()
            }
            renderAudio()
        }

        findViewById<View>(R.id.wifi_settings).setOnClickListener {
            systemNavigation.openSettings(Settings.ACTION_WIFI_SETTINGS)
        }
        findViewById<View>(R.id.bluetooth_settings).setOnClickListener {
            systemNavigation.openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        findViewById<View>(R.id.system_settings).setOnClickListener {
            systemNavigation.openSettings(Settings.ACTION_SETTINGS)
        }
        findViewById<View>(R.id.home_settings).setOnClickListener {
            systemNavigation.openSettings(Settings.ACTION_HOME_SETTINGS)
        }
        findViewById<View>(R.id.maintenance_launcher).setOnClickListener {
            systemNavigation.openOtherLauncher()
        }

        findViewById<TextView>(R.id.device_baseline).text = getString(
            R.string.device_baseline,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
            Build.DISPLAY,
        )
        renderBluetooth()
        renderAudio()
        findViewById<View>(R.id.os_warning).visibility =
            if (Build.VERSION.SDK_INT == 27 && Build.VERSION.RELEASE == "8.1.0") {
                View.GONE
            } else {
                View.VISIBLE
            }
    }

    override fun onResume() {
        super.onResume()
        // Restore before service/sensor work, rather than waiting for window focus after wake.
        applyHomeImmersiveMode()
        HubTestState.onChanged = { renderHub() }
        HubTestService.ensureRunning(this)
        renderHub()
        ProximityWakeService.onChanged = { renderProximityWake() }
        ProximityWakeService.ensureRunning(this)
        renderProximityWake()
        bluetooth.onChanged = {
            renderBluetooth()
            if (!bluetooth.state.allowsAudio && audio.active) {
                notice = R.string.bluetooth_interrupted
                audio.cancel()
            }
            if (pendingRecording && bluetooth.state.allowsAudio) beginRecording()
            renderAudio()
        }
        bluetooth.refreshPowerState()
        renderBluetooth()
        val sensor = proximity
        findViewById<TextView>(R.id.proximity_value).setText(R.string.proximity_wait)
        if (sensor == null) {
            findViewById<TextView>(R.id.proximity_info).setText(R.string.proximity_missing)
        } else {
            val listening = sensors.registerListener(proximityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            findViewById<TextView>(R.id.proximity_info).text = getString(
                R.string.proximity_info, sensor.name,
                getString(if (sensor.isWakeUpSensor) R.string.yes else R.string.no),
                getString(if (listening) R.string.yes else R.string.no),
            )
        }
        findViewById<TextView>(R.id.home_status).setText(
            if (systemNavigation.isDefaultHome()) R.string.home_is_default
            else R.string.home_not_default
        )
    }

    override fun onPause() {
        // Never continue capturing or playing after leaving the foreground probe.
        pendingRecording = false
        audio.cancel()
        bluetooth.onChanged = null
        ProximityWakeService.onChanged = null
        HubTestState.onChanged = null
        sensors.unregisterListener(proximityListener)
        super.onPause()
    }

    override fun onDestroy() {
        audio.close()
        super.onDestroy()
    }

    private fun beginRecording() {
        if (audio.active) return
        notice = 0
        bluetooth.refreshPowerState()
        if (!bluetooth.state.allowsAudio) {
            pendingRecording = true
            renderAudio()
            return
        }
        pendingRecording = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_PERMISSION)
        } else {
            val source = if (findViewById<RadioGroup>(R.id.input_source).checkedRadioButtonId == R.id.input_speech) {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            } else MediaRecorder.AudioSource.MIC
            audio.startRecording(source)
        }
        renderAudio()
    }

    private fun renderProximityWake() {
        renderingWake = true
        findViewById<CheckBox>(R.id.proximity_wake_enabled).isChecked = ProximityWakeService.isEnabled(this)
        renderingWake = false
        findViewById<TextView>(R.id.proximity_wake_status).setText(ProximityWakeService.statusResource)
    }

    private fun renderHub() {
        val list = findViewById<android.widget.LinearLayout>(R.id.hub_candidates)
        list.removeAllViews()
        for (hub in HubTestState.availableHubs) {
            list.addView(android.widget.Button(this).apply {
                text = getString(R.string.hub_confirm, hub.name, hub.host, hub.port)
                isAllCaps = false
                setOnClickListener { HubTestService.confirm(this@DiagnosticsActivity, hub.id) }
            })
        }
        findViewById<TextView>(R.id.hub_status).setText(HubTestState.status)
        findViewById<TextView>(R.id.hub_latest_message).text =
            HubTestState.latestText ?: getString(R.string.hub_test_no_message)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MICROPHONE_PERMISSION) {
            // Granting permission does not itself start the microphone.
            notice = if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                R.string.audio_permission_granted
            } else R.string.audio_permission_required
            renderAudio()
        }
    }

    private fun renderBluetooth() {
        val description = when (bluetooth.state) {
            BluetoothMonitor.State.UNKNOWN -> R.string.bluetooth_unknown
            BluetoothMonitor.State.OFF -> R.string.bluetooth_off
            BluetoothMonitor.State.DISCONNECTED -> R.string.bluetooth_disconnected
            BluetoothMonitor.State.CONNECTED -> R.string.bluetooth_connected
            BluetoothMonitor.State.CONNECTING -> R.string.bluetooth_connecting
            BluetoothMonitor.State.UNAVAILABLE -> R.string.bluetooth_unavailable
        }
        findViewById<TextView>(R.id.bluetooth_status).setText(description)
        findViewById<TextView>(R.id.audio_bluetooth_gate).setText(description)
    }

    private fun showPlaybackBlocked() {
        notice = R.string.playback_bluetooth_blocked
        renderBluetooth()
        AlertDialog.Builder(this)
            .setTitle(R.string.playback_not_started)
            .setMessage(R.string.playback_bluetooth_blocked)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun renderAudio() {
        findViewById<TextView>(R.id.audio_status).setText(
            if (pendingRecording) R.string.bluetooth_wait else audio.message
        )
        findViewById<TextView>(R.id.audio_notice).apply {
            visibility = if (notice == 0) View.GONE else View.VISIBLE
            if (notice != 0) setText(notice)
        }
        findViewById<TextView>(R.id.audio_metrics).text = getString(
            R.string.audio_metrics, audio.elapsedMs / 1000f, audio.peakPercent, audio.averageDbFs,
        )
        findViewById<ProgressBar>(R.id.audio_level).progress = audio.peakPercent.toInt()
        findViewById<View>(R.id.input_mic).isEnabled = !audio.active && !pendingRecording
        findViewById<View>(R.id.input_speech).isEnabled = !audio.active && !pendingRecording
        findViewById<View>(R.id.speaker_test).isEnabled = !audio.active && !pendingRecording
        findViewById<View>(R.id.enhance_playback).isEnabled = !audio.active && !pendingRecording
        findViewById<TextView>(R.id.enhance_status).setText(audio.enhancementStatus)
        renderAudioDiagnostics()
        findViewById<Button>(R.id.record_start).apply {
            isEnabled = !audio.active && !pendingRecording
            setText(if (audio.state == LocalAudioSession.State.READY) R.string.record_again else R.string.record_start)
        }
        findViewById<View>(R.id.record_finish).isEnabled = audio.state == LocalAudioSession.State.RECORDING
        findViewById<Button>(R.id.audio_play).apply {
            isEnabled = audio.state == LocalAudioSession.State.READY || audio.state == LocalAudioSession.State.PLAYING
            setText(if (audio.state == LocalAudioSession.State.PLAYING) R.string.play_stop else R.string.audio_play)
        }
        findViewById<View>(R.id.audio_cancel).isEnabled = pendingRecording || audio.state != LocalAudioSession.State.EMPTY
        if (audio.active || pendingRecording) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun renderAudioDiagnostics() {
        val manager = getSystemService(AudioManager::class.java)
        findViewById<TextView>(R.id.audio_device_status).text = getString(
            R.string.audio_device_status, manager.getStreamVolume(AudioManager.STREAM_MUSIC),
            manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            getString(if (manager.isMicrophoneMute) R.string.yes else R.string.no),
        )
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val handled = super.onKeyUp(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            window.decorView.postDelayed({ if (!isDestroyed) renderAudioDiagnostics() }, 200)
        }
        return handled
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyHomeImmersiveMode()
    }

    @Suppress("DEPRECATION") // API 27 window-local immersive mode; edge swipe still reveals system navigation.
    private fun applyHomeImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    @Deprecated("Android 8.1 Home intentionally stays visible when Back is pressed")
    override fun onBackPressed() {
        pendingRecording = false
        notice = 0
        audio.cancel()
        finish()
    }

    companion object {
        private const val MICROPHONE_PERMISSION = 1
    }
}
