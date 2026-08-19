package com.example.musicplayerapp.data.supabase

import com.example.musicplayerapp.data.Reaction
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry policy, as a decision table.
 *
 * These are the rules that decide whether a listener's reaction is retried in
 * thirty seconds or parked for a day, and they are pure functions, so they are
 * pinned here rather than inferred from a device run. The status codes are not
 * invented: each was provoked against the live project and the response recorded
 * before any of this was written.
 */
class ReactionSyncPolicyTest {

    // ==================== the failure taxonomy ====================

    @Test
    fun a_row_the_policy_refuses_is_permanent() {
        // Live: inserting an event owned by another listener -> 403 / 42501.
        val outcome = classifyStatus(403, "new row violates row-level security policy")
        assertTrue(outcome is SyncOutcome.Permanent)
        assertEquals(403, (outcome as SyncOutcome.Permanent).status)
    }

    @Test
    fun a_row_the_constraints_refuse_is_permanent() {
        // Live: a malformed track_key, and an unknown event_type -> 400 / 23514.
        for (detail in listOf(
            "violates check constraint \"reaction_events_key_is_trackkey_v1\"",
            "violates check constraint \"reaction_events_event_type_check\"",
        )) {
            assertTrue(classifyStatus(400, detail) is SyncOutcome.Permanent)
        }
    }

    @Test
    fun a_bad_token_is_auth_not_permanent() {
        // Live: a garbage bearer token -> 401 / PGRST301. The row is fine; the
        // session is not. Parking the row for a day over this would be wrong.
        assertTrue(classifyStatus(401, "JWT cryptographic operation failed") is SyncOutcome.AuthUnavailable)
    }

    @Test
    fun a_duplicate_event_is_success() {
        // Live: a plain insert of an event_id that is already there -> 409 / 23505.
        // The history is correct, which is all the drain wanted.
        assertEquals(SyncOutcome.Success, classifyStatus(409, "duplicate key value"))
    }

    @Test
    fun server_trouble_and_rate_limiting_are_transient() {
        assertTrue(classifyStatus(429, "too many requests") is SyncOutcome.Transient)
        for (status in listOf(500, 502, 503, 504)) {
            assertTrue("$status", classifyStatus(status, "") is SyncOutcome.Transient)
        }
    }

    @Test
    fun the_network_being_absent_is_transient() {
        for (failure in listOf(
            IOException("closed"),
            SocketTimeoutException("timeout"),
            UnknownHostException("no dns"),
            RuntimeException("wrapped", IOException("reset")),
        )) {
            assertTrue(failure.toString(), classifyFailure(failure) is SyncOutcome.Transient)
        }
    }

    @Test
    fun an_unrecognised_failure_is_transient_not_permanent() {
        // Deliberately the safe direction. An unknown failure treated as permanent
        // would park somebody's reaction for a day over a bug of ours; treated as
        // transient it costs a few pointless retries.
        assertTrue(classifyFailure(IllegalStateException("who knows")) is SyncOutcome.Transient)
    }

    // ==================== backoff ====================

    @Test
    fun transient_backoff_starts_at_thirty_seconds_and_caps_at_an_hour() {
        assertEquals(30_000L, ReactionSyncEngine.transientBackoff(1))
        assertEquals(60_000L, ReactionSyncEngine.transientBackoff(2))
        assertEquals(120_000L, ReactionSyncEngine.transientBackoff(3))

        // Bounded, so a stuck row never becomes a hot loop and never drifts out to
        // never being tried again either.
        for (attempt in 1..50) {
            val delay = ReactionSyncEngine.transientBackoff(attempt)
            assertTrue("attempt $attempt = $delay", delay in 30_000L..3_600_000L)
        }
        assertEquals(3_600_000L, ReactionSyncEngine.transientBackoff(20))
    }

    @Test
    fun permanent_backoff_is_slow_and_caps_at_a_day() {
        assertEquals(3_600_000L, ReactionSyncEngine.permanentBackoff(1))

        for (attempt in 1..50) {
            val delay = ReactionSyncEngine.permanentBackoff(attempt)
            assertTrue("attempt $attempt = $delay", delay in 3_600_000L..86_400_000L)
        }
        // Capped rather than unbounded: the row is kept, so it has to be retried on
        // some schedule, and a day is slow enough to cost nothing while still
        // healing without an app update if the server side is fixed.
        assertEquals(86_400_000L, ReactionSyncEngine.permanentBackoff(20))
    }

    @Test
    fun a_permanent_failure_waits_far_longer_than_a_transient_one() {
        for (attempt in 1..10) {
            assertTrue(
                ReactionSyncEngine.permanentBackoff(attempt) >
                    ReactionSyncEngine.transientBackoff(attempt)
            )
        }
    }

    // ==================== the wire format ====================

    @Test
    fun timestamps_are_utc_with_a_z_and_never_a_plus() {
        val rendered = ReactionSyncWire.timestamp(1_755_596_000_000L)

        // The '+' is the point. An offset written +00:00 becomes a space when the
        // value is used as a query-string filter, and Postgres then rejects the
        // timestamp - which a live probe reproduced exactly.
        assertTrue(rendered, rendered.endsWith("Z"))
        assertTrue(rendered, '+' !in rendered)
        assertTrue(rendered, rendered.startsWith("2025-08-19T"))
    }

    @Test
    fun epoch_zero_and_far_future_still_render() {
        assertEquals("1970-01-01T00:00:00Z", ReactionSyncWire.timestamp(0L))
        assertTrue(ReactionSyncWire.timestamp(4_102_444_800_000L).endsWith("Z"))
    }

    @Test
    fun neutral_has_no_remote_spelling_because_it_is_absence() {
        assertEquals("LIKED", ReactionSyncWire.remoteReaction(Reaction.LIKED))
        assertEquals("DISLIKED", ReactionSyncWire.remoteReaction(Reaction.DISLIKED))
        // Not "NEUTRAL": the schema's CHECK allows only LIKED and DISLIKED, and a
        // withdrawn reaction is a deleted row.
        assertEquals(null, ReactionSyncWire.remoteReaction(Reaction.NEUTRAL))
    }
}
