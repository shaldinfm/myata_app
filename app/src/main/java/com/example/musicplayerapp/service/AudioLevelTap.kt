package com.example.musicplayerapp.service

import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer

/**
 * The one thing that actually listens to the audio: a sink for Media3's own
 * [TeeAudioProcessor].
 *
 * Media3 hands this a read-only view of each audio buffer *as it passes through*
 * - the processor then copies the same samples on to the sink unchanged - so this
 * class may read the bytes and must do nothing else with them. It computes an RMS
 * and publishes it; that is the whole of its job.
 *
 * Everything here runs on the playback thread, so it allocates nothing, blocks on
 * nothing, and touches nothing outside [PlaybackAudioLevel].
 */
@UnstableApi
internal class AudioLevelTap(
    private val clock: () -> Long = { android.os.SystemClock.uptimeMillis() },
) : TeeAudioProcessor.AudioBufferSink {

    private var channels = 1
    private var encoding = PcmEncoding.OTHER

    /**
     * The audio format, on every change of it - and, because Media3 flushes the
     * chain when playback stops or seeks, the moment the pipeline goes quiet too.
     */
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        channels = if (channelCount > 0) channelCount else 1
        this.encoding = encodingOf(encoding)
        PlaybackAudioLevel.publishSilence(clock())
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (encoding == PcmEncoding.OTHER) return
        PlaybackAudioLevel.publish(
            rms = PcmLevel.rms(buffer, encoding, channels),
            atMs = clock(),
        )
    }

    @VisibleForTesting
    internal fun encodingOf(media3Encoding: Int): PcmEncoding = when (media3Encoding) {
        ENCODING_PCM_16BIT -> PcmEncoding.S16
        ENCODING_PCM_FLOAT -> PcmEncoding.FLOAT
        else -> PcmEncoding.OTHER
    }

    private companion object {
        // android.media.AudioFormat values, so this file needs no android.media
        // import for two integers.
        const val ENCODING_PCM_16BIT = 2
        const val ENCODING_PCM_FLOAT = 4
    }
}
