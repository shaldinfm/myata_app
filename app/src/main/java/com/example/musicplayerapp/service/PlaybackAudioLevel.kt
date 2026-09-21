package com.example.musicplayerapp.service

import kotlin.math.log10

/**
 * How loud the stream is right now, as one number the UI can read.
 *
 * The TV player's aura is supposed to move with the music, and this is where that
 * signal lives. It is written by the audio pipeline itself - see
 * [AudioLevelTap] and [AudioLevelRenderersFactory] - and read by the ambient view
 * once per frame, in the same process, so there is no IPC, no permission and no
 * second audio path.
 *
 * ## Why a level and not a spectrum
 *
 * A broadband RMS is enough for "more energy, more glow", it costs a few floats
 * per audio buffer, and it cannot go wrong in interesting ways. Bars, bands and
 * beats are a visualizer, which this is not.
 *
 * ## Why the reading goes stale on purpose
 *
 * The tap only runs while audio is flowing. When playback stops, the last buffer
 * that went through is the last thing this object heard, and the aura has to
 * *know* it is old rather than hold a peak forever - otherwise pausing a loud
 * track would leave the screen lit up. [read] answers 0 once the reading is older
 * than [STALE_AFTER_MS], so silence, pause and a dead pipeline all read the same
 * way to the UI.
 */
object PlaybackAudioLevel {

    /**
     * RMS at or below this reads as zero energy.
     *
     * Ordinary music sits around -20 to -10 dBFS, so a floor much above this
     * would flatten the difference between a quiet passage and a loud one;
     * measured on the TV AVD, radio streams move between roughly -34 dBFS and
     * -12 dBFS, which this floor spreads across most of the 0..1 range.
     */
    const val FLOOR_DB = -48f

    /** A reading older than this is not a reading. */
    const val STALE_AFTER_MS = 600L

    @Volatile
    private var energy = 0f

    @Volatile
    private var publishedAtMs = 0L

    /**
     * Publishes the RMS of one block of audio as it passes through the sink.
     *
     * Called from the playback thread, so the two fields above are volatile and
     * nothing here allocates or blocks.
     */
    fun publish(rms: Float, atMs: Long) {
        energy = energyOf(rms)
        publishedAtMs = atMs
    }

    /** The pipeline went quiet - a flush, a pause, a stream that ended. */
    fun publishSilence(atMs: Long) {
        energy = 0f
        publishedAtMs = atMs
    }

    /**
     * The energy now, or 0 when nothing has been heard recently.
     *
     * @param nowMs a clock the caller already has, so a frame reads the clock once.
     */
    fun read(nowMs: Long): Float =
        if (nowMs - publishedAtMs > STALE_AFTER_MS) 0f else energy

    /**
     * RMS in 0..1 as energy in 0..1, on a decibel curve.
     *
     * Loudness is not linear in amplitude: a stream at half the samples' amplitude
     * is not half as loud, it is 6 dB down. Mapping through dB is what makes the
     * aura's response to a quiet passage feel proportional instead of like a
     * switch.
     */
    fun energyOf(rms: Float): Float {
        if (rms <= 0f) return 0f
        val decibels = 20f * log10(rms)
        return ((decibels - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
    }
}
