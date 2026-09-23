package com.example.musicplayerapp.data

import com.example.musicplayerapp.ui.sleeptimer.SleepTimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionalSleepTimerUndoTest {
    private val boot = 42
    private val timer = SleepTimerState.Armed(50_000L, 30, false, 1L)
    private val file = mutableMapOf<String, Any>()
    private val raw = MemoryFirstPrefs(file)
    private val transaction = TransactionalUndoPrefs(raw)
    private val undo = SleepTimerStore.Undo(transaction)

    private fun cancel(id: String = "cancel") = undo.recordCancelled(timer, boot, boot, id)

    private fun newProcess(): SleepTimerStore.Undo =
        SleepTimerStore.Undo(TransactionalUndoPrefs(MemoryFirstPrefs(file)))

    @Test fun failedConsumeRollsBackAndSameProcessRetryRestoresTimer() {
        assertTrue(cancel())
        raw.failedCommits = 1
        assertEquals(SleepTimerStore.Consumed.NotConsumed, undo.consumeCancelled(boot, "undo"))
        assertEquals(timer.deadlineElapsedMs, undo.readCancelled(boot)?.timer?.deadlineElapsedMs)
        assertNull(undo.lastUndo())
        assertEquals(raw.onDisk(), raw.visible())
        assertTrue(transaction.healthy)

        val result = undo.consumeCancelled(boot, "undo")
        assertTrue(result is SleepTimerStore.Consumed.Timer)
        assertEquals(timer.deadlineElapsedMs, (result as SleepTimerStore.Consumed.Timer).timer.deadlineElapsedMs)
        assertNull(undo.readCancelled(boot))
        assertEquals("undo", undo.lastUndo())
        assertEquals(raw.onDisk(), raw.visible())
    }

    @Test fun unrelatedSuccessfulEditCannotPersistFailedConsume() {
        assertTrue(cancel())
        raw.failedCommits = 1
        assertEquals(SleepTimerStore.Consumed.NotConsumed, undo.consumeCancelled(boot, "undo"))
        assertTrue(raw.edit(mapOf("unrelated" to "value")))
        assertEquals("cancel", newProcess().readCancelled(boot)?.cancelledBy)
        assertNull(newProcess().lastUndo())
    }

    @Test fun newProcessCanRetryAfterFailedConsume() {
        assertTrue(cancel())
        raw.failedCommits = 1
        assertEquals(SleepTimerStore.Consumed.NotConsumed, undo.consumeCancelled(boot, "undo"))
        val next = newProcess()
        assertEquals("cancel", next.readCancelled(boot)?.cancelledBy)
        assertTrue(next.consumeCancelled(boot, "undo") is SleepTimerStore.Consumed.Timer)
        assertEquals("undo", newProcess().lastUndo())
    }

    @Test fun rollbackFailureFreezesOldStateAndRefusesAllWrites() {
        assertTrue(cancel())
        val confirmed = raw.onDisk()
        val previousAttempts = raw.attemptsSinceFailure
        raw.failedCommits = 2
        assertEquals(SleepTimerStore.Consumed.NotConsumed, undo.consumeCancelled(boot, "undo"))
        assertFalse(transaction.healthy)
        assertEquals("cancel", undo.readCancelled(boot)?.cancelledBy)
        assertNull(undo.lastUndo())
        assertFalse(undo.clearCancelled())
        assertFalse(undo.recordCancelled(timer, boot, boot, "later"))
        assertEquals(SleepTimerStore.Consumed.NotConsumed, undo.consumeCancelled(boot, "again"))
        assertEquals(confirmed, raw.onDisk())
        assertEquals(previousAttempts + 2, raw.attemptsSinceFailure)
        assertEquals("cancel", newProcess().readCancelled(boot)?.cancelledBy)
    }

    @Test fun failedRecordDoesNotBecomeAuthoritative() {
        raw.failedCommits = 1
        assertFalse(cancel())
        assertNull(undo.readCancelled(boot))
        assertNull(newProcess().readCancelled(boot))
        assertEquals(raw.onDisk(), raw.visible())
    }

    @Test fun failedClearKeepsConfirmedSnapshot() {
        assertTrue(cancel())
        raw.failedCommits = 1
        assertFalse(undo.clearCancelled())
        assertEquals("cancel", undo.readCancelled(boot)?.cancelledBy)
        assertEquals("cancel", newProcess().readCancelled(boot)?.cancelledBy)
        assertEquals(raw.onDisk(), raw.visible())
    }

    @Test fun successfulConsumeIsOneDurableEditAndReplaySafeAcrossProcesses() {
        assertTrue(cancel())
        val edits = raw.commits
        assertTrue(undo.consumeCancelled(boot, "undo") is SleepTimerStore.Consumed.Timer)
        assertEquals(edits + 1, raw.commits)
        assertNull(undo.readCancelled(boot))
        assertEquals("undo", undo.lastUndo())
        val next = newProcess()
        assertNull(next.readCancelled(boot))
        assertEquals("undo", next.lastUndo())
        assertEquals(SleepTimerStore.Consumed.Nothing, next.consumeCancelled(boot, "undo"))
        assertTrue(next.recordCancelled(timer, boot, boot, "later"))
        assertTrue(SleepTimerStore.Replay.undoAlreadyConsumed(next.lastUndo(), "undo"))
        assertEquals("later", next.readCancelled(boot)?.cancelledBy)
    }

    /** One process map over a durable file; each successful edit writes the whole map. */
    private class MemoryFirstPrefs(private val file: MutableMap<String, Any>) : TransactionalUndoPrefs.Raw {
        private val process = file.toMutableMap()
        var failedCommits = 0
        var commits = 0
        var attemptsSinceFailure = 0

        override fun read(): Map<String, Any> = process.toMap()

        override fun edit(changes: Map<String, Any?>): Boolean {
            attemptsSinceFailure++
            for ((key, value) in changes) {
                if (value == null) process.remove(key) else process[key] = value
            }
            if (failedCommits > 0) {
                failedCommits--
                return false
            }
            file.clear()
            file.putAll(process)
            commits++
            return true
        }

        fun visible(): Map<String, Any> = process.toMap()
        fun onDisk(): Map<String, Any> = file.toMap()
    }
}
