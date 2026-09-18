package com.example.musicplayerapp.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * At most one candidate per [OccurrenceId] for the lifetime of the app process -
 * across `MediaPlayerService` recreations, each of which builds a new tracker.
 *
 * Each "service instance" below is a fresh [ScrobbleTracker] over the same
 * [ScrobbleEmissions] store, the way the service passes [ScrobbleEmissions.process].
 * A fresh store stands for a new process.
 *
 * Time model as in ScrobbleTrackerTest: elapsed 0 is server second [S0], and
 * observations are made on whole seconds.
 */
class ScrobbleEmissionsTest {

    private companion object {
        const val S0 = 1_789_000_000L
        const val MYATA = "myata"
        const val GOLD = "gold"
    }

    private val candidates = mutableListOf<ScrobbleCandidate>()

    private fun st(second: Long) = S0 + second

    private fun tracker(store: ScrobbleEmissions) =
        ScrobbleTracker(isEnabled = { true }, sink = { candidates += it }, emissions = store)

    private fun obs(startedAt: Long, duration: Long, atMs: Long, stream: String = MYATA) =
        FeedObservation(stream, "Artist", "Title", startedAt, duration, startedAt + duration, S0 + atMs / 1000)

    /** A tracker that starts playing [stream] at [fromMs] and sees X at the same moment. */
    private fun ScrobbleTracker.listen(fromMs: Long, startedAt: Long, duration: Long, stream: String = MYATA): Long? {
        onPlaying(true, stream, fromMs)
        return onObservation(obs(startedAt, duration, fromMs, stream), fromMs)
    }

    // ---- the store itself ----

    @Test
    fun `the mark covers the emitted occurrence and every older one on its stream`() {
        val store = ScrobbleEmissions()
        store.markEmitted(OccurrenceId(MYATA, 100))
        assertTrue(store.hasEmitted(OccurrenceId(MYATA, 100)))
        assertTrue("older: conservatively treated as emitted", store.hasEmitted(OccurrenceId(MYATA, 90)))
        assertFalse("newer is free", store.hasEmitted(OccurrenceId(MYATA, 101)))
        assertFalse("other streams are independent", store.hasEmitted(OccurrenceId(GOLD, 100)))
        store.markEmitted(OccurrenceId(MYATA, 50))
        assertFalse("a late older mark never lowers the watermark", store.hasEmitted(OccurrenceId(MYATA, 101)))
        assertTrue(store.hasEmitted(OccurrenceId(MYATA, 100)))
    }

    // ---- across service recreation ----

    @Test
    fun `a recreated service never emits the same occurrence twice`() {
        val store = ScrobbleEmissions()

        // Service instance A: hears X (600 s, threshold 240 s) to eligibility.
        val a = tracker(store)
        a.listen(0, startedAt = st(0), duration = 600)
        a.onTick(240_000)
        assertEquals(1, candidates.size)
        a.release()                                         // the service is destroyed

        // Service instance B, same process: X is still on air with 350 s left.
        val b = tracker(store)
        val next = b.listen(250_000, startedAt = st(0), duration = 600)
        assertNull("nothing to wait for: X is already emitted", next)
        b.onTick(490_000)                                   // 240 s of X heard again
        b.onTick(600_000)
        assertEquals("still exactly one candidate for X", 1, candidates.size)
    }

    @Test
    fun `a genuinely newer occurrence still emits after recreation`() {
        val store = ScrobbleEmissions()
        val a = tracker(store)
        a.listen(0, startedAt = st(0), duration = 600)
        a.onTick(240_000)
        a.release()

        val b = tracker(store)
        b.listen(250_000, startedAt = st(0), duration = 600)
        b.onObservation(obs(st(600), 300, 600_000), 600_000) // Y follows X
        b.onTick(750_000)
        assertEquals(2, candidates.size)
        assertEquals(st(600), candidates[1].startedAt)
    }

    @Test
    fun `streams stay independent across recreation`() {
        val store = ScrobbleEmissions()
        val a = tracker(store)
        a.listen(0, startedAt = st(0), duration = 600)
        a.onTick(240_000)
        a.release()

        // The same started_at number on another station is another occurrence.
        val b = tracker(store)
        b.listen(0, startedAt = st(0), duration = 600, stream = GOLD)
        b.onTick(240_000)
        assertEquals(2, candidates.size)
        assertEquals(GOLD, candidates[1].streamId)
    }

    @Test
    fun `partial listening does not survive recreation`() {
        val store = ScrobbleEmissions()
        val a = tracker(store)
        a.listen(0, startedAt = st(0), duration = 600)
        a.onTick(200_000)                                   // 200 of 240 s, not yet
        assertEquals(0, candidates.size)
        a.release()

        val b = tracker(store)
        val next = b.listen(300_000, startedAt = st(0), duration = 600)
        assertEquals("from zero: the 200 s heard by A are gone", 240_000L, next)
        b.onTick(539_999)
        assertEquals(0, candidates.size)
        b.onTick(540_000)
        assertEquals(1, candidates.size)
    }

    @Test
    fun `a fresh store - a new process - may emit again, until P5 persists dedupe`() {
        val a = tracker(ScrobbleEmissions())
        a.listen(0, startedAt = st(0), duration = 600)
        a.onTick(240_000)
        a.release()

        // Process death clears the in-memory record by design; P5's queue is the
        // persistent layer that will stop this.
        val b = tracker(ScrobbleEmissions())
        b.listen(250_000, startedAt = st(0), duration = 600)
        b.onTick(490_000)
        assertEquals(2, candidates.size)
        assertEquals(candidates[0].occurrenceId, candidates[1].occurrenceId)
    }

    @Test
    fun `the service's store is one process-wide instance`() {
        assertTrue(ScrobbleEmissions.process === ScrobbleEmissions.process)
    }
}
