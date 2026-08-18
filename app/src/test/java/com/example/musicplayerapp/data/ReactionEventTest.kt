package com.example.musicplayerapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reaction vocabulary, and the two rules that decide when an event exists.
 *
 * The bug these tests exist for: removing a Like reported `DISLIKE`, because the
 * vocabulary had no way to say "back to neutral". The wire strings are asserted
 * literally because they are the sheet's schema - the Apps Script writes
 * `params.action` straight through with no whitelist, so a renamed constant would
 * silently split a column's history rather than fail anywhere visible.
 */
class ReactionEventTest {

    @Test
    fun `wire names are the four reaction transitions`() {
        assertEquals("LIKE", ReactionEvent.LIKE.wire)
        assertEquals("UNLIKE", ReactionEvent.UNLIKE.wire)
        assertEquals("DISLIKE", ReactionEvent.DISLIKE.wire)
        assertEquals("UNDISLIKE", ReactionEvent.UNDISLIKE.wire)
        assertEquals(4, ReactionEvent.entries.size)
    }

    @Test
    fun `unlike is not dislike`() {
        // The whole point of the type.
        assertNotEquals(ReactionEvent.DISLIKE, ReactionEvent.UNLIKE)
        assertNotEquals(ReactionEvent.DISLIKE.wire, ReactionEvent.UNLIKE.wire)
    }

    @Test
    fun `the legacy heart variant is gone`() {
        // One removal path used to send "💔 DISLIKE", which the sheet
        // counted as a fourth action of its own.
        assertEquals(
            emptyList<ReactionEvent>(),
            ReactionEvent.entries.filter { it.wire != it.name }
        )
    }

    // ==================== A real insert is a LIKE ====================

    @Test
    fun `a row that was inserted reports LIKE`() {
        assertEquals(ReactionEvent.LIKE, ReactionEvent.afterInsert(1L))
        assertEquals(ReactionEvent.LIKE, ReactionEvent.afterInsert(42L))
    }

    @Test
    fun `an ignored insert reports nothing`() {
        // OnConflictStrategy.IGNORE returns -1: the track was already in the
        // Collection, nothing changed, and a second LIKE would count one opinion twice.
        assertNull(ReactionEvent.afterInsert(ReactionEvent.NO_ROW_INSERTED))
        assertNull(ReactionEvent.afterInsert(-1L))
    }

    // ==================== A real delete is an UNLIKE ====================

    @Test
    fun `a row that was deleted reports UNLIKE, never DISLIKE`() {
        assertEquals(ReactionEvent.UNLIKE, ReactionEvent.afterDelete(1))
        assertNotEquals(ReactionEvent.DISLIKE, ReactionEvent.afterDelete(1))
    }

    @Test
    fun `a delete that matched nothing reports nothing`() {
        assertNull(ReactionEvent.afterDelete(0))
    }

    // ==================== The sequences the screens produce ====================

    @Test
    fun `like then unlike`() {
        // PLAYER: tap to save, tap again to withdraw.
        assertEquals(ReactionEvent.LIKE, ReactionEvent.afterInsert(7L))
        assertEquals(ReactionEvent.UNLIKE, ReactionEvent.afterDelete(1))
    }

    @Test
    fun `remove then undo`() {
        // COLLECTION: swipe away, then "Отменить".
        assertEquals(ReactionEvent.UNLIKE, ReactionEvent.afterDelete(1))
        assertEquals(ReactionEvent.LIKE, ReactionEvent.afterInsert(7L))
    }

    @Test
    fun `a repeated tap on the same state reports nothing either way`() {
        assertNull(ReactionEvent.afterDelete(0))
        assertNull(ReactionEvent.afterInsert(ReactionEvent.NO_ROW_INSERTED))
    }
}
