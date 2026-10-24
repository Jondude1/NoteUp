package com.jondude.noteup.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val SAMPLE_RATE = 16000
const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000 // 16-bit mono

object Wav {
    fun header(dataLen: Int, rate: Int = SAMPLE_RATE): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + dataLen); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataLen)
        return b.array()
    }

    fun wrap(pcm: ByteArray): ByteArray = header(pcm.size) + pcm

    /** Rewrites the size fields once recording ends. */
    fun finalize(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            val dataLen = (raf.length() - 44).toInt().coerceAtLeast(0)
            raf.seek(0)
            raf.write(header(dataLen))
        }
    }
}
