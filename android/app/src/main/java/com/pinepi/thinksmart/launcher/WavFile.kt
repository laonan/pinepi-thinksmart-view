package com.pinepi.thinksmart.launcher

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavFile {
    const val SAMPLE_RATE = 16_000

    /** Fade only the final 20 ms in place; does not change sample count or file length. */
    fun fadeTail(file: RandomAccessFile, pcmBytes: Int) {
        require(pcmBytes >= 0 && pcmBytes % 2 == 0)
        val count = minOf(pcmBytes / 2, SAMPLE_RATE / 50)
        if (count == 0) return
        val position = 44L + pcmBytes - count * 2
        val bytes = ByteArray(count * 2)
        file.seek(position)
        file.readFully(bytes)
        for (index in 0 until count) {
            val original = ((bytes[index * 2].toInt() and 255) or
                (bytes[index * 2 + 1].toInt() shl 8)).toShort().toInt()
            val faded = if (count == 1) 0 else original * (count - index - 1) / (count - 1)
            bytes[index * 2] = faded.toByte()
            bytes[index * 2 + 1] = (faded shr 8).toByte()
        }
        file.seek(position)
        file.write(bytes)
    }

    /** RIFF/WAVE, signed PCM16 little endian, mono. Leaves the cursor after the header. */
    fun writeHeader(file: RandomAccessFile, pcmBytes: Int) {
        require(pcmBytes >= 0 && pcmBytes % 2 == 0 && pcmBytes <= Int.MAX_VALUE - 36)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + pcmBytes)
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        header.putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2)
        header.putShort(2).putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(pcmBytes)
        file.seek(0)
        file.write(header.array())
    }
}
