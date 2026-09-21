package com.example.musicplayerapp.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loudness reading as the UI sees it: what a given RMS means, and when a
 * reading stops counting.
 *
 * The aura's "quiet passages calm down" behaviour is this curve. If the mapping
 * were linear in amplitude, ordinary music - which lives between about -30 and
 * -10 dBFS - would sit in the top fifth of the range and never visibly move; if
 * the reading never went stale, pausing a loud track would leave the screen lit.
 */
class PlaybackAudioLevelTest {

    @After
    fun quiet() {
        PlaybackAudioLevel.publishSilence(0L)
    }

    @Test
    fun silence_is_zero_energy() {
        assertEquals(0f, PlaybackAudioLevel.energyOf(0f), 1e-6f)
        assertEquals(0f, PlaybackAudioLevel.energyOf(-1f), 1e-6f)
    }

    @Test
    fun full_scale_is_full_energy() {
        assertEquals(1f, PlaybackAudioLevel.energyOf(1f), 1e-4f)
    }

    @Test
    fun the_mapping_is_the_decibel_curve_not_the_amplitude_one() {
        // -6 dBFS is 0.5 amplitude: 42 of the 48 dB the window covers, so 0.875
        // of the range - not the 0.5 a linear reading would report.
        assertEquals(0.875f, PlaybackAudioLevel.energyOf(0.5012f), 0.01f)

        // The floor is where the range starts and it is not reachable from above.
        assertTrue(PlaybackAudioLevel.energyOf(0.004f) < 0.02f)
    }

    @Test
    fun ordinary_music_sits_across_the_middle_of_the_range() {
        // Measured on the TV AVD: quiet passages near -34 dBFS, loud ones near
        // -12. Both have to be visible on screen, and they are - a quarter of the
        // range apart rather than a few percent.
        val quiet = PlaybackAudioLevel.energyOf(0.02f)
        val loud = PlaybackAudioLevel.energyOf(0.25f)

        assertTrue("quiet $quiet", quiet in 0.2f..0.45f)
        assertTrue("loud $loud", loud in 0.7f..0.85f)
    }

    @Test
    fun a_fresh_reading_is_read_back() {
        PlaybackAudioLevel.publish(0.25f, atMs = 1_000L)

        assertEquals(PlaybackAudioLevel.energyOf(0.25f), PlaybackAudioLevel.read(1_100L), 1e-4f)
    }

    @Test
    fun a_reading_that_stopped_arriving_goes_to_zero() {
        // Pause, a flush, a stream that ended: the pipeline stops publishing, and
        // the screen has to settle rather than hold the last loud moment forever.
        PlaybackAudioLevel.publish(0.25f, atMs = 1_000L)

        assertEquals(0f, PlaybackAudioLevel.read(1_000L + PlaybackAudioLevel.STALE_AFTER_MS + 1), 1e-6f)
    }

    @Test
    fun the_pipeline_going_quiet_reads_as_silence_immediately() {
        PlaybackAudioLevel.publish(0.25f, atMs = 1_000L)
        PlaybackAudioLevel.publishSilence(atMs = 1_010L)

        assertEquals(0f, PlaybackAudioLevel.read(1_020L), 1e-6f)
    }
}
