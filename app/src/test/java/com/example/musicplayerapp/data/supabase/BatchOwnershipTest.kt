package com.example.musicplayerapp.data.supabase

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ownership rule for `apply_reaction_event_batch`, exhaustively.
 *
 * The function accepts no `listener_id` and takes its identity from `auth.uid()`.
 * That is right server-side, and it costs something the direct writes had for free:
 * they sent `listener_id` in the body and every policy compared it against
 * `auth.uid()`, so a batch assembled as **X** and sent on a session that had since
 * become **Y** was refused by the database. The RPC has no column to disagree with,
 * so it would store X's reactions and X's history under Y - legitimately, because Y
 * is who asked.
 *
 * [ownershipVerdict] is the client restoring that comparison. A pure function on two
 * strings, so every case can be stated here rather than inferred from a live run.
 */
class BatchOwnershipTest {

    // Hex letters on purpose: a uid comparison has to be case-sensitive, and an
    // all-digit uid could not tell the difference.
    private val x = "aaaaaaaa-1111-4111-8111-1111111111ab"
    private val y = "bbbbbbbb-2222-4222-8222-2222222222cd"

    /** **A.** The ordinary case: this device is who the batch was built for. */
    @Test
    fun a_matching_session_may_send() {
        assertNull(ownershipVerdict(session = x, expected = x))
    }

    /** **B.** No session at all. Nothing may be sent. */
    @Test
    fun b_an_absent_session_may_not_send() {
        val verdict = ownershipVerdict(session = null, expected = x)
        assertTrue("an absent session must refuse", verdict is SyncOutcome.AuthUnavailable)
    }

    /** **C.** A different listener. Nothing may be sent. */
    @Test
    fun c_a_foreign_session_may_not_send() {
        val verdict = ownershipVerdict(session = y, expected = x)
        assertTrue("a foreign session must refuse", verdict is SyncOutcome.AuthUnavailable)
    }

    /**
     * **D.** Neither refusal is permanent, and that is the whole difference between
     * a batch parked for a day and a batch that delivers the moment the right session
     * comes back.
     *
     * Stated as a type check rather than a string, because what the drain branches on
     * is the type: `AuthUnavailable` counts no attempt and sets no backoff, while
     * `Permanent` parks the track. A wrong session is not the rows' fault.
     */
    @Test
    fun d_a_refusal_is_never_permanent() {
        for (session in listOf(null, y, "", "not-a-uuid")) {
            val verdict = ownershipVerdict(session = session, expected = x)
            assertTrue(
                "session=$session must be AuthUnavailable, never Permanent",
                verdict is SyncOutcome.AuthUnavailable,
            )
        }
    }

    /** The comparison is exact. A prefix, a case change or whitespace is a stranger. */
    @Test
    fun the_comparison_is_exact() {
        for (session in listOf(x.uppercase(), x.dropLast(1), " $x", "$x ")) {
            assertTrue(
                "'$session' must not pass as $x",
                ownershipVerdict(session = session, expected = x) is SyncOutcome.AuthUnavailable,
            )
        }
    }

    /** Whatever else it does, it never invents a reason to succeed. */
    @Test
    fun only_an_exact_match_returns_null() {
        assertNull(ownershipVerdict(session = y, expected = y))
        assertNull(ownershipVerdict(session = x, expected = x))
    }
}
