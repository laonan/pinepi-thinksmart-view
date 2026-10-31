package com.pinepi.thinksmart.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import java.util.Calendar
import java.util.TimeZone

/** Compose owns presentation. Existing private message store and foreground services own data/lifecycle. */
class MainActivity : ComponentActivity() {
    private var page by mutableStateOf(HomePage.MESSAGES)
    private var messages by mutableStateOf(emptyList<HomeMessage>())
    private var selectedId by mutableStateOf<String?>(null)
    private var arrivalId by mutableStateOf<String?>(null)
    private var hubStatus by mutableIntStateOf(R.string.hub_pending)
    private var hubs by mutableStateOf(emptyList<DiscoveredHub>())
    private var bluetoothState by mutableStateOf(BluetoothMonitor.State.UNKNOWN)
    private var proximityEnabled by mutableStateOf(false)
    private var mode by mutableStateOf(ThemeMode.AUTO)
    private var dark by mutableStateOf(true)
    private var active by mutableStateOf(false)
    private var storageNotice by mutableIntStateOf(R.string.main_keep_ten)
    private var newestId: String? = null
    private var versionClicks = 0
    private var versionClickTime = 0L
    private var receiverRegistered = false
    private lateinit var store: HomeMessageStore
    private lateinit var bluetooth: BluetoothMonitor
    private lateinit var navigation: SystemNavigation
    private lateinit var images: HomeMediaResources
    private val preferences by lazy { getSharedPreferences("home_display", Context.MODE_PRIVATE) }
    private val timeChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) TimeZone.setDefault(null)
            updateTheme()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LauncherApplication
        store = app.messages
        bluetooth = app.bluetooth
        navigation = SystemNavigation(this)
        images = HomeMediaResources()
        app.messageAudio.beforePlay = { images.stopVideo() }
        messages = store.messages
        newestId = messages.firstOrNull()?.id
        selectedId = savedInstanceState?.getString("selected_id") ?: newestId
        page = savedInstanceState?.getString("page")?.let { name -> HomePage.entries.find { it.name == name } } ?: HomePage.MESSAGES
        mode = ThemeMode.fromSaved(preferences.getString("theme_mode", null))
        updateTheme()
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent {
            BackHandler { openPage(HomePage.MESSAGES) }
            PinePiTheme(dark) {
                HomeApp(
                    page, messages, selectedId, hubStatus, hubs, bluetoothState, proximityEnabled,
                    mode, active, storageNotice, images,
                    arrivalId = arrivalId,
                    onArrivalShown = { id -> if (arrivalId == id) arrivalId = null },
                    onPage = ::openPage,
                    onSelect = { if (selectedId != it) app.messageAudio.stop(); selectedId = it },
                    onTheme = { selected ->
                        mode = selected
                        preferences.edit { putString("theme_mode", selected.name) }
                        updateTheme()
                    },
                    onProximity = { enabled -> ProximityWakeService.setEnabled(this, enabled); refreshWake() },
                    onSettings = { action -> navigation.openSettings(action) },
                    onOtherLauncher = { navigation.openOtherLauncher() },
                    onConfirmHub = { HubTestService.confirm(this, it) },
                    onClear = {
                        app.messageAudio.stop()
                        storageNotice = if (store.clear()) R.string.main_messages_cleared else R.string.main_storage_failed
                    },
                    onVersion = ::tapVersion,
                    voiceVolume = app.messageAudio.minimumVolumeStep,
                    volumeSteps = app.messageAudio.volumeSteps,
                    onVoiceVolume = app.messageAudio::updateMinimumVolume,
                    onSurfaceColors = { background, isDark ->
                        window.setBackgroundDrawable(background.toArgb().toDrawable())
                        window.statusBarColor = background.toArgb()
                        window.navigationBarColor = background.toArgb()
                        immersive(isDark)
                    },
                )
            }
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            (application as LauncherApplication).messageAudio.userAdjustedVolume()
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        active = true
        messages = store.messages
        val newest = messages.firstOrNull()?.id
        if (newest != newestId) { arrivalId = newest; selectedId = newest; page = HomePage.MESSAGES }
        newestId = newest
        store.onChanged = {
            messages = store.messages
            val previousNewest = newestId
            newestId = messages.firstOrNull()?.id
            if (newestId != previousNewest) arrivalId = newestId
            selectedId = newestId
            storageNotice = if (store.saved) R.string.main_keep_ten else R.string.main_storage_failed
            if (!getSystemService(PowerManager::class.java).isInteractive) openPage(HomePage.MESSAGES)
        }
        HubTestState.onChanged = ::refreshHub
        bluetooth.onChanged = { bluetoothState = bluetooth.state }
        ProximityWakeService.onChanged = ::refreshWake
        HubTestService.ensureRunning(this)
        ProximityWakeService.ensureRunning(this)
        bluetooth.refreshPowerState()
        bluetoothState = bluetooth.state
        refreshHub()
        refreshWake()
        updateTheme()
        if (!receiverRegistered) {
            registerReceiver(timeChanges, IntentFilter(Intent.ACTION_TIME_TICK).apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
            })
            receiverRegistered = true
        }
    }
    override fun onPause() {
        // Compose media effects release their resources when active becomes false.
        active = false
        images.pause()
        (application as LauncherApplication).messageAudio.stop()
        store.onChanged = null
        HubTestState.onChanged = null
        bluetooth.onChanged = null
        ProximityWakeService.onChanged = null
        if (receiverRegistered) { unregisterReceiver(timeChanges); receiverRegistered = false }
        super.onPause()
    }
    override fun onDestroy() {
        (application as LauncherApplication).messageAudio.beforePlay = null
        images.close(); super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page.name)
        outState.putString("selected_id", selectedId)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); openPage(HomePage.MESSAGES) }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive(dark) }
    private fun openPage(value: HomePage) {
        if (value != page) (application as LauncherApplication).messageAudio.stop()
        page = value; versionClicks = 0
    }
    private fun refreshHub() { hubStatus = HubTestState.status; hubs = HubTestState.availableHubs }
    private fun refreshWake() { proximityEnabled = ProximityWakeService.isEnabled(this) }
    private fun updateTheme() { dark = mode.isDark(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)); immersive(dark) }
    private fun tapVersion() {
        val now = SystemClock.elapsedRealtime()
        if (now - versionClickTime > 3000) versionClicks = 0
        versionClickTime = now
        if (++versionClicks == 7) {
            versionClicks = 0
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        } else if (versionClicks >= 4) {
            Toast.makeText(this, getString(R.string.main_debug_count, 7 - versionClicks), Toast.LENGTH_SHORT).show()
        }
    }
    @Suppress("DEPRECATION")
    private fun immersive(isDark: Boolean) {
        val contrast = if (isDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or contrast
    }
}
