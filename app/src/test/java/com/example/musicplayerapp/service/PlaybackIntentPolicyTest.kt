package com.example.musicplayerapp.service

import com.example.musicplayerapp.data.PlaybackIntentStore
import com.example.musicplayerapp.data.Streams
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decisions a process death forces, pinned.
 *
 * A real process death cannot be staged from a test that lives in the process
 * being killed, so what is asserted here is the thing that has to be right when
 * it happens: given a durable record and the state of a freshly built player,
 * whether audio may start, and for which station. The service is a translator on
 * top of this - it sets the media item, prepares and plays - and the translation
 * is the part `PlaybackIntentServiceTest` and the manual kill-the-process pass
 * cover.
 *
 * The negative cases are the ones that matter most. A restart that resurrects
 * audio the listener stopped is a worse bug than the one this whole mechanism
 * exists to fix: that one is silence, this one is a radio playing by itself.
 */
class PlaybackIntentPolicyTest {

    private fun known(stream: String, wants: Boolean) =
        PlaybackIntentStore.Stored.Known(stream, wants)

    private fun restart(
        stored: PlaybackIntentStore.Stored,
        alreadyRestored: Boolean = false,
        playerHasMediaItem: Boolean = false,
    ) = PlaybackIntentPolicy.onStickyRestart(stored, alreadyRestored, playerHasMediaItem)

    // ==================== resume: the bug this slice fixes ====================

    @Test
    fun `myata that was playing resumes after a sticky restart`() {
        assertEquals(
            PlaybackIntentPolicy.Restore.Resume(Streams.MYATA),
            restart(known(Streams.MYATA, wants = true)),
        )
    }

    @Test
    fun `gold that was playing resumes after a sticky restart`() {
        assertEquals(
            PlaybackIntentPolicy.Restore.Resume(Streams.GOLD),
            restart(known(Streams.GOLD, wants = true)),
        )
    }

    @Test
    fun `xtra that was playing resumes after a sticky restart`() {
        assertEquals(
            PlaybackIntentPolicy.Restore.Resume(Streams.XTRA),
            restart(known(Streams.XTRA, wants = true)),
        )
    }

    // ==================== user intent wins ====================

    /**
     * Pause, stop and a sleep-timer expiry are one case to this object, and that
     * is the point: each of them writes the same `false` through the same call,
     * so none of them can be the one that was forgotten. Which of the three it
     * was is a `PlaybackLog` line, not a branch.
     */
    @Test
    fun `a stopped stream is adopted but never started`() {
        for (stream in listOf(Streams.MYATA, Streams.GOLD, Streams.XTRA)) {
            assertEquals(
                "stream $stream",
                PlaybackIntentPolicy.Restore.Adopt(stream),
                restart(known(stream, wants = false)),
            )
        }
    }

    @Test
    fun `an adopted stream is still the one a later play means`() {
        // The pause case keeps the station, which is what makes the notification
        // Play work after a restart instead of having to guess a default.
        val decision = restart(known(Streams.GOLD, wants = false))
        assertEquals(PlaybackIntentPolicy.Restore.Adopt(Streams.GOLD), decision)
    }

    // ==================== nothing to restore ====================

    @Test
    fun `no record starts nothing`() {
        assertEquals(
            PlaybackIntentPolicy.Restore.Skip("no_durable_intent"),
            restart(PlaybackIntentStore.Stored.None),
        )
    }

    @Test
    fun `an unusable stream key is discarded rather than guessed at`() {
        // Silence, not a default station: nobody is watching a restarted service,
        // and the wrong stream starting by itself is the worse failure.
        assertEquals(
            PlaybackIntentPolicy.Restore.Discard("android.intent.action.PLAY"),
            restart(PlaybackIntentStore.Stored.Unusable("android.intent.action.PLAY")),
        )
    }

    // ==================== no duplicate prepare/play ====================

    @Test
    fun `a second start command does not restore twice`() {
        assertEquals(
            PlaybackIntentPolicy.Restore.Skip("already_restored"),
            restart(known(Streams.MYATA, wants = true), alreadyRestored = true),
        )
    }

    @Test
    fun `a loaded player is left alone`() {
        // Media3 issues its own action-less start commands while playback runs.
        assertEquals(
            PlaybackIntentPolicy.Restore.Skip("player_already_loaded"),
            restart(known(Streams.MYATA, wants = true), playerHasMediaItem = true),
        )
    }

    @Test
    fun `already restored beats every record`() {
        for (stored in listOf(
            PlaybackIntentStore.Stored.None,
            PlaybackIntentStore.Stored.Unusable("nonsense"),
            known(Streams.GOLD, wants = true),
            known(Streams.GOLD, wants = false),
        )) {
            assertEquals(
                "stored $stored",
                PlaybackIntentPolicy.Restore.Skip("already_restored"),
                restart(stored, alreadyRestored = true),
            )
        }
    }

    // ==================== remote / notification Play, empty timeline ====================

    @Test
    fun `play on an empty timeline restores the persisted stream`() {
        assertEquals(
            PlaybackIntentPolicy.Selection(Streams.XTRA, "durable_intent"),
            PlaybackIntentPolicy.onEmptyTimelinePlay(known(Streams.XTRA, wants = true), inMemoryStream = ""),
        )
    }

    @Test
    fun `play on an empty timeline uses the record even after an explicit pause`() {
        // The press is the intent. The stored boolean says what was true before
        // it, and has nothing to say about it.
        assertEquals(
            PlaybackIntentPolicy.Selection(Streams.GOLD, "durable_intent"),
            PlaybackIntentPolicy.onEmptyTimelinePlay(known(Streams.GOLD, wants = false), inMemoryStream = null),
        )
    }

    @Test
    fun `a station chosen after the restart beats the record`() {
        assertEquals(
            PlaybackIntentPolicy.Selection(Streams.MYATA, "current_selection"),
            PlaybackIntentPolicy.onEmptyTimelinePlay(known(Streams.GOLD, wants = true), inMemoryStream = Streams.MYATA),
        )
    }

    @Test
    fun `an unusable in-memory stream falls through to the record`() {
        assertEquals(
            PlaybackIntentPolicy.Selection(Streams.GOLD, "durable_intent"),
            PlaybackIntentPolicy.onEmptyTimelinePlay(
                known(Streams.GOLD, wants = true),
                inMemoryStream = "android.intent.action.PLAY",
            ),
        )
    }

    @Test
    fun `an explicit play never resolves to nothing`() {
        // A dead Play button is issue #14. With no usable record at all, the
        // default station is the only answer that is not one.
        assertEquals(
            PlaybackIntentPolicy.Selection(Streams.DEFAULT, "default_no_record"),
            PlaybackIntentPolicy.onEmptyTimelinePlay(PlaybackIntentStore.Stored.None, inMemoryStream = ""),
        )
        assertEquals(
            PlaybackIntentPolicy.Selection(Streams.DEFAULT, "default_unusable_record"),
            PlaybackIntentPolicy.onEmptyTimelinePlay(
                PlaybackIntentStore.Stored.Unusable("nonsense"),
                inMemoryStream = "",
            ),
        )
    }

    // ==================== which station the UI shows ====================

    private fun ui(session: String? = null, saved: String? = null, stored: String? = null) =
        PlaybackIntentPolicy.uiStream(session, saved, stored)

    @Test
    fun `the session outranks everything`() {
        // GOLD coming out of the speaker and MYATA on the screen is the failure
        // this ordering exists to make impossible.
        assertEquals(
            PlaybackIntentPolicy.Selection(Streams.GOLD, "session"),
            ui(session = Streams.GOLD, saved = Streams.MYATA, stored = Streams.XTRA),
        )
    }

    @Test
    fun `a restored station reaches the UI through the record when the session is silent`() {
        // The paused case: the service holds the station but has no media item, so
        // the session cannot report it. The record can, and must.
        for (stream in listOf(Streams.MYATA, Streams.GOLD, Streams.XTRA)) {
            assertEquals(
                "stream $stream",
                PlaybackIntentPolicy.Selection(stream, "durable_intent"),
                ui(session = null, saved = null, stored = stream),
            )
        }
    }

    @Test
    fun `a UI that already knows what it was showing keeps it`() {
        // A recreated UI - theme change, rotation, restored task - is not told to
        // forget its own page by a record that says something older.
        assertEquals(
            PlaybackIntentPolicy.Selection(Streams.XTRA, "ui_state"),
            ui(session = null, saved = Streams.XTRA, stored = Streams.GOLD),
        )
    }

    @Test
    fun `a live session still overrides what the UI was showing`() {
        assertEquals(
            PlaybackIntentPolicy.Selection(Streams.MYATA, "session"),
            ui(session = Streams.MYATA, saved = Streams.XTRA, stored = Streams.XTRA),
        )
    }

    @Test
    fun `an unusable persisted stream falls back to the default`() {
        assertEquals(
            PlaybackIntentPolicy.Selection(Streams.DEFAULT, "default"),
            ui(stored = "android.intent.action.PLAY"),
        )
    }

    @Test
    fun `nothing at all is the default`() {
        assertEquals(PlaybackIntentPolicy.Selection(Streams.DEFAULT, "default"), ui())
        assertEquals(PlaybackIntentPolicy.Selection(Streams.DEFAULT, "default"), ui(session = "", saved = "", stored = ""))
    }

    @Test
    fun `an unusable session id does not veto the sources below it`() {
        // A media id that is not a stream key means the session cannot answer, not
        // that nobody can.
        assertEquals(
            PlaybackIntentPolicy.Selection(Streams.GOLD, "durable_intent"),
            ui(session = "some-other-item", saved = null, stored = Streams.GOLD),
        )
    }

    @Test
    fun `the xtra alias is accepted from a record written by an older build`() {
        assertEquals(
            PlaybackIntentPolicy.Selection(Streams.XTRA, "current_selection"),
            PlaybackIntentPolicy.onEmptyTimelinePlay(PlaybackIntentStore.Stored.None, inMemoryStream = "xtra"),
        )
    }
}
