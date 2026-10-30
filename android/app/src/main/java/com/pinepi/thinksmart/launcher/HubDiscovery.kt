package com.pinepi.thinksmart.launcher

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.util.UUID

/** Local identity is only for remembering consent, not an authenticated identity. */
data class DiscoveredHub(val id: String, val name: String, val host: String, val port: Int) {
    val url get() = "ws://$host:$port/_test/device"
    companion object {
        fun validated(id: String?, name: String, host: String?, port: Int, version: String?, path: String?, auth: String?): DiscoveredHub? {
            if (id == null || host == null || !LocalHubAddress.isPrivateIpv4(host) || port !in 1..65535 ||
                version != "1" || path != "/_test/device" || auth != "none") return null
            val normalized = try { UUID.fromString(id).toString() } catch (_: IllegalArgumentException) { return null }
            if (normalized != id) return null
            return DiscoveredHub(id, name.take(100), host, port)
        }
    }
}

/** Only an explicitly selected Hub is eligible for connection. */
object HubConsent {
    fun eligible(hubs: List<DiscoveredHub>, confirmedId: String?) = hubs.filter { it.id == confirmedId }
}

/** API 27 allows only one resolve operation at a time. Late callbacks never revive a stopped scan. */
class HubDiscovery(context: Context, private val changed: (List<DiscoveredHub>) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val found = linkedMapOf<String, DiscoveredHub>()
    private var resolving = false
    private var closed = false
    private var epoch = 0
    private val rescan = Runnable { restart() }

    fun start() { if (!closed && listener == null) scan() }

    private fun scan() {
        val cycle = ++epoch
        val next = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                main.post { if (current(cycle)) restartLater() }
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    if (!current(cycle) || !info.serviceType.trimEnd('.').endsWith("_pinepi-hub._tcp")) return@post
                    if (pending.size < 32) pending.addLast(info)
                    resolveNext(cycle)
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (current(cycle)) {
                        found.remove(info.serviceName)
                        changed(found.values.toList())
                    }
                }
            }
        }
        listener = next
        try {
            nsd.discoverServices("_pinepi-hub._tcp.", NsdManager.PROTOCOL_DNS_SD, next)
            main.postDelayed(rescan, 30000)
        } catch (_: RuntimeException) { restartLater() }
    }

    private fun current(cycle: Int) = !closed && epoch == cycle
    private fun restartLater() {
        main.removeCallbacks(rescan)
        main.postDelayed(rescan, 5000)
    }
    private fun resolveNext(cycle: Int) {
        if (!current(cycle) || resolving || pending.isEmpty()) return
        val info = pending.removeFirst()
        resolving = true
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(service: NsdServiceInfo, error: Int) {
                    main.post { if (current(cycle)) { resolving = false; resolveNext(cycle) } }
                }
                override fun onServiceResolved(service: NsdServiceInfo) {
                    main.post {
                        if (!current(cycle)) return@post
                        resolving = false
                        fun attr(key: String) = service.attributes[key]?.toString(Charsets.UTF_8)
                        val hub = DiscoveredHub.validated(attr("id"), service.serviceName, service.host?.hostAddress,
                            service.port, attr("v"), attr("path"), attr("auth"))
                        if (hub != null) found[service.serviceName] = hub
                        changed(found.values.toList())
                        resolveNext(cycle)
                    }
                }
            })
        } catch (_: RuntimeException) { resolving = false; restartLater() }
    }

    fun restart() {
        if (closed) return
        stopScan()
        // Resolved addresses expire with each scan; never reconnect indefinitely to a stale IP.
        found.clear()
        changed(emptyList())
        scan()
    }
    private fun stopScan() {
        epoch++
        main.removeCallbacks(rescan)
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: RuntimeException) {} }
        listener = null
        pending.clear()
        resolving = false
    }
    fun close() { closed = true; stopScan(); found.clear(); main.removeCallbacksAndMessages(null) }
}
