package com.example.musicplayerapp.data.lastfm.queue

import com.example.musicplayerapp.data.lastfm.IgnoredScrobble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both decision tables and the backoff curve, pinned row by row. */
class ScrobblePolicyTest {

    private val now = 1_789_000_000_000L

    // ---- backoff ------------------------------------------------------------------

    @Test
    fun `backoff doubles from 30 s and is capped at 6 h`() {
        assertEquals(30_000L, ScrobblePolicy.backoffMs(1, 0.0))
        assertEquals(60_000L, ScrobblePolicy.backoffMs(2, 0.0))
        assertEquals(120_000L, ScrobblePolicy.backoffMs(3, 0.0))
        assertEquals(6 * 3_600_000L, ScrobblePolicy.backoffMs(20, 0.0))
        assertEquals(6 * 3_600_000L, ScrobblePolicy.backoffMs(10_000, 0.0))
    }

    @Test
    fun `jitter moves it by at most 20 percent either way`() {
        assertEquals(24_000L, ScrobblePolicy.backoffMs(1, -1.0))
        assertEquals(36_000L, ScrobblePolicy.backoffMs(1, 1.0))
        assertEquals("clamped", 36_000L, ScrobblePolicy.backoffMs(1, 7.0))
        assertEquals((6 * 3_600_000L * 1.2).toLong(), ScrobblePolicy.backoffMs(99, 1.0))
    }

    // ---- ordering -----------------------------------------------------------------

    private fun row(startedAt: Long, next: Long = 0L, stream: String = "myata") =
        ScrobbleQueueEntry(stream, startedAt, "k", "A", "T", 200, "x", 1L, 0, next)

    @Test
    fun `only the consecutive due prefix is sent`() {
        val head = listOf(row(1), row(2), row(3, next = now + 1), row(4))
        assertEquals(listOf(1L, 2L), ScrobblePolicy.duePrefix(head, now).map { it.startedAt })
    }

    @Test
    fun `an oldest row in backoff lets nothing newer through`() {
        val head = listOf(row(1, next = now + 60_000), row(2), row(3))
        assertTrue(ScrobblePolicy.duePrefix(head, now).isEmpty())
    }

    @Test
    fun `never more than 50`() {
        val head = (1L..120L).map { row(it) }
        assertEquals(50, ScrobblePolicy.duePrefix(head, now).size)
    }

    // ---- global errors ------------------------------------------------------------

    @Test
    fun `only 11 and 16 retry, 9 re-authenticates, everything else stops until restart`() {
        assertEquals(ScrobblePolicy.Global.Retry, ScrobblePolicy.forGlobalError(11))
        assertEquals(ScrobblePolicy.Global.Retry, ScrobblePolicy.forGlobalError(16))
        assertEquals(ScrobblePolicy.Global.Reauthenticate, ScrobblePolicy.forGlobalError(9))
        for (code in listOf(2, 3, 4, 5, 6, 7, 8, 10, 13, 26, 27, 29, 0, 99, -1)) {
            assertEquals("code $code", ScrobblePolicy.Global.StopUntilRestart, ScrobblePolicy.forGlobalError(code))
        }
    }

    // ---- per scrobble -------------------------------------------------------------

    @Test
    fun `accepted and the three terminal filters delete the row`() {
        for (v in listOf(
            IgnoredScrobble.ACCEPTED, IgnoredScrobble.ARTIST_IGNORED,
            IgnoredScrobble.TRACK_IGNORED, IgnoredScrobble.TIMESTAMP_TOO_OLD,
        )) {
            assertEquals(v.name, ScrobblePolicy.Item.Delete, ScrobblePolicy.forItem(v, 0, now))
        }
    }

    @Test
    fun `too new defers 10 minutes, and the third attempt quarantines`() {
        assertEquals(ScrobblePolicy.Item.Defer(now + 600_000), ScrobblePolicy.forItem(IgnoredScrobble.TIMESTAMP_TOO_NEW, 0, now))
        assertEquals(ScrobblePolicy.Item.Defer(now + 600_000), ScrobblePolicy.forItem(IgnoredScrobble.TIMESTAMP_TOO_NEW, 1, now))
        assertEquals(ScrobblePolicy.Item.Quarantine, ScrobblePolicy.forItem(IgnoredScrobble.TIMESTAMP_TOO_NEW, 2, now))
    }

    @Test
    fun `the daily limit defers a day, an unknown code quarantines, a missing one backs off`() {
        assertEquals(
            ScrobblePolicy.Item.Defer(now + 86_400_000),
            ScrobblePolicy.forItem(IgnoredScrobble.DAILY_LIMIT_EXCEEDED, 0, now),
        )
        assertEquals(ScrobblePolicy.Item.Quarantine, ScrobblePolicy.forItem(IgnoredScrobble.UNKNOWN, 0, now))
        assertEquals(ScrobblePolicy.Item.Backoff, ScrobblePolicy.forItem(IgnoredScrobble.MISSING, 0, now))
    }
}
