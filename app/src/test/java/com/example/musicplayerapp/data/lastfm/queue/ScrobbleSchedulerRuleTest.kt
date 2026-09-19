package com.example.musicplayerapp.data.lastfm.queue

import androidx.work.WorkInfo.State
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drain-coalescing rule (G6b P6b): enqueue unless a run exists that has not
 * started yet. Every trigger fires after its row is committed, so a run that has
 * not started will read it; a running or finished one may already have read the
 * queue, so it must get a follow-up.
 */
class ScrobbleSchedulerRuleTest {

    private fun enqueue(vararg states: State) = LastfmScrobbleScheduler.shouldEnqueue(states.toList())

    @Test
    fun `nothing there - enqueue`() = assertTrue(enqueue())

    @Test
    fun `a run waiting to start will read the committed row - skip`() {
        assertFalse("ENQUEUED, e.g. waiting for a network", enqueue(State.ENQUEUED))
        assertFalse("BLOCKED behind a running run", enqueue(State.RUNNING, State.BLOCKED))
    }

    @Test
    fun `a running run may already have read the queue - it gets a follow-up`() {
        assertTrue(enqueue(State.RUNNING))
    }

    @Test
    fun `a finished chain starts again`() {
        for (s in listOf(State.SUCCEEDED, State.FAILED, State.CANCELLED)) assertTrue(s.name, enqueue(s))
    }
}
