package com.example.musicplayerapp.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The loudness reading the aura moves with, checked on ordinary buffers.
 *
 * This is the number the whole music-reactive half of the slice rests on, and it
 * is the kind of code that is wrong in ways nobody sees: a sign error, a byte
 * order, an off-by-one at the end of a buffer. None of that shows up as a crash
 * or as a test failure anywhere else, so it is pinned here, with no Android and
 * no Media3 involved.
 */
class PcmLevelTest {

    private fun bufferOfS16(vararg samples: Int): ByteBuffer {
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putShort(it.toShort()) }
        bytes.flip()
        return bytes
    }

    private fun bufferOfFloat(vararg samples: Float): ByteBuffer {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putFloat(it) }
        bytes.flip()
        return bytes
    }

    @Test
    fun a_silent_buffer_reads_as_silence() {
        val rms = PcmLevel.rms(bufferOfS16(0, 0, 0, 0), PcmEncoding.S16, channels = 1)

        assertEquals(0f, rms, 1e-6f)
    }

    @Test
    fun full_scale_sixteen_bit_reads_as_one() {
        val rms = PcmLevel.rms(
            bufferOfS16(-32768, 32767, -32768, 32767),
            PcmEncoding.S16,
            channels = 2,
        )

        assertTrue("full scale should read as full scale, got $rms", rms > 0.99f)
    }

    @Test
    fun half_scale_reads_as_half_scale() {
        // A steady 0.5 of full scale: RMS of a constant is the constant, which is
        // what makes this check the normalisation rather than the averaging.
        val rms = PcmLevel.rms(
            bufferOfS16(16384, -16384, 16384, -16384),
            PcmEncoding.S16,
            channels = 1,
        )

        assertEquals(0.5f, rms, 0.002f)
    }

    @Test
    fun both_channels_are_measured() {
        // One channel silent, one at full scale - the sort of thing a bug that
        // reads only the left channel reports as silence.
        val rms = PcmLevel.rms(
            bufferOfS16(0, -32768, 0, 32767),
            PcmEncoding.S16,
            channels = 2,
        )

        assertTrue("a live channel was left out, got $rms", rms > 0.7f)
    }

    @Test
    fun float_pcm_is_measured_too() {
        // Float output is a supported configuration of the sink, and an unsigned
        // read of it would produce nonsense rather than silence.
        val rms = PcmLevel.rms(bufferOfFloat(0.5f, -0.5f, 0.5f, -0.5f), PcmEncoding.FLOAT, channels = 1)

        assertEquals(0.5f, rms, 0.002f)
    }

    @Test
    fun a_negative_sixteen_bit_sample_is_not_read_as_a_positive_one() {
        val positive = PcmLevel.rms(bufferOfS16(32767, 32767), PcmEncoding.S16, channels = 1)
        val negative = PcmLevel.rms(bufferOfS16(-32768, -32768), PcmEncoding.S16, channels = 1)

        assertEquals("a sign was lost on the way in", positive, negative, 0.002f)
    }

    @Test
    fun an_encoding_the_tap_does_not_understand_reads_as_silence() {
        assertEquals(0f, PcmLevel.rms(bufferOfS16(32767), PcmEncoding.OTHER, channels = 1), 1e-6f)
    }

    @Test
    fun a_buffer_too_short_for_one_frame_reads_as_silence() {
        // Half a stereo frame: reading it as a frame would read past the end.
        val buffer = ByteBuffer.allocate(2)
        buffer.putShort(1234)
        buffer.flip()

        assertEquals(0f, PcmLevel.rms(buffer, PcmEncoding.S16, channels = 2), 1e-6f)
    }

    @Test
    fun the_buffer_is_left_alone() {
        // The tap is handed a read-only view of Media3's own buffer; reading it
        // must not move it, or the samples after the tap would be lost.
        val buffer = bufferOfS16(16384, 16384, 16384, 16384)
        val position = buffer.position()
        val limit = buffer.limit()

        PcmLevel.rms(buffer, PcmEncoding.S16, channels = 2)

        assertEquals("the buffer's position moved", position, buffer.position())
        assertEquals("the buffer's limit moved", limit, buffer.limit())
    }

    @Test
    fun the_stride_samples_the_buffer_rather_than_all_of_it() {
        // Loud frame, then quiet ones. At a stride of one the quiet frames count;
        // at a stride of four only the loud one is measured. Both are valid
        // readings, and the point is that the stride is honoured at all.
        val samples = IntArray(16)
        samples[0] = 32767
        val buffer = bufferOfS16(*samples)

        val every = PcmLevel.rms(buffer, PcmEncoding.S16, channels = 1, strideFrames = 1)
        val strided = PcmLevel.rms(buffer, PcmEncoding.S16, channels = 1, strideFrames = 4)

        assertTrue("strided $strided should be louder than every-frame $every", strided > every * 1.5f)
    }
}
