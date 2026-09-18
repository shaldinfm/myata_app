package com.example.musicplayerapp.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tracker against a fake clock and explicit events.
 *
 * Time model: the elapsed clock starts at 0 and the station's server clock reads
 * [S0] at that moment, so an observation made at elapsed `t` ms carries
 * `server_time = S0 + t / 1000`, and a track that started at server second
 * `S0 + s` has its window starting at elapsed `s * 1000`. [st] builds such a
 * `started_at`. Observations are made on whole seconds so the mapping is exact.
 *
 * [runTo] plays the service's part: it fires every check the tracker asked for
 * up to the given time, exactly when it asked, and nothing else.
 */
class ScrobbleTrackerTest {

    private companion object {
        const val S0 = 1_789_000_000L
        const val MYATA = "myata"
        const val GOLD = "gold"
    }

    private var enabled = true
    private val candidates = mutableListOf<ScrobbleCandidate>()
    private val logged = mutableListOf<String>()
    private val tracker = ScrobbleTracker(
        isEnabled = { enabled },
        sink = { candidates += it },
        emissions = ScrobbleEmissions(),
        log = { name, _ -> logged += name },
    )
    private var now = 0L

    /** The delay the tracker returned from the last call, relative to that call. */
    private var nextCheck: Long? = null
        set(value) {
            field = value
            dueAt = value?.let { now + it }
        }

    /** The same request as an absolute time - what the service's timer would hold. */
    private var dueAt: Long? = null

    private fun st(second: Long) = S0 + second

    private fun at(ms: Long) {
        require(ms >= now) { "time runs forwards" }
        now = ms
    }

    private fun play(ms: Long, stream: String = MYATA) {
        at(ms); nextCheck = tracker.onPlaying(true, stream, ms)
    }

    private fun stop(ms: Long, stream: String = MYATA) {
        at(ms); nextCheck = tracker.onPlaying(false, stream, ms)
    }

    private fun select(ms: Long, stream: String) {
        at(ms); nextCheck = tracker.onStreamSelected(stream, ms)
    }

    private fun see(
        ms: Long,
        startedAt: Long,
        duration: Long,
        artist: String? = "Artist",
        title: String? = "Title",
        stream: String = MYATA,
        serverTime: Long = S0 + ms / 1000,
        endsAt: Long = startedAt + duration,
    ) {
        at(ms)
        nextCheck = tracker.onObservation(
            FeedObservation(stream, artist, title, startedAt, duration, endsAt, serverTime), ms,
        )
    }

    private fun tick(ms: Long) {
        at(ms); nextCheck = tracker.onTick(ms)
    }

    /** Fires every requested check up to [ms], then advances to it. */
    private fun runTo(ms: Long) {
        while (true) {
            val due = dueAt ?: break
            if (due > ms) break
            tick(due)
            if (dueAt == due) break
        }
        at(ms)
    }

    // ================================================================ thresholds

    @Test
    fun `threshold boundaries to the millisecond, and only one candidate`() {
        play(0)
        see(0, startedAt = st(0), duration = 180)
        assertEquals("the service is asked to check exactly at the threshold", 90_000L, nextCheck)

        tick(89_999)
        assertEquals("threshold - 1 ms", 0, candidates.size)
        tick(90_000)
        assertEquals("exact threshold", 1, candidates.size)
        tick(90_001)
        runTo(180_000)
        assertEquals("threshold + 1 ms and beyond: still one", 1, candidates.size)
        assertNull("nothing left to check", nextCheck)
    }

    @Test
    fun `duration 30 never, 31 at 15500 ms`() {
        play(0)
        see(0, startedAt = st(0), duration = 30)
        runTo(30_000)
        assertEquals(0, candidates.size)
        assertNull(nextCheck)

        see(30_000, startedAt = st(30), duration = 31)
        assertEquals(15_500L, nextCheck)
        runTo(45_499)
        assertEquals(0, candidates.size)
        runTo(45_500)
        assertEquals(1, candidates.size)
    }

    @Test
    fun `durations 60, 480 and 600 use 30 s, 240 s and 240 s`() {
        play(0)
        see(0, startedAt = st(0), duration = 60)
        assertEquals(30_000L, nextCheck)
        runTo(60_000)
        see(60_000, startedAt = st(60), duration = 480)
        assertEquals(240_000L, nextCheck)
        runTo(540_000)
        see(540_000, startedAt = st(540), duration = 600)
        assertEquals(240_000L, nextCheck)
        runTo(780_000)
        assertEquals(3, candidates.size)
    }

    // ================================================================ joining

    @Test
    fun `joining midway credits only what this app played`() {
        // On air since -100 s, 300 s long: 200 s of it remain when Play is pressed.
        play(0)
        see(0, startedAt = st(-100), duration = 300)
        assertEquals("150 s of actual playing, not 150 - 100", 150_000L, nextCheck)
        runTo(149_999)
        assertEquals(0, candidates.size)
        runTo(150_000)
        assertEquals(1, candidates.size)
    }

    @Test
    fun `joining in the final seconds can never become eligible`() {
        play(0)
        see(0, startedAt = st(-170), duration = 180)
        assertNull("10 s left of a 90 s threshold", nextCheck)
        runTo(600_000)
        assertEquals(0, candidates.size)
    }

    @Test
    fun `playing before the first poll counts, but only inside the window`() {
        play(0)
        // The poll answers 8 s after Play; the track began 5 s before Play.
        see(8_000, startedAt = st(-5), duration = 60)
        // 8 s already credited from Play, none from before it.
        assertEquals(22_000L, nextCheck)
        runTo(30_000)
        assertEquals(1, candidates.size)
    }

    // ================================================================ transitions

    @Test
    fun `natural A to B, with B seen 20 s late, credits B from its own start`() {
        play(0)
        see(0, startedAt = st(0), duration = 100)   // A
        runTo(100_000)
        assertEquals("A at 50 s", 1, candidates.size)

        // The poll that sees B lands 20 s into it.
        see(120_000, startedAt = st(100), duration = 60) // B
        assertEquals("20 s of B already played; 10 s to go", 10_000L, nextCheck)
        runTo(130_000)
        assertEquals(2, candidates.size)
        assertEquals(st(100), candidates[1].startedAt)
    }

    @Test
    fun `metadata a few seconds ahead of its start credits from the start, not the poll`() {
        play(0)
        see(0, startedAt = st(5), duration = 60)
        assertEquals("window opens at 5 s; 30 s from there", 35_000L, nextCheck)
        runTo(34_999)
        assertEquals(0, candidates.size)
        runTo(35_000)
        assertEquals(1, candidates.size)
    }

    @Test
    fun `A is never credited past its own end, whatever the poll lag`() {
        play(0)
        see(0, startedAt = st(-80), duration = 100) // 20 s of A left: never eligible
        runTo(300_000)                               // no poll for five minutes
        assertEquals(0, candidates.size)
        assertNull(nextCheck)
    }

    @Test
    fun `the same track aired again later is a new occurrence and counts again`() {
        play(0)
        see(0, startedAt = st(0), duration = 60)
        runTo(60_000)
        see(3_600_000, startedAt = st(3_600), duration = 60)
        runTo(3_660_000)
        assertEquals(2, candidates.size)
        assertEquals(candidates[0].trackKey, candidates[1].trackKey)
        assertTrue(candidates[0].occurrenceId != candidates[1].occurrenceId)
    }

    @Test
    fun `repeated polls of one airing neither reset nor duplicate it`() {
        play(0)
        see(0, startedAt = st(0), duration = 180)
        for (t in 10_000L..170_000L step 10_000L) {
            see(t, startedAt = st(0), duration = 180)
            runTo(t + 5_000)
        }
        runTo(180_000)
        assertEquals(1, candidates.size)
        assertEquals(1, logged.count { it == "SCROBBLE_OCCURRENCE" })
    }

    // ================================================================ playback state

    @Test
    fun `buffering, pause and reconnect do not accrue, and the airing resumes`() {
        play(0)
        see(0, startedAt = st(0), duration = 300)   // threshold 150 s
        runTo(60_000)
        stop(60_000)                                // buffering / reconnect / pause
        assertNull("nothing to schedule while not playing", nextCheck)
        runTo(120_000)
        play(120_000)
        see(120_000, startedAt = st(0), duration = 300)
        assertEquals("60 s credited, 90 s to go", 90_000L, nextCheck)
        runTo(209_999)
        assertEquals(0, candidates.size)
        runTo(210_000)
        assertEquals(1, candidates.size)
    }

    @Test
    fun `eligible then stopped keeps the one candidate and adds nothing`() {
        play(0)
        see(0, startedAt = st(0), duration = 60)
        runTo(30_000)
        stop(31_000)
        play(40_000)
        runTo(60_000)
        assertEquals(1, candidates.size)
    }

    @Test
    fun `a check long after the last poll still ends at the window`() {
        play(0)
        see(0, startedAt = st(-200), duration = 300) // 100 s of window left, threshold 150
        assertNull(nextCheck)
        tick(500_000)
        assertEquals(0, candidates.size)
        assertNull("window exhausted: nothing more can accrue", nextCheck)
    }

    // ================================================================ station switch (D1)

    @Test
    fun `switching station discards the partial listen, and coming back starts again`() {
        play(0)
        see(0, startedAt = st(0), duration = 300)   // Myata, threshold 150 s
        runTo(100_000)

        select(100_000, GOLD)
        stop(100_000, GOLD)
        play(102_000, GOLD)
        see(102_000, startedAt = st(50), duration = 400, stream = GOLD)
        runTo(110_000)

        select(110_000, MYATA)
        stop(110_000, MYATA)
        play(112_000, MYATA)
        see(112_000, startedAt = st(0), duration = 300)
        // 100 s earlier on Myata no longer count; the window ends at 300 s.
        assertEquals(150_000L, nextCheck)
        runTo(262_000)
        assertEquals(1, candidates.size)
        assertEquals(MYATA, candidates[0].streamId)
    }

    @Test
    fun `a poll answer for the previous station is ignored`() {
        play(0, GOLD)
        see(0, startedAt = st(0), duration = 60, stream = MYATA)
        runTo(60_000)
        assertEquals(0, candidates.size)
        assertEquals(0, logged.count { it == "SCROBBLE_OCCURRENCE" })
    }

    // ================================================================ freshness

    @Test
    fun `a frozen feed stops accruing at ends_at and never opens anything new`() {
        play(0)
        see(0, startedAt = st(-60), duration = 100) // threshold 50, only 40 s of window left
        runTo(60_000)
        // The feed froze: same record, server clock moving on, now far past ends_at.
        for (t in 200_000L..900_000L step 20_000L) {
            see(t, startedAt = st(-60), duration = 100)
            // A frozen feed that also reports a "new" stale track must not open it.
            see(t, startedAt = st(t / 1000 - 400), duration = 200)
        }
        assertEquals(0, candidates.size)
        assertEquals(1, logged.count { it == "SCROBBLE_OCCURRENCE" })
        assertTrue(logged.contains("SCROBBLE_FEED"))
    }

    @Test
    fun `a stale poll does not reset the airing being tracked`() {
        play(0)
        see(0, startedAt = st(0), duration = 180)
        runTo(40_000)
        // One stale answer about some other, long-finished track.
        see(40_000, startedAt = st(-600), duration = 200)
        runTo(90_000)
        assertEquals(1, candidates.size)
        assertEquals(st(0), candidates[0].startedAt)
    }

    @Test
    fun `after a stale spell a fresh airing is tracked cleanly`() {
        play(0)
        see(0, startedAt = st(-500), duration = 200)   // stale from the start
        runTo(100_000)
        assertEquals(0, logged.count { it == "SCROBBLE_OCCURRENCE" })
        see(100_000, startedAt = st(100), duration = 60)
        runTo(130_000)
        assertEquals(1, candidates.size)
    }

    @Test
    fun `metadata that blanks out and returns keeps the same airing`() {
        play(0)
        see(0, startedAt = st(0), duration = 180)
        runTo(30_000)
        see(30_000, startedAt = st(0), duration = 180, artist = "", title = "")
        see(40_000, startedAt = 0, duration = 0, artist = "", title = "", endsAt = 0)
        see(50_000, startedAt = st(0), duration = 180)
        runTo(90_000)
        assertEquals(1, candidates.size)
        assertEquals(1, logged.count { it == "SCROBBLE_OCCURRENCE" })
    }

    @Test
    fun `zero started_at or duration can never become eligible`() {
        play(0)
        see(0, startedAt = 0, duration = 180, endsAt = 180)
        see(10_000, startedAt = st(0), duration = 0, endsAt = st(0))
        runTo(600_000)
        assertEquals(0, candidates.size)
    }

    // ================================================================ identity

    @Test
    fun `a TrackKey correction on the same started_at is the same occurrence`() {
        play(0)
        see(0, startedAt = st(0), duration = 180, title = "Titel")
        runTo(30_000)
        see(30_000, startedAt = st(0), duration = 180, title = "Title")
        runTo(90_000)
        assertEquals(1, candidates.size)
        assertEquals(1, logged.count { it == "SCROBBLE_OCCURRENCE" })
        assertEquals("the candidate carries the corrected metadata", "Title", candidates[0].title)
        assertEquals(OccurrenceId(MYATA, st(0)), candidates[0].occurrenceId)
    }

    @Test
    fun `a correction after eligibility never produces a second candidate`() {
        play(0)
        see(0, startedAt = st(0), duration = 60, title = "Titel")
        runTo(30_000)
        assertEquals(1, candidates.size)
        see(40_000, startedAt = st(0), duration = 60, title = "Title")
        runTo(60_000)
        assertEquals(1, candidates.size)
        assertEquals("Titel", candidates[0].title)
        assertTrue(logged.contains("SCROBBLE_CORRECTION_AFTER_EMIT"))
    }

    @Test
    fun `an old airing reappearing out of order never emits twice`() {
        play(0)
        see(0, startedAt = st(0), duration = 60)      // A
        runTo(60_000)
        see(60_000, startedAt = st(60), duration = 200) // B
        runTo(70_000)
        // A again, while B is tracked: out of order, ignored.
        see(70_000, startedAt = st(0), duration = 60)
        // A again after a station round trip has cleared B: re-opened, but inert.
        select(80_000, GOLD)
        select(90_000, MYATA)
        // Fresh and on a live window, so only the emitted mark can stop it.
        see(90_000, startedAt = st(0), duration = 60, serverTime = st(0))
        runTo(400_000)
        assertEquals(1, candidates.count { it.startedAt == st(0) })
        assertTrue(logged.contains("SCROBBLE_OUT_OF_ORDER"))
    }

    // ================================================================ gating (D2)

    @Test
    fun `nothing at all happens while tracking is disabled`() {
        enabled = false
        play(0)
        see(0, startedAt = st(0), duration = 60)
        runTo(60_000)
        tick(60_000)
        assertEquals(0, candidates.size)
        assertEquals("no SCROBBLE_* logs", emptyList<String>(), logged)
        assertNull(nextCheck)
    }

    @Test
    fun `linked midway through a track does not credit listening from before`() {
        enabled = false
        play(0)
        see(0, startedAt = st(0), duration = 180)
        runTo(60_000)
        enabled = true
        see(60_000, startedAt = st(0), duration = 180)
        assertEquals("the full 90 s from the moment the link was seen", 90_000L, nextCheck)
        runTo(149_999)
        assertEquals(0, candidates.size)
        runTo(150_000)
        assertEquals(1, candidates.size)
    }

    @Test
    fun `unlinking before the threshold discards the partial listen`() {
        play(0)
        see(0, startedAt = st(0), duration = 300)
        runTo(100_000)
        enabled = false
        see(100_000, startedAt = st(0), duration = 300)
        enabled = true
        see(110_000, startedAt = st(0), duration = 300)
        assertEquals("from zero again", 150_000L, nextCheck)
    }

    @Test
    fun `unlinking just before the scheduled check means no candidate`() {
        play(0)
        see(0, startedAt = st(0), duration = 180)
        assertEquals(90_000L, nextCheck)
        enabled = false
        tick(90_000)
        assertEquals(0, candidates.size)
        assertNull(nextCheck)
        enabled = true
        tick(90_001)
        assertEquals("relinked: the earlier 90 s are gone", 0, candidates.size)
    }

    @Test
    fun `relinking during the same airing starts a new partial listen from there`() {
        play(0)
        see(0, startedAt = st(0), duration = 400)   // threshold 200 s
        runTo(150_000)
        enabled = false
        tick(150_000)
        runTo(200_000)
        enabled = true
        tick(200_000)                               // first event after the link is seen
        see(210_000, startedAt = st(0), duration = 400)
        // 10 s since relink credited; the window ends at 400 s: 190 s more fits.
        assertEquals(190_000L, nextCheck)
        runTo(400_000)
        assertEquals(1, candidates.size)
    }

    @Test
    fun `the gate is re-read immediately before emitting`() {
        var reads = 0
        val gated = ScrobbleTracker(
            isEnabled = { reads++; reads < 4 },      // true for the event, false at emission
            sink = { candidates += it },
            emissions = ScrobbleEmissions(),
        )
        gated.onPlaying(true, MYATA, 0)
        gated.onObservation(FeedObservation(MYATA, "A", "T", st(0), 60, st(60), S0), 0)
        gated.onTick(30_000)                        // exactly the threshold
        assertEquals(0, candidates.size)
    }

    @Test
    fun `release forgets the partial listen`() {
        play(0)
        see(0, startedAt = st(0), duration = 180)
        runTo(80_000)
        tracker.release()
        tick(100_000)
        assertEquals(0, candidates.size)
    }
}
