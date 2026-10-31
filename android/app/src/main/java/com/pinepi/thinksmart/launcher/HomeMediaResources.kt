package com.pinepi.thinksmart.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.VideoView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface HomeImageState {
    data object Loading : HomeImageState
    data object Failed : HomeImageState
    data class Ready(val bitmap: Bitmap) : HomeImageState
}

/** One bounded image request and one native video surface, owned by the visible Activity. */
class HomeMediaResources {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private var closed = false
    var video: VideoView? = null
    var onVideoStopped: (() -> Unit)? = null

    fun loadImage(url: String, result: (HomeImageState) -> Unit): Call? {
        if (!HomeMediaUrl.allowed(url)) { result(HomeImageState.Failed); return null }
        val call = client.newCall(Request.Builder().url(url).build())
        fun fail() { main.post { if (!closed && !call.isCanceled()) result(HomeImageState.Failed) } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { fail() }
            override fun onResponse(call: Call, response: Response) {
                val bitmap = try {
                    response.use {
                        require(it.isSuccessful && it.header("Content-Type", "")!!.startsWith("image/"))
                        val body = it.body ?: error("empty body")
                        require(body.contentLength() <= 8 * 1024 * 1024)
                        val bytes = body.byteStream().use { stream -> stream.readBytesBounded(8 * 1024 * 1024) }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 40000000)
                        var sample = 1
                        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                            ?: error("unsupported image")
                    }
                } catch (_: Exception) { fail(); return }
                main.post {
                    if (!closed && !call.isCanceled()) result(HomeImageState.Ready(bitmap)) else bitmap.recycle()
                }
            }
        })
        return call
    }
    fun pause() { client.dispatcher.cancelAll(); stopVideo() }
    fun stopVideo() {
        val stopped = onVideoStopped
        onVideoStopped = null
        video?.apply {
            (tag as? android.widget.MediaController)?.hide()
            tag = null
            setOnPreparedListener(null)
            setOnCompletionListener(null)
            setOnErrorListener(null)
            stopPlayback()
            setMediaController(null)
        }
        video = null
        stopped?.invoke()
    }
    fun close() {
        closed = true
        pause()
        main.removeCallbacksAndMessages(null)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
