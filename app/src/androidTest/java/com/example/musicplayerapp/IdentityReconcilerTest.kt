package com.example.musicplayerapp

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.ReactionDao
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.data.supabase.AuthAttempt
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.HandoffStage
import com.example.musicplayerapp.data.supabase.IdentityHandoff
import com.example.musicplayerapp.data.supabase.IdentityReconciler
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a cold start makes of a device whose disk and session disagree.
 *
 * Process death is simulated the only way an in-process test can, and the same way
 * `IdentityHandoffTest` does it: drive the durable state to a chosen point, throw away
 * the in-memory world, and run recovery against what is left on disk - which is
 * exactly the information a cold start has and no more.
 *
 * The interesting half of this file is not the promotions. It is the pair of cases
 * that look identical on disk and want opposite repairs - a sign-in that died before
 * its commit, and a logout that died before clearing its token - and the marker that
 * tells them apart.
 */
@RunWith(AndroidJUnit4::class)
class IdentityReconcilerTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var dao: ReactionDao
    private lateinit var auth: FakeEmailAuthApi
    private lateinit var sync: RecordingSyncApi
    private lateinit var identity: CountingIdentity

    private val x = "11111111-1111-4111-8111-111111111111"
    private val y = "22222222-2222-4222-8222-222222222222"
    private val z = "33333333-3333-4333-8333-333333333333"

    private val depeche = TrackKey.of("Depeche Mode", "Enjoy the Silence")!!

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.reactionDao()
        AppDatabase.overrideForInstrumentation(db)

        auth = FakeEmailAuthApi().also { it.uid = y }
        EmailAuthBackend.overrideForInstrumentation { auth }

        sync = RecordingSyncApi()
        identity = CountingIdentity(x)
        ReactionSyncBackend.overrideForInstrumentation({ sync }, identity.asProvider())

        IdentityStore.clearForTest(context)
    }

    @After
    fun close() {
        IdentityStore.clearForTest(context)
        AppDatabase.overrideForInstrumentation(null)
        TestIsolation.restoreBackends()
        db.close()
    }

    private suspend fun like(key: String, at: Long = 1_000L) =
        dao.like(key, "Artist", "Title", "myata", at, at)

    // ==================== G. the handoff case ====================

    /**
     * The case the owner named: `SWITCH_PENDING(X)` on disk, and the session that
     * comes back is somebody else.
     *
     * The disk cannot say whether the destination was ever created. The session can,
     * and a session for a uid that is not the source can only mean the switch took.
     */
    @Test
    fun g_a_switch_pending_handoff_with_a_destination_session_completes_forward() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        like(depeche)
        // The last durable thing written before the destination was authenticated.
        IdentityStore.markHandoffSwitchPending(context, x)

        val outcome = IdentityReconciler.reconcile(context, sessionUid = y)

        assertEquals(
            IdentityReconciler.Outcome.HandoffResolved(IdentityHandoff.Result.Switched(y)),
            outcome,
        )
        // REGISTERED(Y) and SWITCHED were committed together, so the pair cannot tear.
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertEquals(setOf(depeche), sync.adoptedBy.getValue(y).keys)
        assertTrue("adoption invents no history", sync.eventsBy(y).isEmpty())
        assertNull("nothing is owed once the handoff is resolved", IdentityStore.handoff(context))
    }

    @Test
    fun a_handoff_whose_session_is_still_the_source_is_rolled_back() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        like(depeche)
        IdentityStore.markHandoffPrepared(context, x)

        val outcome = IdentityReconciler.reconcile(context, sessionUid = x)

        assertTrue("$outcome", outcome is IdentityReconciler.Outcome.HandoffResolved)
        assertEquals(IdentityState.Anonymous(x), IdentityStore.state(context))
        // X's remote state was rebuilt from Room, which is what PREPARED promises.
        assertEquals(setOf(depeche), sync.adoptedBy.getValue(x).keys)
        assertNull(IdentityStore.handoff(context))
    }

    @Test
    fun a_handoff_with_no_session_defers_and_writes_nothing() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        IdentityStore.markHandoffSwitchPending(context, x)

        val outcome = IdentityReconciler.reconcile(context, sessionUid = null)

        assertTrue("$outcome", outcome is IdentityReconciler.Outcome.Deferred)
        // The record survives so a later start, with a session, can decide.
        assertEquals(HandoffStage.SWITCH_PENDING, IdentityStore.handoff(context)?.stage)
        assertTrue(sync.adoptedBy.isEmpty())
        assertTrue(sync.retirements.isEmpty())
    }

    // ==================== G. the direct-auth case ====================

    @Test
    fun g_an_interrupted_direct_registration_is_completed_forward() = runBlocking {
        // Everything a registration from NONE writes before its remote call, and
        // nothing it writes after one.
        IdentityStore.markAuthAttempt(context, AuthAttempt.REGISTER)

        val outcome = IdentityReconciler.reconcile(context, sessionUid = y)

        assertEquals(IdentityReconciler.Outcome.PromotedToRegistered(y), outcome)
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertNull("a spent marker must not survive", IdentityStore.authAttempt(context))
    }

    /**
     * The same interruption one step further on.
     *
     * `ListenerSession.restore` runs first and fills a NONE install in from whatever
     * session came back, labelling it anonymous - which is right when there is no
     * marker and exactly wrong when there is. The uids match here; the correction is
     * to the *kind*.
     */
    @Test
    fun an_interrupted_registration_that_restore_labelled_anonymous_is_corrected() = runBlocking {
        IdentityStore.markAuthAttempt(context, AuthAttempt.REGISTER)
        IdentityStore.adoptAnonymous(context, y)
        assertEquals(IdentityState.Anonymous(y), IdentityStore.state(context))

        val outcome = IdentityReconciler.reconcile(context, sessionUid = y)

        assertEquals(IdentityReconciler.Outcome.PromotedToRegistered(y), outcome)
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
    }

    @Test
    fun an_interrupted_sign_in_from_a_signed_out_install_is_completed_forward() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        IdentityStore.signOut(context)
        IdentityStore.markAuthAttempt(context, AuthAttempt.SIGN_IN)

        val outcome = IdentityReconciler.reconcile(context, sessionUid = y)

        assertEquals(IdentityReconciler.Outcome.PromotedToRegistered(y), outcome)
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertEquals("no session may be cleared on this path", 0, auth.localSignOuts)
    }

    // ==================== the ambiguity, and how it is removed ====================

    /**
     * The other history behind the same two facts.
     *
     * Signed out, and a live session for the identity that signed out - with no
     * marker, so nothing was being signed into. That is a logout whose token outlived
     * its state, and rule 7 of the frozen contract says the token is the wrong half.
     */
    @Test
    fun a_signed_out_install_with_its_own_stale_session_finishes_the_logout() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        IdentityStore.signOut(context)
        auth.session = x

        val outcome = IdentityReconciler.reconcile(context, sessionUid = x)

        assertEquals(IdentityReconciler.Outcome.LogoutCompleted(x), outcome)
        assertEquals("signing out is not a route back to an identity",
            IdentityState.SignedOut(x), IdentityStore.state(context))
        assertEquals(1, auth.localSignOuts)
    }

    /**
     * The pair that makes the marker load-bearing, side by side.
     *
     * Identical persisted state, identical session, opposite repairs - and the only
     * thing separating them is a bit written before the remote call.
     */
    @Test
    fun the_marker_is_the_only_thing_separating_two_identical_disks() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        IdentityStore.signOut(context)

        // Without it: the token is wrong.
        val logout = IdentityReconciler.reconcile(context, sessionUid = x)
        assertTrue("$logout", logout is IdentityReconciler.Outcome.LogoutCompleted)
        assertEquals(IdentityState.SignedOut(x), IdentityStore.state(context))

        // With it: the disk is wrong.
        IdentityStore.markAuthAttempt(context, AuthAttempt.SIGN_IN)
        val signIn = IdentityReconciler.reconcile(context, sessionUid = x)
        assertEquals(IdentityReconciler.Outcome.PromotedToRegistered(x), signIn)
        assertEquals(IdentityState.Registered(x), IdentityStore.state(context))
    }

    @Test
    fun a_session_for_an_identity_that_never_signed_out_is_a_completed_sign_in() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        IdentityStore.signOut(context)

        // No marker, but the session is not the one that signed out - which a stale
        // logout token cannot be. Something authenticated, and only a completed
        // authentication produces a session for somebody else.
        val outcome = IdentityReconciler.reconcile(context, sessionUid = z)

        assertEquals(IdentityReconciler.Outcome.PromotedToRegistered(z), outcome)
        assertEquals(IdentityState.Registered(z), IdentityStore.state(context))
        assertEquals(0, auth.localSignOuts)
    }

    // ==================== the states that need no repair ====================

    @Test
    fun an_account_whose_session_agrees_is_left_alone() = runBlocking {
        IdentityStore.markRegistered(context, y)

        val outcome = IdentityReconciler.reconcile(context, sessionUid = y)

        assertEquals(IdentityReconciler.Outcome.Consistent, outcome)
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
    }

    @Test
    fun an_anonymous_install_whose_session_agrees_is_left_anonymous() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)

        val outcome = IdentityReconciler.reconcile(context, sessionUid = x)

        assertEquals(IdentityReconciler.Outcome.Consistent, outcome)
        assertEquals("reconciliation is not a way to become registered",
            IdentityState.Anonymous(x), IdentityStore.state(context))
    }

    @Test
    fun a_session_that_disagrees_with_a_stored_account_wins() = runBlocking {
        IdentityStore.markRegistered(context, y)

        // Should not happen outside a handoff. The session is what RLS enforces, so
        // the alternative is a device writing rows it will be refused for.
        val outcome = IdentityReconciler.reconcile(context, sessionUid = z)

        assertEquals(IdentityReconciler.Outcome.PromotedToRegistered(z), outcome)
        assertEquals(IdentityState.Registered(z), IdentityStore.state(context))
    }

    // ==================== nothing is written without a session ====================

    @Test
    fun with_no_session_nothing_is_written_and_the_marker_is_kept() = runBlocking {
        IdentityStore.markAuthAttempt(context, AuthAttempt.REGISTER)

        val outcome = IdentityReconciler.reconcile(context, sessionUid = null)

        assertTrue("$outcome", outcome is IdentityReconciler.Outcome.Deferred)
        assertEquals(IdentityState.None, IdentityStore.state(context))
        // Deliberately kept. A session can fail to restore because the read failed,
        // and discarding the only record of an interrupted sign-in over a transient
        // failure would make the repair unavailable forever.
        assertNotNull(IdentityStore.authAttempt(context))
    }

    @Test
    fun a_quiet_install_with_no_session_and_no_marker_is_simply_consistent() = runBlocking {
        val outcome = IdentityReconciler.reconcile(context, sessionUid = null)

        assertEquals(IdentityReconciler.Outcome.Consistent, outcome)
        assertEquals(IdentityState.None, IdentityStore.state(context))
    }

    // ==================== I. reconciliation never mints ====================

    @Test
    fun i_reconciliation_never_mints_an_anonymous_identity() = runBlocking {
        // Every shape of input a cold start can present, and none of them may end with
        // this install owning an identity it did not already own.
        IdentityReconciler.reconcile(context, sessionUid = null)
        assertEquals(IdentityState.None, IdentityStore.state(context))

        IdentityStore.markAuthAttempt(context, AuthAttempt.SIGN_IN)
        IdentityReconciler.reconcile(context, sessionUid = null)
        assertEquals(IdentityState.None, IdentityStore.state(context))

        assertEquals(
            "the identity boundary is the only thing that can mint, and nothing here " +
                "may reach it",
            0,
            identity.calls,
        )
    }

    /**
     * A fresh install with a session and no marker keeps whatever `restore` decided.
     *
     * Reconciliation is not a second opinion on an anonymous session: it repairs
     * disagreements, and there is none here.
     */
    @Test
    fun a_session_with_no_marker_does_not_become_an_account() = runBlocking {
        val outcome = IdentityReconciler.reconcile(context, sessionUid = x)

        assertEquals(IdentityReconciler.Outcome.Consistent, outcome)
        assertEquals(IdentityState.None, IdentityStore.state(context))
    }
}
