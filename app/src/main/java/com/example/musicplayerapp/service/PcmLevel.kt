package com.example.musicplayerapp.service

import java.nio.ByteBuffer

/** The two PCM encodings the tap understands. Anything else is left alone. */
enum class PcmEncoding { S16, FLOAT, OTHER }

/**
 * Root-mean-square level of one block of PCM.
 *
 * Deliberately free of Android and of Media3: this is the part of the
 * audio-reactive path that can be wrong in a way nobody notices on a screenshot,
 * so it is written to be checked by ordinary JVM tests with ordinary buffers.
 *
 * Little-endian is assembled by hand rather than by setting the buffer's byte
 * order, because the buffer handed to the tap is a read-only view of Media3's own
 * buffer and this code has no business writing to - or reordering - someone
 * else's byte buffer.
 */
object PcmLevel {

    /**
     * Every [strideFrames]th frame is measured.
     *
     * The tap runs on the audio thread for the whole life of playback, and an RMS
     * over every sample of every buffer is work nobody asked for: a few hundred
     * sampled frames per buffer is already a stable loudness reading at buffer
     * rates of tens per second.
     */
    const val DEFAULT_STRIDE_FRAMES = 4

    /**
     * @param buffer the audio as it is about to be played; position and limit are
     *   read but never modified, so the buffer keeps flowing untouched.
     * @param channels how many samples make a frame.
     * @return RMS in 0..1, or 0 for an encoding this does not understand.
     */
    fun rms(
        buffer: ByteBuffer,
        encoding: PcmEncoding,
        channels: Int,
        strideFrames: Int = DEFAULT_STRIDE_FRAMES,
    ): Float {
        val bytesPerSample = when (encoding) {
            PcmEncoding.S16 -> 2
            PcmEncoding.FLOAT -> 4
            PcmEncoding.OTHER -> return 0f
        }
        val channelCount = if (channels > 0) channels else 1
        val frameBytes = channelCount * bytesPerSample
        val frames = buffer.remaining() / frameBytes
        if (frames <= 0) return 0f

        val stride = if (strideFrames > 0) strideFrames else 1
        val view = buffer.duplicate()
        val base = view.position()

        var sumOfSquares = 0.0
        var samples = 0
        var frame = 0
        while (frame < frames) {
            val frameStart = base + frame * frameBytes
            for (channel in 0 until channelCount) {
                val offset = frameStart + channel * bytesPerSample
                val sample = if (bytesPerSample == 2) {
                    readS16(view, offset) / 32768f
                } else {
                    readFloat(view, offset)
                }
                sumOfSquares += sample.toDouble() * sample
                samples++
            }
            frame += stride
        }

        if (samples == 0) return 0f
        return kotlin.math.sqrt(sumOfSquares / samples).toFloat().coerceIn(0f, 1f)
    }

    private fun readS16(buffer: ByteBuffer, offset: Int): Float {
        val low = buffer.get(offset).toInt() and 0xFF
        val high = buffer.get(offset + 1).toInt()
        return ((high shl 8) or low).toShort().toFloat()
    }

    private fun readFloat(buffer: ByteBuffer, offset: Int): Float {
        val bits = (buffer.get(offset).toInt() and 0xFF) or
            ((buffer.get(offset + 1).toInt() and 0xFF) shl 8) or
            ((buffer.get(offset + 2).toInt() and 0xFF) shl 16) or
            ((buffer.get(offset + 3).toInt() and 0xFF) shl 24)
        val value = java.lang.Float.intBitsToFloat(bits)
        return if (value.isFinite()) value else 0f
    }
}
