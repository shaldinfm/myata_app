package com.example.musicplayerapp.data.supabase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `IdentityState.isAccount` - one predicate, read from both sides of the demotion
 * guard.
 *
 * ## Why this is worth a test of its own
 *
 * G-A7 live validation logged `refusing to demote an account state to ANONYMOUS` at
 * WARN on every push, every drain and every session restore of a perfectly healthy
 * registered install. The guard was right; it was simply being asked a question the
 * happy path already knew the answer to, so [ListenerSession] now checks before
 * asking and the guard is left to mean what it says.
 *
 * That split the one rule across two files, which is the part worth pinning. If the
 * caller's idea of "an account" ever grew wider than the guard's, it would skip a
 * write that should have happened; narrower, and the noise comes back. Both read this
 * property, so the set below is the whole contract and neither side can drift from it
 * without this failing.
 */
class IdentityAccountStateTest {

    private val uid = "11111111-1111-4111-8111-111111111111"

    @Test
    fun `the account states are exactly the three the guard refuses`() {
        assertTrue(IdentityState.Registered(uid).isAccount)
        assertTrue(IdentityState.EmailVerified(uid).isAccount)
        assertTrue(IdentityState.EmailPending(uid, "n@example.com").isAccount)
    }

    @Test
    fun `nothing else is an account`() {
        assertFalse(IdentityState.None.isAccount)
        assertFalse(IdentityState.Anonymous(uid).isAccount)
        // Reached from an account and deliberately not one: it records that this
        // install is not currently anybody. Every path that consults isAccount has
        // already short-circuited on SignedOut before it gets here.
        assertFalse(IdentityState.SignedOut(uid).isAccount)
    }

    @Test
    fun `an anonymous install still has something to adopt`() {
        // The other half of the same decision, and the reason the skip cannot simply
        // be "the state has a uid". ListenerSession must still reach adoptAnonymous
        // for these two, because they are the states a restored session genuinely
        // fills in - None most of all, which is preferences cleared underneath a live
        // session and the case that stops a duplicate being minted.
        assertFalse(IdentityState.None.isAccount)
        assertFalse(IdentityState.Anonymous(uid).isAccount)
    }

    @Test
    fun `it is independent of whether sync may run`() {
        // Two different questions that happen to agree on four of the six states, so
        // neither is a substitute for the other. SignedOut is the state that separates
        // them: sync is paused and it is not an account.
        assertEquals(false, IdentityState.SignedOut(uid).syncEnabled)
        assertEquals(false, IdentityState.SignedOut(uid).isAccount)
        assertEquals(true, IdentityState.Registered(uid).syncEnabled)
        assertEquals(true, IdentityState.Registered(uid).isAccount)
    }
}
