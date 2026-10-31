package com.pinepi.thinksmart.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Discovered LAN message transport. Persists records before ACK; media travels by URL. */
class HubTestService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val ids = RecentMessageIds()
    private var client: OkHttpClient? = null
    private var socket: WebSocket? = null
    private lateinit var discovery: HubDiscovery
    private var candidates = emptyList<DiscoveredHub>()
    private val preferences by lazy { getSharedPreferences("hub_discovery", Context.MODE_PRIVATE) }
    private var nextEndpoint = 0
    private var generation = 0
    private var stopped = false
    private var retrySeconds = 1L
    private lateinit var networks: ConnectivityManager
    private val retry = Runnable { connect() }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post {
                if (!stopped && socket == null) {
                    main.removeCallbacks(retry)
                    retrySeconds = 1
                    discovery.restart()
                }
            }
        }
        override fun onLost(network: Network) {
            main.post {
                if (stopped) return@post
                generation++
                socket?.cancel()
                socket = null
                main.removeCallbacks(retry)
                HubTestState.status(R.string.hub_test_reconnecting)
                main.postDelayed(retry, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            "hub_link_test", getString(R.string.hub_test_channel), NotificationManager.IMPORTANCE_LOW,
        ).apply { setSound(null, null); enableVibration(false) })
        val home = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(20, Notification.Builder(this, "hub_link_test")
            .setSmallIcon(R.drawable.ic_proximity_wake).setOngoing(true).setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.hub_test_notification)).setContentIntent(home)
            .setContentText(getString(R.string.hub_test_notification_body)).build())
        discovery = HubDiscovery(this) { hubs ->
            candidates = hubs
            publishCandidates()
            if (socket == null) connect()
        }
        networks = getSystemService(ConnectivityManager::class.java)
        networks.registerDefaultNetworkCallback(callback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (client == null) {
            client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS).pingInterval(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
            HubTestState.status(R.string.hub_discovering)
            discovery.start()
        }
        if (intent?.action == ACTION_CONFIRM) {
            val id = intent.getStringExtra("hub_id")
            if (candidates.any { it.id == id }) {
                // Persist consent before attempting any connection.
                if (preferences.edit().putString("selected_id", id).commit()) {
                    generation++
                    socket?.cancel()
                    socket = null
                    retrySeconds = 1
                    publishCandidates()
                    connect()
                }
            }
        }
        return START_STICKY
    }

    private fun confirmedId(): String? = preferences.getString("selected_id", null)
        // Preserve the one confirmation made during initial 0.5.0 device validation.
        ?: preferences.getStringSet("confirmed_ids", emptySet())!!.singleOrNull()

    private fun publishCandidates() {
        HubTestState.candidates(candidates.filter { it.id != confirmedId() }.distinctBy { it.id })
    }

    private fun connect() {
        if (stopped || socket != null) return
        val available = HubConsent.eligible(candidates, confirmedId())
        if (available.isEmpty()) {
            HubTestState.status(if (candidates.isEmpty()) R.string.hub_discovering else R.string.hub_confirmation_required)
            return
        }
        main.removeCallbacks(retry)
        val settings = available[nextEndpoint++ % available.size]
        val token = ++generation
        HubTestState.status(R.string.hub_test_connecting)
        val request = Request.Builder().url(settings.url).build()
        socket = client!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (!current(token, webSocket)) return@post
                    retrySeconds = 1
                    HubTestState.status(R.string.hub_test_connected)
                    Log.i(TAG, "Gateway test connected")
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { if (current(token, webSocket)) receive(webSocket, text) }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // The message protocol accepts text JSON only. Media is fetched from validated URLs.
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { lost(token, webSocket, false) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val rejected = response?.code == 401 || response?.code == 403
                response?.close()
                main.post { lost(token, webSocket, rejected) }
            }
        })
    }

    private fun current(token: Int, ws: WebSocket) = !stopped && generation == token && socket === ws

    private fun lost(token: Int, ws: WebSocket, rejected: Boolean) {
        if (!current(token, ws)) return
        socket = null
        generation++
        main.removeCallbacks(retry)
        if (rejected) {
            HubTestState.status(R.string.hub_test_auth_failed)
            return
        }
        HubTestState.status(R.string.hub_test_reconnecting)
        main.postDelayed(retry, retrySeconds * 1000)
        retrySeconds = minOf(retrySeconds * 2, 30)
    }

    private fun receive(ws: WebSocket, text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > 64 * 1024) return
        try {
            val json = JSONObject(text)
            val notification = json.opt("type") == "home.message"
            if (!notification && json.opt("type") != "test.message") return
            val message = if (notification && json.opt("version") == 2) {
                HomeMessageProtocol.decode(json) ?: return
            } else {
                if (json.opt("version") != 1 || (notification && json.opt("kind") != "TEXT")) return
                val id = json.opt("id") as? String ?: return
                val content = json.opt("text") as? String ?: return
                if (content.isBlank() || content.toByteArray(Charsets.UTF_8).size > (if (notification) 32 * 1024 else 2048)) return
                val title = if (notification) json.opt("title") as? String ?: return else ""
                val source = if (notification) json.opt("source") as? String ?: return else getString(R.string.hub_message_source)
                HomeMessage(id, HomeMessage.Kind.TEXT, source, System.currentTimeMillis(), title = title, text = content)
            }
            if (!message.isValid()) return
            val id = message.id
            val store = (application as LauncherApplication).messages
            fun acknowledge(wake: Boolean) {
                val ack = JSONObject().put("type", if (notification) "home.ack" else "test.ack").put("id", id)
                    .put("wake_requested", wake)
                if (message.version == 2) ack.put("version", 2)
                ws.send(ack.toString())
            }
            if (notification && store.contains(id)) {
                if (store.saved || store.flush()) {
                    acknowledge(false)
                }
                return
            }
            if (!notification && !ids.accept(id)) return
            if (!store.add(message)) return
            HubTestState.received(if (message.kind == HomeMessage.Kind.TEXT) message.text else message.title.ifBlank { message.kind.name })
            val result = DisplayWake.request(this, "HubMessage")
            // Notification ACK means the local record was saved, not merely received in RAM.
            if (!notification || store.saved) {
                acknowledge(result == DisplayWake.Result.REQUESTED)
            }
            if (store.saved) (application as LauncherApplication).messageAudio.incoming(message)
            Log.i(TAG, "Hub message accepted; wake=$result; count=${HubTestState.acceptedCount}")
        } catch (_: RuntimeException) {
            // Unrecognized frames never trigger sound, recording or a wake request.
        } catch (_: org.json.JSONException) {
            // Malformed messages are ignored; never log payloads or credentials.
        }
    }

    override fun onDestroy() {
        stopped = true
        generation++
        main.removeCallbacksAndMessages(null)
        networks.unregisterNetworkCallback(callback)
        socket?.cancel()
        socket = null
        discovery.close()
        HubTestState.candidates(emptyList())
        client?.dispatcher?.executorService?.shutdown()
        client?.connectionPool?.evictAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PinePiHub"
        private const val ACTION_CONFIRM = "com.pinepi.thinksmart.CONFIRM_HUB"
        fun ensureRunning(context: Context) {
            context.startForegroundService(Intent(context, HubTestService::class.java))
        }
        fun confirm(context: Context, hubId: String) {
            context.startForegroundService(Intent(context, HubTestService::class.java)
                .setAction(ACTION_CONFIRM).putExtra("hub_id", hubId))
        }
    }
}
