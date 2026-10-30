package com.pinepi.thinksmart.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.io.InputStream

class WavFileTest {
    @Test
    fun fadeOutPreservesEarlierPcmAndEndsAtZeroWithoutChangingLength() {
        val file = File.createTempFile("wave-tail-test", ".wav")
        val frames = 640
        val pcm = ByteArray(frames * 2)
        for (index in 0 until frames) {
            val value = if (index % 2 == 0) 12000 else -12000
            pcm[index * 2] = value.toByte()
            pcm[index * 2 + 1] = (value shr 8).toByte()
        }
        try {
            RandomAccessFile(file, "rw").use { output ->
                output.write(ByteArray(44))
                output.write(pcm)
                WavFile.fadeTail(output, pcm.size)
                WavFile.writeHeader(output, pcm.size)
                assertEquals(44L + pcm.size, output.length())
                val actual = ByteArray(pcm.size)
                output.seek(44)
                output.readFully(actual)
                assertArrayEquals(pcm.copyOf(640), actual.copyOf(640))
                assertEquals(0, actual[actual.size - 1].toInt())
                assertEquals(0, actual[actual.size - 2].toInt())
                // Halfway through the final fade, the signed sample is about half scale.
                val index = 480
                val sample = ((actual[index * 2].toInt() and 255) or (actual[index * 2 + 1].toInt() shl 8)).toShort().toInt()
                assertEquals(5981, sample)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun independentWaveReaderRecognizesFormatAndPreservesSignedSamples() {
        // Known PCM samples: -32768, 0, +32767, little endian.
        val pcm = byteArrayOf(0, -128, 0, 0, -1, 127)
        val file = File.createTempFile("wave-format-test", ".wav")
        try {
            RandomAccessFile(file, "rw").use { output ->
                output.write(ByteArray(44))
                output.write(pcm)
                WavFile.writeHeader(output, pcm.size)
            }
            // Android's unit-test compile classpath omits java.desktop. The host JDK
            // still provides this independent WAV parser at test runtime.
            val reader = Class.forName("javax.sound.sampled.AudioSystem")
            val stream = reader.getMethod("getAudioInputStream", File::class.java).invoke(null, file) as InputStream
            stream.use { audio ->
                val format = audio.property("getFormat")
                assertEquals("PCM_SIGNED", format.property("getEncoding").toString())
                assertEquals(16_000f, format.property("getSampleRate"))
                assertEquals(1, format.property("getChannels"))
                assertEquals(16, format.property("getSampleSizeInBits"))
                assertEquals(false, format.property("isBigEndian"))
                assertEquals(3L, audio.property("getFrameLength"))
                assertArrayEquals(pcm, audio.readBytes())
            }
        } finally {
            file.delete()
        }
    }

    private fun Any.property(method: String): Any = requireNotNull(javaClass.getMethod(method).invoke(this))
}
