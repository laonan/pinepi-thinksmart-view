package com.pinepi.thinksmart.launcher

import android.app.Application

class LauncherApplication : Application() {
    lateinit var bluetooth: BluetoothMonitor
        private set

    lateinit var messages: HomeMessageStore
        private set

    override fun onCreate() {
        super.onCreate()
        // Remove only this probe's private leftovers after a previous process crash.
        cacheDir.listFiles()?.filter { it.name.startsWith("local-audio-") && it.extension == "wav" }
            ?.forEach { it.delete() }
        messages = HomeMessageStore(this)
        bluetooth = BluetoothMonitor(this)
        // Application lifetime preserves events while visiting system settings.
        bluetooth.start()
    }
}
