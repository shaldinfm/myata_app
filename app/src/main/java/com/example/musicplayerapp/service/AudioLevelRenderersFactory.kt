package com.example.musicplayerapp.service

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor

/**
 * The playback pipeline with a loudness reading coming off it.
 *
 * ## Why this is safe, and how it was checked
 *
 * The only permission-free way to hear your own app's audio on API 24 is inside
 * the app's own audio pipeline. `android.media.audiofx.Visualizer` - the obvious
 * candidate - needs `RECORD_AUDIO`, which this app deliberately does not hold and
 * should not start holding to decorate a background; `AudioPlaybackCapture`
 * arrives with a MediaProjection consent dialog on API 29 and does not exist on
 * 24. So the tap goes where the samples already are.
 *
 * Media3 ships [TeeAudioProcessor] for exactly this, and the chain below is the
 * stock one plus that processor. The claim that it *is* the stock one is not
 * taken on faith - media3-exoplayer 1.7.1's `DefaultRenderersFactory.buildAudioSink`
 * builds `DefaultAudioSink.Builder(context).setEnableFloatOutput(..).setEnableAudioTrackPlaybackParams(..).build()`,
 * and `DefaultAudioSink.Builder.build()` fills an unset chain with
 * `new DefaultAudioProcessorChain(new AudioProcessor[0])` - so constructing that
 * same chain with the tee in front of the same two default processors reproduces
 * it exactly. Read out of the pinned artifact's bytecode, not from memory.
 *
 * [TeeAudioProcessor] itself passes audio through: it hands the sink a read-only
 * view of the buffer, then copies the identical bytes into its output. Nothing
 * here changes a sample, a format, a volume or a timing - and if any of it fails
 * to construct, the stock sink is built instead, because a background effect is
 * never worth a player that will not start.
 */
@UnstableApi
internal class AudioLevelRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? = try {
        tappedSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
    } catch (e: Throwable) {
        Log.w(TAG, "audio level tap unavailable; playing through the stock audio sink", e)
        super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
    }

    @VisibleForTesting
    internal fun tappedSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(TeeAudioProcessor(AudioLevelTap())),
            )
            .build()

    private companion object {
        const val TAG = "AudioLevelTap"
    }
}
