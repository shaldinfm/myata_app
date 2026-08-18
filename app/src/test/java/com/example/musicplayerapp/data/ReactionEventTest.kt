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

    // ==================== A real save is a LIKE ====================

    @Test
    fun `a save that changed the state reports LIKE`() {
        assertEquals(ReactionEvent.LIKE, ReactionEvent.forLike(changed = true))
    }

    @Test
    fun `a save that changed nothing reports nothing`() {
        // The track was already LIKED, so nothing moved, and a second LIKE would
        // count one opinion twice.
        assertNull(ReactionEvent.forLike(changed = false))
    }

    // ==================== A real withdrawal is an UNLIKE ====================

    @Test
    fun `a withdrawal that changed the state reports UNLIKE, never DISLIKE`() {
        assertEquals(ReactionEvent.UNLIKE, ReactionEvent.forUnlike(changed = true))
        assertNotEquals(ReactionEvent.DISLIKE, ReactionEvent.forUnlike(changed = true))
    }

    @Test
    fun `a withdrawal that changed nothing reports nothing`() {
        assertNull(ReactionEvent.forUnlike(changed = false))
    }

    // ==================== The sequences the screens produce ====================

    @Test
    fun `like then unlike`() {
        // PLAYER: tap to save, tap again to withdraw.
        assertEquals(ReactionEvent.LIKE, ReactionEvent.forLike(changed = true))
        assertEquals(ReactionEvent.UNLIKE, ReactionEvent.forUnlike(changed = true))
    }

    @Test
    fun `remove then undo`() {
        // COLLECTION: swipe away, then "Отменить".
        assertEquals(ReactionEvent.UNLIKE, ReactionEvent.forUnlike(changed = true))
        assertEquals(ReactionEvent.LIKE, ReactionEvent.forLike(changed = true))
    }

    @Test
    fun `a repeated tap on the same state reports nothing either way`() {
        assertNull(ReactionEvent.forUnlike(changed = false))
        assertNull(ReactionEvent.forLike(changed = false))
    }
}
