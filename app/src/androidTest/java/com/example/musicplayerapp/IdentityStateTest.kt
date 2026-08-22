package com.example.musicplayerapp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.ListenerIdentity
import com.example.musicplayerapp.data.supabase.ListenerSession
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The persisted identity state machine.
 *
 * **Nothing here contacts Supabase.** Every case is either pure storage or a path
 * that returns before the client is reached - which is itself part of what is being
 * asserted, since `SIGNED_OUT` short-circuiting *before* any auth call is the whole
 * reason a paused install costs nothing.
 *
 * Minting a real identity is the one thing that cannot be tested without the network,
 * so it lives in the opt-in `SupabaseFoundationTest` instead.
 */
@RunWith(AndroidJUnit4::class)
class IdentityStateTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val uid = "11111111-2222-3333-4444-555555555555"
    private val other = "99999999-8888-7777-6666-555555555555"

    @Before
    fun freshInstall() = IdentityStore.clearForTest(context)

    @After
    fun tidy() = IdentityStore.clearForTest(context)

    // ==================== the six states ====================

    @Test
    fun a_fresh_install_is_none() {
        assertEquals(IdentityState.None, IdentityStore.state(context))
        assertNull(ListenerSession.knownUid(context))
        assertFalse(ListenerSession.hasKnownIdentity(context))
    }

    @Test
    fun every_state_survives_a_round_trip_through_storage() {
        IdentityStore.adoptAnonymous(context, uid)
        assertEquals(IdentityState.Anonymous(uid), IdentityStore.state(context))

        IdentityStore.markEmailPending(context, uid, "listener@example.com")
        assertEquals(
            IdentityState.EmailPending(uid, "listener@example.com"),
            IdentityStore.state(context),
        )

        IdentityStore.markEmailVerified(context, uid)
        assertEquals(IdentityState.EmailVerified(uid), IdentityStore.state(context))

        IdentityStore.markRegistered(context, uid)
        assertEquals(IdentityState.Registered(uid), IdentityStore.state(context))

        IdentityStore.signOut(context)
        assertEquals(IdentityState.SignedOut(uid), IdentityStore.state(context))
    }

    @Test
    fun every_state_except_signed_out_allows_sync() {
        assertTrue(IdentityState.None.syncEnabled)
        assertTrue(IdentityState.Anonymous(uid).syncEnabled)
        assertTrue(IdentityState.EmailPending(uid, "a@b.c").syncEnabled)
        assertTrue(IdentityState.EmailVerified(uid).syncEnabled)
        assertTrue(IdentityState.Registered(uid).syncEnabled)
        assertFalse(IdentityState.SignedOut(uid).syncEnabled)
    }

    // ==================== legacy migration ====================

    @Test
    fun a_legacy_marker_migrates_to_anonymous() {
        IdentityStore.writeLegacyMarkerForTest(context, uid)

        // The old marker meant "this install owns this uid", and that is exactly what
        // it still means. Registration did not exist when any of them were written.
        assertEquals(IdentityState.Anonymous(uid), IdentityStore.state(context))
        assertEquals(uid, ListenerSession.knownUid(context))
    }

    @Test
    fun a_legacy_marker_migrates_the_same_way_whether_or_not_a_session_can_be_restored() {
        // This is the case the old model got dangerously right by accident and the new
        // one must get right on purpose. There is no session in this test process - no
        // client call is made at all - and the migration must still not conclude that
        // this install is new, because concluding that is how a second uid gets minted
        // for one person over a flat battery.
        IdentityStore.writeLegacyMarkerForTest(context, uid)

        val migrated = IdentityStore.state(context)

        assertEquals(IdentityState.Anonymous(uid), migrated)
        assertTrue("a known identity must never migrate to NONE", migrated !is IdentityState.None)
    }

    @Test
    fun no_marker_and_no_state_is_a_fresh_install_not_a_lost_identity() {
        assertEquals(IdentityState.None, IdentityStore.state(context))
    }

    @Test
    fun state_wins_over_a_legacy_marker_that_is_still_present() {
        IdentityStore.adoptAnonymous(context, uid)
        IdentityStore.signOut(context)

        // adoptAnonymous keeps the legacy key in step for downgrade safety, so the
        // marker still says `uid`. The state must be what is read.
        assertEquals(IdentityState.SignedOut(uid), IdentityStore.state(context))
    }

    // ==================== sign-out ====================

    @Test
    fun signing_out_keeps_the_uid_rather_than_returning_to_none() {
        IdentityStore.adoptAnonymous(context, uid)
        IdentityStore.signOut(context)

        val state = IdentityStore.state(context)
        assertEquals(IdentityState.SignedOut(uid), state)
        // The uid is still known. Losing it is what would let the next sync boundary
        // treat this install as new and orphan everything the old uid owns.
        assertEquals(uid, state.uid)
        assertTrue(IdentityStore.isSignedOut(context))
    }

    @Test
    fun signed_out_survives_a_restart() {
        IdentityStore.adoptAnonymous(context, uid)
        IdentityStore.signOut(context)

        // A process restart re-reads the file rather than any in-memory state. Reading
        // it back off disk is the closest an in-process test gets, and the on-disk
        // assertion below is the other half.
        assertEquals(IdentityState.SignedOut(uid), IdentityStore.state(context))
        assertTrue(prefsFileText().contains("SIGNED_OUT"))
    }

    @Test
    fun a_signed_out_install_never_mints_and_reports_paused_not_failure() = runBlocking {
        IdentityStore.adoptAnonymous(context, uid)
        IdentityStore.signOut(context)

        val who = ListenerSession.identity(context)

        // Paused, not Unavailable. The two travel to opposite places in the worker:
        // one retries on a backoff, the other stops until an explicit sign-in.
        assertTrue("expected Paused, got $who", who is ListenerIdentity.Paused)
        assertEquals(uid, (who as ListenerIdentity.Paused).lastUid)
        // And the state is untouched by having been asked.
        assertEquals(IdentityState.SignedOut(uid), IdentityStore.state(context))
    }

    @Test
    fun repeated_and_concurrent_calls_while_signed_out_all_report_paused() = runBlocking {
        IdentityStore.adoptAnonymous(context, uid)
        IdentityStore.signOut(context)

        val answers = (1..8).map { async { ListenerSession.identity(context) } }.awaitAll()

        assertTrue(answers.all { it is ListenerIdentity.Paused })
        // Nothing was minted by any of them, which is the property that matters: a
        // signed-out install that keeps being asked stays signed out.
        assertEquals(IdentityState.SignedOut(uid), IdentityStore.state(context))
    }

    @Test
    fun a_signed_out_install_does_not_restore_a_session() = runBlocking {
        IdentityStore.adoptAnonymous(context, uid)
        IdentityStore.signOut(context)

        assertNull(ListenerSession.restore(context))
        assertEquals(IdentityState.SignedOut(uid), IdentityStore.state(context))
    }

    @Test
    fun a_known_identity_is_never_replaced_by_the_sync_boundary() = runBlocking {
        IdentityStore.markRegistered(context, uid)

        // Whatever the boundary manages to do - restore a session, fail to, or find
        // no client at all - the one thing it must never do is decide this install is
        // new. The assertion is on the stored state rather than the return value,
        // which makes it true in both normal and opt-in runs.
        val who = ListenerSession.identity(context)

        val after = IdentityStore.state(context)
        assertTrue("a REGISTERED install must not be demoted, got $after", after is IdentityState.Registered)
        assertEquals(uid, after.uid)
        assertTrue("a registered install is not paused", who !is ListenerIdentity.Paused)
    }

    @Test
    fun an_anonymous_install_that_cannot_reach_its_session_keeps_its_uid() = runBlocking {
        IdentityStore.adoptAnonymous(context, uid)

        ListenerSession.identity(context)

        // The old marker got this right and it must keep getting it right: a refresh
        // or network failure is not evidence of a new listener.
        assertEquals(uid, IdentityStore.state(context).uid)
    }

    // ==================== transitions that must be refused ====================

    @Test
    fun an_account_state_is_never_demoted_to_anonymous() {
        IdentityStore.markRegistered(context, uid)

        // An anonymous sign-in landing underneath a real account is a bug, not a
        // transition. Silently demoting would hide it and hand the account's rows to
        // an anonymous identity.
        IdentityStore.adoptAnonymous(context, other)

        assertEquals(IdentityState.Registered(uid), IdentityStore.state(context))
    }

    @Test
    fun email_states_are_not_demoted_either() {
        IdentityStore.markEmailPending(context, uid, "listener@example.com")
        IdentityStore.adoptAnonymous(context, other)
        assertEquals(
            IdentityState.EmailPending(uid, "listener@example.com"),
            IdentityStore.state(context),
        )

        IdentityStore.markEmailVerified(context, uid)
        IdentityStore.adoptAnonymous(context, other)
        assertEquals(IdentityState.EmailVerified(uid), IdentityStore.state(context))
    }

    @Test
    fun signing_out_of_nothing_stays_none() {
        IdentityStore.signOut(context)
        assertEquals(IdentityState.None, IdentityStore.state(context))
    }

    @Test
    fun an_explicit_sign_in_is_the_only_way_out_of_signed_out() {
        IdentityStore.adoptAnonymous(context, uid)
        IdentityStore.signOut(context)

        IdentityStore.resumeAs(context, uid, registered = true)

        assertEquals(IdentityState.Registered(uid), IdentityStore.state(context))
        assertFalse(IdentityStore.isSignedOut(context))
    }

    // ==================== durability ====================

    @Test
    fun a_transition_is_on_disk_before_it_returns() {
        IdentityStore.markRegistered(context, uid)

        // The real assertion of `commit()` over `apply()`, made against the file
        // rather than the in-memory cache - which would be identical either way and
        // would prove nothing. `apply()` flushes on a background thread, so a process
        // death here loses the write and leaves an install that believes it has never
        // had an identity. That is not hypothetical: an API 36 force-stop straight
        // after a sign-in produced exactly that empty file.
        val onDisk = prefsFileText()
        assertTrue("state not yet on disk: $onDisk", onDisk.contains("REGISTERED"))
        assertTrue("uid not yet on disk: $onDisk", onDisk.contains(uid))
    }

    @Test
    fun the_legacy_key_is_kept_in_step_for_a_downgrade() {
        IdentityStore.adoptAnonymous(context, uid)

        // A build without the state machine reads only `listener_uid`. It must still
        // find a marker and still refuse to mint - the safe direction.
        val onDisk = prefsFileText()
        assertTrue(onDisk.contains("listener_uid"))
        assertTrue(onDisk.contains(uid))
    }

    /** The preferences file as it actually exists on disk right now. */
    private fun prefsFileText(): String {
        val file = File(context.applicationInfo.dataDir, "shared_prefs/supabase_identity.xml")
        return if (file.exists()) file.readText() else ""
    }
}
