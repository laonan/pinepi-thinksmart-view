package com.pinepi.thinksmart.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Foreground-only local probe. Call public methods on the main thread. */
class LocalAudioSession(private val context: Context, private val onChanged: () -> Unit) {
    enum class State { EMPTY, STARTING, RECORDING, STOPPING, READY, PLAYING }

    var state = State.EMPTY
        private set
    var elapsedMs = 0L
        private set
    var peakPercent = 0f
        private set
    var averageDbFs = -96.0
        private set
    var message: Int = R.string.audio_idle
        private set
    var enhancementStatus = R.string.enhance_not_used
        private set
    private var clip: File? = null
    private var player: MediaPlayer? = null
    private var testTone: File? = null
    private var playingTone = false
    private var enhancer: LoudnessEnhancer? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var running = AtomicBoolean(false)
    private var discard = AtomicBoolean(false)
    @Volatile private var closed = false
    private var generation = 0
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    val active: Boolean
        get() = state == State.STARTING || state == State.RECORDING || state == State.STOPPING || state == State.PLAYING

    fun startRecording(source: Int) {
        if (closed || active) return
        require(source == MediaRecorder.AudioSource.MIC || source == MediaRecorder.AudioSource.VOICE_RECOGNITION)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message = R.string.audio_permission_required
            onChanged()
            return
        }
        clearClip()
        elapsedMs = 0
        peakPercent = 0f
        averageDbFs = -96.0
        enhancementStatus = R.string.enhance_not_used
        message = R.string.audio_warming
        state = State.STARTING
        val runFlag = AtomicBoolean(true).also { running = it }
        val discardFlag = AtomicBoolean(false).also { discard = it }
        val token = ++generation
        onChanged()
        worker.execute { capture(token, runFlag, discardFlag, source) }
    }

    fun finishRecording() {
        if (state != State.RECORDING) return
        state = State.STOPPING
        message = R.string.audio_stopping
        running.set(false)
        onChanged()
    }

    private fun capture(token: Int, runFlag: AtomicBoolean, discardFlag: AtomicBoolean, source: Int) {
        var file: File? = null
        var recorder: AudioRecord? = null
        var pcmBytes = 0
        var failure = false
        val meter = PcmLevelMeter(skipSamples = 0)
        val startup = PcmStartGuard()
        var announcedReady = false
        try {
            val minimum = AudioRecord.getMinBufferSize(
                WavFile.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            check(minimum > 0)
            // Recheck permission on the worker before acquiring the microphone.
            check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            if (!runFlag.get() || discardFlag.get() || closed) return
            recorder = AudioRecord(
                source, WavFile.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum * 2, 6400),
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            file = File.createTempFile("local-audio-", ".wav", context.cacheDir)
            RandomAccessFile(file, "rw").use { output ->
                output.write(ByteArray(44))
                if (!runFlag.get() || discardFlag.get() || closed) return@use
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                val samples = ShortArray(1600)
                val bytes = ByteArray(samples.size * 2)
                val started = SystemClock.elapsedRealtime()
                var lastProgress = 0L
                val maxSamples = WavFile.SAMPLE_RATE * MAX_SECONDS
                while (runFlag.get() && !closed && pcmBytes / 2 < maxSamples &&
                    SystemClock.elapsedRealtime() - started < MAX_SECONDS * 1000L) {
                    val read = recorder.read(samples, 0, minOf(samples.size, maxSamples - pcmBytes / 2), AudioRecord.READ_NON_BLOCKING)
                    check(read >= 0)
                    if (read == 0) {
                        Thread.sleep(10)
                        continue
                    }
                    val count = startup.process(samples, read)
                    if (count == 0) continue
                    if (!announcedReady) {
                        announcedReady = true
                        main.post {
                            if (!closed && token == generation && state == State.STARTING && runFlag.get()) {
                                state = State.RECORDING
                                message = R.string.audio_recording
                                onChanged()
                            }
                        }
                    }
                    var peak = 0
                    for (index in 0 until count) {
                        val value = samples[index].toInt()
                        bytes[index * 2] = value.toByte()
                        bytes[index * 2 + 1] = (value shr 8).toByte()
                        peak = maxOf(peak, abs(value))
                    }
                    output.write(bytes, 0, count * 2)
                    pcmBytes += count * 2
                    meter.add(samples, count)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgress >= 150) {
                        lastProgress = now
                        val duration = pcmBytes * 1000L / (WavFile.SAMPLE_RATE * 2)
                        val level = peak * 100f / 32768
                        val average = meter.dbFs
                        main.post {
                            if (!closed && token == generation && state == State.RECORDING) {
                                elapsedMs = duration
                                peakPercent = level
                                averageDbFs = average
                                onChanged()
                            }
                        }
                    }
                }
                if (!discardFlag.get() && !closed) {
                    check(pcmBytes > 0)
                    WavFile.fadeTail(output, pcmBytes)
                    WavFile.writeHeader(output, pcmBytes)
                }
            }
        } catch (_: Exception) {
            // Never log raw audio or device/user content.
            failure = true
        } finally {
            runFlag.set(false)
            try {
                if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } catch (_: IllegalStateException) {
                failure = true
            } finally {
                try {
                    recorder?.release()
                } catch (_: RuntimeException) {
                    failure = true
                }
            }
            val result = file
            val failed = failure
            val duration = pcmBytes * 1000L / (WavFile.SAMPLE_RATE * 2)
            val average = meter.dbFs
            main.post {
                if (closed || token != generation || discardFlag.get() || failed || pcmBytes == 0) {
                    result?.delete()
                    if (!closed && token == generation) {
                        state = State.EMPTY
                        elapsedMs = 0
                        peakPercent = 0f
                        averageDbFs = -96.0
                        message = if (failed && !discardFlag.get()) R.string.audio_failed else R.string.audio_cleared
                        onChanged()
                    }
                } else {
                    clip = result
                    elapsedMs = duration
                    peakPercent = 0f
                    averageDbFs = average
                    state = State.READY
                    message = if (average < -45.0) R.string.audio_ready_quiet else R.string.audio_ready
                    onChanged()
                }
            }
        }
    }

    fun play(enhance: Boolean) {
        val file = clip ?: return
        if (closed || state != State.READY) return
        playFile(file, false, enhance)
    }

    fun playTestTone() {
        if (closed || active) return
        try {
            val file = File.createTempFile("local-audio-tone-", ".wav", context.cacheDir)
            testTone = file
            val pcm = SpeakerTestSignal.pcm()
            RandomAccessFile(file, "rw").use { output ->
                WavFile.writeHeader(output, pcm.size)
                output.write(pcm)
            }
            playFile(file, true)
        } catch (_: Exception) {
            releasePlayer()
            message = R.string.audio_play_failed
            onChanged()
        }
    }

    private fun playFile(file: File, isTone: Boolean, enhance: Boolean = false) {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    stopPlayback()
                }
            }, main)
            .build()
        focus = request
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            releasePlayer()
            message = R.string.audio_focus_denied
            onChanged()
            return
        }
        state = State.PLAYING
        playingTone = isTone
        enhancementStatus = R.string.enhance_off
        message = if (isTone) R.string.tone_playing else R.string.audio_playing
        try {
            val next = MediaPlayer()
            player = next
            next.setAudioAttributes(attributes)
            next.setDataSource(file.absolutePath)
            next.setOnPreparedListener {
                if (player === it && !closed) {
                    if (enhance && !isTone) {
                        try {
                            // Never attach to global session 0 or another application's session.
                            check(it.audioSessionId > 0)
                            val effect = LoudnessEnhancer(it.audioSessionId)
                            enhancer = effect
                            effect.setTargetGain(2400) // millibels: maximum +24 dB.
                            check(effect.setEnabled(true) == AudioEffect.SUCCESS)
                            enhancementStatus = R.string.enhance_active
                        } catch (_: RuntimeException) {
                            releaseEnhancer()
                            enhancementStatus = R.string.enhance_unavailable
                        }
                    }
                    try {
                        it.start()
                        onChanged()
                    } catch (_: IllegalStateException) {
                        releasePlayer()
                        state = if (clip == null) State.EMPTY else State.READY
                        message = R.string.audio_play_failed
                        onChanged()
                    }
                }
            }
            next.setOnCompletionListener { if (player === it) stopPlayback() }
            next.setOnErrorListener { media, _, _ ->
                if (player === media) {
                    releasePlayer()
                    state = if (clip == null) State.EMPTY else State.READY
                    message = R.string.audio_play_failed
                    onChanged()
                }
                true
            }
            next.prepareAsync()
        } catch (_: Exception) {
            releasePlayer()
            state = if (clip == null) State.EMPTY else State.READY
            message = R.string.audio_play_failed
        }
        onChanged()
    }

    fun stopPlayback() {
        if (state != State.PLAYING) return
        val wasTone = playingTone
        releasePlayer()
        state = if (clip == null) State.EMPTY else State.READY
        message = if (wasTone) R.string.tone_finished else if (averageDbFs < -45.0) R.string.audio_ready_quiet else R.string.audio_ready
        onChanged()
    }

    fun cancel() {
        releasePlayer()
        clearClip()
        discard.set(true)
        running.set(false)
        if (state == State.STARTING || state == State.RECORDING || state == State.STOPPING) {
            state = State.STOPPING
            message = R.string.audio_cancelling
        } else {
            state = State.EMPTY
            elapsedMs = 0
            peakPercent = 0f
            averageDbFs = -96.0
            enhancementStatus = R.string.enhance_not_used
            message = R.string.audio_cleared
        }
        onChanged()
    }

    fun close() {
        cancel()
        closed = true
        worker.shutdown()
    }

    private fun clearClip() {
        clip?.delete()
        clip = null
    }

    private fun releasePlayer() {
        releaseEnhancer()
        player?.release()
        player = null
        testTone?.delete()
        testTone = null
        playingTone = false
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun releaseEnhancer() {
        val effect = enhancer
        enhancer = null
        try {
            effect?.release()
        } catch (_: RuntimeException) {
            // Audio-server death must not prevent player/focus/file cleanup.
        }
    }

    companion object {
        // Local hardware-probe limit, not a frozen V1 conversation policy.
        const val MAX_SECONDS = 30
    }
}
