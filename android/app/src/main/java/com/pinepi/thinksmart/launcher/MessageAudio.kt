package com.pinepi.thinksmart.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One cancelable message sound at a time. No speech provider or text upload on the terminal. */
class MessageAudio(private val context: Context, private val bluetooth: BluetoothMonitor) {
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(AudioManager::class.java)
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) stop()
        }.build()
    private var player: MediaPlayer? = null
    private var call: Call? = null
    private var cached: File? = null
    private var generation = 0
    var suspended = false
        set(value) { field = value; if (value) stop() }
    var beforePlay: (() -> Unit)? = null
    var currentId by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    private fun allowed() = !suspended && bluetooth.state.allowsAudio

    fun gateChanged() { if (!allowed()) stop() }

    fun incoming(message: HomeMessage) {
        // Latest new arrival replaces current sound; there is no delayed autoplay queue.
        stop()
        if (message.version != 2 || !allowed()) return
        if (message.kind == HomeMessage.Kind.TEXT && message.textMode == HomeMessage.TextMode.TTS) {
            remote(message.id, message.speechUrl) { local("notice_text") }
        } else local(when (message.kind) {
            HomeMessage.Kind.TEXT -> "notice_text"
            HomeMessage.Kind.IMAGE -> "notice_image"
            HomeMessage.Kind.AUDIO -> "notice_audio"
            HomeMessage.Kind.VIDEO -> "notice_video"
        })
    }

    fun play(message: HomeMessage) {
        stop()
        if (!allowed()) { lastError = "请先断开蓝牙并结束录音"; return }
        val url = if (message.kind == HomeMessage.Kind.TEXT && message.textMode == HomeMessage.TextMode.TTS)
            message.speechUrl else if (message.kind == HomeMessage.Kind.AUDIO) message.mediaUrl else return
        remote(message.id, url)
    }

    private fun local(name: String) {
        if (!allowed()) return
        val resource = when (name) {
            "notice_text" -> R.raw.notice_text
            "notice_image" -> R.raw.notice_image
            "notice_audio" -> R.raw.notice_audio
            "notice_video" -> R.raw.notice_video
            else -> return
        }
        try {
            context.resources.openRawResourceFd(resource).use { descriptor ->
                val next = MediaPlayer()
                player = next
                next.setAudioAttributes(attributes)
                next.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                prepare(next, generation, null)
            }
        } catch (_: Exception) { stop(); lastError = "提示音播放失败" }
    }

    private fun remote(id: String, url: String, fallback: (() -> Unit)? = null) {
        if (!allowed() || !HomeMediaUrl.allowed(url)) return
        beforePlay?.invoke()
        currentId = id
        loading = true
        lastError = null
        val token = generation
        val request = client.newCall(Request.Builder().url(url).build())
        call = request
        fun fail() { main.post {
            if (token == generation && !request.isCanceled()) {
                stop(); lastError = "音频下载或播放失败"; if (allowed()) fallback?.invoke()
            }
        } }
        request.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { fail() }
            override fun onResponse(call: Call, response: Response) {
                var file: File? = null
                try {
                    file = File.createTempFile("message-audio-", ".mp3", context.cacheDir)
                    response.use {
                        require(it.isSuccessful && it.header("Content-Type", "")!!.substringBefore(';').trim() == "audio/mpeg")
                        val body = it.body ?: error("empty audio")
                        require(body.contentLength() <= MAX_BYTES)
                        var total = 0
                        body.byteStream().use { input -> file.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                if (call.isCanceled()) throw IOException("canceled")
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_BYTES)
                                output.write(buffer, 0, count)
                            }
                        } }
                        require(total > 0)
                    }
                    val downloaded = file
                    main.post {
                        if (token != generation || request.isCanceled() || !allowed()) {
                            downloaded.delete(); return@post
                        }
                        cached = downloaded
                        try {
                            val next = MediaPlayer()
                            player = next
                            next.setAudioAttributes(attributes)
                            next.setDataSource(downloaded.absolutePath)
                            prepare(next, token, fallback)
                        } catch (_: Exception) { stop(); lastError = "音频播放失败"; if (allowed()) fallback?.invoke() }
                    }
                } catch (_: Exception) { file?.delete(); fail() }
            }
        })
    }

    private fun prepare(next: MediaPlayer, token: Int, fallback: (() -> Unit)?) {
        beforePlay?.invoke()
        next.setOnPreparedListener {
            if (generation != token || player !== next || !allowed()) return@setOnPreparedListener
            if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { stop(); return@setOnPreparedListener }
            loading = false
            try { next.start() } catch (_: Exception) { stop(); lastError = "音频播放失败" }
        }
        next.setOnCompletionListener { if (generation == token && player === next) stop() }
        next.setOnErrorListener { _, _, _ ->
            if (generation == token && player === next) { stop(); lastError = "音频播放失败"; if (allowed()) fallback?.invoke() }
            true
        }
        next.prepareAsync()
    }

    fun stop() {
        generation++
        call?.cancel(); call = null
        player?.apply {
            setOnPreparedListener(null); setOnCompletionListener(null); setOnErrorListener(null)
            release()
        }
        player = null
        manager.abandonAudioFocusRequest(focus)
        cached?.delete(); cached = null
        currentId = null
        loading = false
    }

    companion object { private const val MAX_BYTES = 16 * 1024 * 1024 }
}
