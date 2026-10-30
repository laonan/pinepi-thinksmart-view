package com.pinepi.thinksmart.launcher

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * API 27 public broadcasts report aggregate profile connections, including A2DP sink.
 * They are NOT sticky: a new process must not assume that Bluetooth is disconnected.
 * No hidden sink profile proxy, reflection, scanning, or forced disconnection is used.
 */
@SuppressLint("MissingPermission") // BLUETOOTH is a normal install-time permission on API 27.
class BluetoothMonitor(private val context: Context) {
    enum class State {
        UNKNOWN, OFF, DISCONNECTED, CONNECTING, CONNECTED, UNAVAILABLE;

        val allowsAudio: Boolean
            get() = this == OFF || this == DISCONNECTED || this == UNAVAILABLE
    }

    var state = State.UNKNOWN
        private set
    var onChanged: (() -> Unit)? = null
    private var registered = false
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val next = when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                ) {
                    BluetoothAdapter.STATE_OFF -> State.OFF
                    else -> State.UNKNOWN
                }
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> when (
                    intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, BluetoothAdapter.ERROR)
                ) {
                    BluetoothAdapter.STATE_DISCONNECTED -> State.DISCONNECTED
                    BluetoothAdapter.STATE_CONNECTED -> State.CONNECTED
                    BluetoothAdapter.STATE_CONNECTING, BluetoothAdapter.STATE_DISCONNECTING -> State.CONNECTING
                    else -> State.UNKNOWN
                }
                else -> return
            }
            state = next
            refreshPowerState()
            onChanged?.invoke()
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        registered = true
        refreshPowerState()
    }

    /** Does not replace unknown with disconnected when the adapter is on. */
    fun refreshPowerState() {
        state = try {
            when {
                adapter == null -> State.UNAVAILABLE
                adapter.state == BluetoothAdapter.STATE_OFF -> State.OFF
                adapter.state != BluetoothAdapter.STATE_ON -> State.UNKNOWN
                state == State.OFF || state == State.UNAVAILABLE -> State.UNKNOWN
                else -> state
            }
        } catch (_: SecurityException) {
            State.UNKNOWN
        }
    }
}
