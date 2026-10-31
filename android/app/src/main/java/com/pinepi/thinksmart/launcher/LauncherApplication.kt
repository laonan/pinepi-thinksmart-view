package com.pinepi.thinksmart.launcher

import android.app.Application

class LauncherApplication : Application() {
    lateinit var bluetooth: BluetoothMonitor
        private set

    lateinit var messages: HomeMessageStore
        private set
    lateinit var messageAudio: MessageAudio
        private set

    override fun onCreate() {
        super.onCreate()
        // Remove only this probe's private leftovers after a previous process crash.
        cacheDir.listFiles()?.filter { it.name.startsWith("local-audio-") && it.extension == "wav" }
            ?.forEach { it.delete() }
        messages = HomeMessageStore(this)
        bluetooth = BluetoothMonitor(this)
        cacheDir.listFiles()?.filter { it.name.startsWith("message-audio-") }?.forEach { it.delete() }
        messageAudio = MessageAudio(this, bluetooth)
        bluetooth.onAudioGateChanged = { messageAudio.gateChanged() }
        // Application lifetime preserves events while visiting system settings.
        bluetooth.start()
    }
}
