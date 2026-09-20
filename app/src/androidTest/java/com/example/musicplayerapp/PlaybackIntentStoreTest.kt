package com.example.musicplayerapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.musicplayerapp.data.PlaybackIntentStore
import com.example.musicplayerapp.data.Streams
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The durable record itself, on a device, against the real SharedPreferences.
 *
 * The decisions taken from it are `PlaybackIntentPolicyTest`'s; what is asserted
 * here is that the record survives being written and read back the way the policy
 * assumes, including the two cases that are only interesting on disk: a key that
 * is not a stream, and a pause that must not take the station with it.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackIntentStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() = PlaybackIntentStore.clearForTest(context)

    @After
    fun tidy() = PlaybackIntentStore.clearForTest(context)

    @Test
    fun a_fresh_install_has_no_record() {
        assertEquals(PlaybackIntentStore.Stored.None, PlaybackIntentStore.read(context))
    }

    @Test
    fun a_play_records_the_station_and_the_intent_together() {
        for (stream in listOf(Streams.MYATA, Streams.GOLD, Streams.XTRA)) {
            assertTrue(PlaybackIntentStore.recordPlaybackWanted(context, stream))
            assertEquals(
                PlaybackIntentStore.Stored.Known(stream, true),
                PlaybackIntentStore.read(context),
            )
        }
    }

    @Test
    fun a_switch_never_leaves_the_new_intent_beside_the_old_station() {
        // One editor, so there is no instant at which the record says "playback
        // wanted" against a station the listener has just moved off. A process
        // death in such an instant would restore a radio nobody asked for.
        PlaybackIntentStore.recordPlaybackWanted(context, Streams.MYATA)

        PlaybackIntentStore.recordPlaybackWanted(context, Streams.XTRA)

        assertEquals(
            PlaybackIntentStore.Stored.Known(Streams.XTRA, true),
            PlaybackIntentStore.read(context),
        )
    }

    @Test
    fun a_pause_clears_the_intent_and_keeps_the_station() {
        PlaybackIntentStore.recordPlaybackWanted(context, Streams.GOLD)

        PlaybackIntentStore.recordPlaybackNotWanted(context)

        // The station is what makes the notification Play work after a restart;
        // losing it on a pause would leave that button guessing.
        assertEquals(
            PlaybackIntentStore.Stored.Known(Streams.GOLD, false),
            PlaybackIntentStore.read(context),
        )
    }

    @Test
    fun a_station_recorded_on_its_own_makes_no_claim_about_playback() {
        // Absent is not "true". A record that has never been told what the
        // listener wanted must not be able to start audio by itself.
        assertTrue(PlaybackIntentStore.recordSelectedStream(context, Streams.XTRA))
        assertEquals(
            PlaybackIntentStore.Stored.Known(Streams.XTRA, false),
            PlaybackIntentStore.read(context),
        )
    }

    @Test
    fun recording_a_station_leaves_an_existing_intent_alone() {
        PlaybackIntentStore.recordPlaybackWanted(context, Streams.MYATA)

        PlaybackIntentStore.recordSelectedStream(context, Streams.GOLD)

        assertEquals(
            PlaybackIntentStore.Stored.Known(Streams.GOLD, true),
            PlaybackIntentStore.read(context),
        )
    }

    @Test
    fun an_alias_is_stored_canonically() {
        PlaybackIntentStore.recordSelectedStream(context, "xtra")
        assertEquals(Streams.XTRA, PlaybackIntentStore.rawStreamForTest(context))

        PlaybackIntentStore.recordPlaybackWanted(context, "hits")
        assertEquals(Streams.XTRA, PlaybackIntentStore.rawStreamForTest(context))
    }

    @Test
    fun a_key_that_is_not_a_stream_is_refused_rather_than_written() {
        PlaybackIntentStore.recordPlaybackWanted(context, Streams.MYATA)

        // Framework intent actions reach the service as stream extras; this is
        // the one thing that must never overwrite a working record (issue #14).
        for (junk in listOf("android.intent.action.PLAY", "", null)) {
            assertFalse(PlaybackIntentStore.recordPlaybackWanted(context, junk))
            assertFalse(PlaybackIntentStore.recordSelectedStream(context, junk))
        }

        assertEquals(
            PlaybackIntentStore.Stored.Known(Streams.MYATA, true),
            PlaybackIntentStore.read(context),
        )
    }

    @Test
    fun the_last_selected_station_is_what_the_UI_reads() {
        assertNull(PlaybackIntentStore.selectedStream(context))

        PlaybackIntentStore.recordPlaybackWanted(context, Streams.GOLD)
        assertEquals(Streams.GOLD, PlaybackIntentStore.selectedStream(context))

        // A pause does not take it away - the UI still has to show the station.
        PlaybackIntentStore.recordPlaybackNotWanted(context)
        assertEquals(Streams.GOLD, PlaybackIntentStore.selectedStream(context))

        // An unusable record has no station to give.
        PlaybackIntentStore.writeRawForTest(context, "nonsense", wantsPlayback = true)
        assertNull(PlaybackIntentStore.selectedStream(context))
    }

    @Test
    fun a_planted_unusable_key_is_reported_as_unusable() {
        PlaybackIntentStore.writeRawForTest(context, "android.intent.action.PLAY", wantsPlayback = true)
        assertEquals(
            PlaybackIntentStore.Stored.Unusable("android.intent.action.PLAY"),
            PlaybackIntentStore.read(context),
        )
    }

    @Test
    fun clear_removes_both_halves() {
        PlaybackIntentStore.recordPlaybackWanted(context, Streams.GOLD)

        PlaybackIntentStore.clear(context)

        assertEquals(PlaybackIntentStore.Stored.None, PlaybackIntentStore.read(context))
        assertNull(PlaybackIntentStore.rawStreamForTest(context))
    }
}
