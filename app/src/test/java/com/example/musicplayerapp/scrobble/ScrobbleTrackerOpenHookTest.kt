package com.example.musicplayerapp.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the tracker says an airing has opened (G6b P6b) - and that listening and
 * eligibility are exactly what they were without anyone listening to it.
 *
 * Same time model as ScrobbleTrackerTest: elapsed 0 is server second [S0].
 */
class ScrobbleTrackerOpenHookTest {

    private companion object {
        const val S0 = 1_789_000_000L
        const val MYATA = "myata"
        const val GOLD = "gold"
    }

    private var enabled = true
    private val opened = mutableListOf<OpenedOccurrence>()
    private val candidates = mutableListOf<ScrobbleCandidate>()

    private fun tracker(hook: (OpenedOccurrence) -> Unit = { opened += it }) = ScrobbleTracker(
        isEnabled = { enabled },
        sink = { candidates += it },
        emissions = ScrobbleEmissions(),
        onOccurrenceOpened = hook,
    )

    private fun st(s: Long) = S0 + s

    private fun ScrobbleTracker.see(ms: Long, startedAt: Long, duration: Long = 180, stream: String = MYATA) =
        onObservation(FeedObservation(stream, "Artist", "Title", startedAt, duration, startedAt + duration, S0 + ms / 1000), ms)

    @Test
    fun `a newly opened airing is announced once, with what Now Playing needs`() {
        val t = tracker()
        t.onPlaying(true, MYATA, 0)
        t.see(0, st(-20), duration = 240)
        assertEquals(1, opened.size)
        val o = opened.single()
        assertEquals(OccurrenceId(MYATA, st(-20)), o.occurrenceId)
        assertEquals("Artist", o.artist)
        assertEquals("Title", o.title)
        assertEquals(240L, o.durationSec)
    }

    @Test
    fun `repeated polls, pause and resume, and reconnects of the same airing are not new`() {
        val t = tracker()
        t.onPlaying(true, MYATA, 0)
        t.see(0, st(0))
        t.see(10_000, st(0))
        t.onPlaying(false, MYATA, 20_000)                  // pause, buffering, reconnect
        t.onPlaying(true, MYATA, 40_000)
        t.see(40_000, st(0))
        t.see(50_000, st(0))
        assertEquals(1, opened.size)
    }

    @Test
    fun `a genuinely new started_at is announced`() {
        val t = tracker()
        t.onPlaying(true, MYATA, 0)
        t.see(0, st(0))
        t.see(180_000, st(180))
        assertEquals(listOf(st(0), st(180)), opened.map { it.occurrenceId.startedAt })
    }

    @Test
    fun `an airing whose window has already ended is not announced`() {
        val t = tracker()
        t.onPlaying(true, MYATA, 0)
        t.see(0, st(-200), duration = 180)                 // ended 20 s ago, feed still fresh
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `an answer that lands after playback stopped is not announced`() {
        val t = tracker()
        t.onPlaying(true, MYATA, 0)
        t.onPlaying(false, MYATA, 5_000)
        t.see(6_000, st(0))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `nothing is announced while tracking is inactive - unconfigured, unlinked, re-auth, TV`() {
        enabled = false
        val t = tracker()
        t.onPlaying(true, MYATA, 0)
        t.see(0, st(0))
        t.see(10_000, st(0))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `linking mid-track announces the airing on the next normal poll`() {
        enabled = false
        val t = tracker()
        t.onPlaying(true, MYATA, 0)
        t.see(0, st(-30))
        enabled = true                                      // linked now
        t.see(40_000, st(-30))                             // the next existing poll
        assertEquals(listOf(st(-30)), opened.map { it.occurrenceId.startedAt })
    }

    @Test
    fun `switching back to the same airing reopens it - dedupe is the sender's job`() {
        val t = tracker()
        t.onPlaying(true, MYATA, 0)
        t.see(0, st(0))
        t.onStreamSelected(GOLD, 10_000)
        t.onStreamSelected(MYATA, 20_000)
        t.see(20_000, st(0))
        assertEquals(2, opened.size)
        assertEquals(opened[0].occurrenceId, opened[1].occurrenceId)
    }

    @Test
    fun `listening and eligibility are exactly the same with a hook, even one that throws`() {
        fun run(hook: (OpenedOccurrence) -> Unit): List<ScrobbleCandidate> {
            candidates.clear()
            val t = tracker(hook)
            t.onPlaying(true, MYATA, 0)
            t.see(0, st(0), duration = 60)
            t.onTick(30_000)
            t.see(60_000, st(60), duration = 100)
            t.onTick(110_000)
            return candidates.toList()
        }
        val quiet = run { }
        val loud = run { throw IllegalStateException("listener failed") }
        assertEquals(2, quiet.size)
        assertEquals(quiet, loud)
    }
}
