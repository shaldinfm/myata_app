package com.example.musicplayerapp

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.supabase.AccountDeletionCleanup
import com.example.musicplayerapp.data.supabase.DeletionStage
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityReconciler
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.data.supabase.ReactionPullTrigger
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import com.example.musicplayerapp.data.supabase.SyncLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The final local cutover, and the two things a reaction tap must never be able to do.
 *
 * `SyncLease` serialises sync against sync. It does **not** serialise a listener
 * tapping Like - that is [com.example.musicplayerapp.data.ReactionWriteGate]'s job - so
 * the deletion cleanup and a tap are concurrent by construction, and the cleanup is the
 * one moment where that concurrency is destructive.
 *
 * Two properties, pulling in opposite directions:
 *
 *  * **a tap must not wait on the network.** `signOutLocal` issues an HTTP
 *    `POST /logout?scope=local` whenever a session exists, so holding the gate across
 *    it would block a Like for a round trip. It runs outside the gate;
 *  * **a tap must not slip into the middle of the cutover.** Purge, forget and
 *    `forgetDeletedAccount` are one gate section, so a tap either precedes it and is
 *    purged, or follows it and is a guest-side action.
 *
 * ## Why final row counts cannot prove this
 *
 * A tap that was correctly excluded and one that interleaved leave **identical** final
 * state: a row present, identity `None`, marker gone. The difference is only visible
 * *from inside* the cutover, which is why [AccountDeletionCleanup.insideCutover] exists
 * and why test B observes through it rather than counting rows afterwards.
 */
@RunWith(AndroidJUnit4::class)
class AccountDeletionCutoverTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var auth: FakeEmailAuthApi
    private lateinit var sync: RecordingSyncApi

    private val x = "11111111-1111-4111-8111-111111111111"
    private val before = "a".repeat(64)
    private val during = "c".repeat(64)

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        AppDatabase.overrideForInstrumentation(db)

        auth = FakeEmailAuthApi().also { it.uid = x; it.session = x }
        EmailAuthBackend.overrideForInstrumentation { auth }

        sync = RecordingSyncApi().also { it.pullPages = emptyList() }
        ReactionSyncBackend.overrideForInstrumentation({ sync }, CountingIdentity(x).asProvider())

        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
        ReactionPullTrigger.resetForTest()

        // An install whose deletion the server has already confirmed.
        IdentityStore.markRegistered(context, x)
        IdentityStore.markDeletionConfirmed(context, "R", x)
        LastSyncStore.recordUploadSuccess(context, x, 1_000L)
    }

    @After
    fun close() {
        AccountDeletionCleanup.insideCutover = null
        ReactionPullTrigger.resetForTest()
        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
        AppDatabase.overrideForInstrumentation(null)
        TestIsolation.restoreBackends()
        if (::db.isInitialized) db.close()
    }

    /**
     * **A. A tap is not blocked by the sign-out, and is purged by the cutover.**
     *
     * The cleanup is held inside `signOutLocal`, exactly where the real one waits on
     * `/logout`. A reaction tapped at that moment must commit immediately - if it did
     * not, a listener would be watching a Like do nothing while a logout hung.
     *
     * And because it committed *before* the gate was taken, the purge must remove it.
     * **This is the assertion the previous two-phase order failed**: it purged first
     * and signed out afterwards, so a tap during the sign-out landed after the purge
     * and survived the deletion, belonging to an account that no longer existed.
     */
    @Test
    fun a_a_tap_during_sign_out_is_not_blocked_and_is_purged() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        auth.signOutStarted = started
        auth.signOutGate = release

        val cleanup = async { AccountDeletionCleanup.run(context, x) }
        started.await()

        // The cleanup is parked in the network call. A tap must not be.
        db.reactionDao().like(before, "Artist", "Title", "myata", likedAt = 1_000L)

        // It committed while the sign-out was still outstanding - the gate is not held.
        assertNotNull("a tap must not wait on /logout", db.reactionDao().find(before))
        assertEquals(1, db.reactionOutboxDao().count())
        assertFalse("the sign-out has not returned yet", release.isCompleted)

        release.complete(Unit)
        val outcome = cleanup.await()

        assertTrue("$outcome", outcome is AccountDeletionCleanup.Outcome.Completed)
        // The pre-cutover row was purged. This is what the old order got wrong.
        assertNull("a pre-cutover reaction must not survive", db.reactionDao().find(before))
        assertEquals(0, db.reactionOutboxDao().count())
        assertEquals(IdentityState.None, IdentityStore.state(context))
    }

    /**
     * **B. A tap cannot commit between the purge and `forgetDeletedAccount`.**
     *
     * Observed from inside the cutover, because that is the only place the two
     * hypotheses differ. A tap is launched and parks on the gate the cleanup holds;
     * the probe then asserts, *while the cutover is still open*, that the tables are
     * empty and the identity has not yet been cleared - so there is no instant at which
     * the tap could have inserted a row that the purge would not remove.
     *
     * Afterwards the tap proceeds, and lands on an install that is already a guest.
     */
    @Test
    fun b_a_tap_cannot_interleave_with_the_cutover() = runBlocking {
        val insideReached = CompletableDeferred<Unit>()
        val letCutoverFinish = CompletableDeferred<Unit>()
        val observed = mutableListOf<String>()

        AccountDeletionCleanup.insideCutover = {
            // Purge has run; forgetDeletedAccount has not.
            observed += "rows=${db.reactionDao().allReactions().size}"
            observed += "identity=${IdentityStore.state(context).javaClass.simpleName}"
            observed += "marker=${IdentityStore.deletion(context)?.stage}"
            insideReached.complete(Unit)
            letCutoverFinish.await()
        }

        val cleanup = async { AccountDeletionCleanup.run(context, x) }
        insideReached.await()

        // A tap arrives mid-cutover and parks on the gate.
        val tap = async { db.reactionDao().like(during, "Artist", "Title", "myata", likedAt = 2_000L) }
        yield()

        // It has not committed, and cannot: the cutover holds the gate.
        assertFalse("the tap must be excluded from the cutover", tap.isCompleted)
        assertEquals(
            "the purge has run and the identity has not been cleared yet",
            listOf("rows=0", "identity=Registered", "marker=CONFIRMED"),
            observed,
        )
        assertNull(db.reactionDao().find(during))

        letCutoverFinish.complete(Unit)
        cleanup.await()
        tap.await()

        // The tap landed only after the cutover, as a guest-side action.
        assertEquals(IdentityState.None, IdentityStore.state(context))
        assertNull(IdentityStore.deletion(context))
        assertNotNull("a queued tap may proceed afterwards", db.reactionDao().find(during))
    }

    /**
     * **C. A failed sign-out changes nothing at all.**
     *
     * Under the new order the sign-out comes first, so a failure stops before any local
     * damage - which the previous order could not offer, having already erased the
     * Collection by then.
     */
    @Test
    fun c_a_failed_sign_out_leaves_everything_untouched() = runBlocking {
        db.reactionDao().like(before, "Artist", "Title", "myata", likedAt = 1_000L)
        auth.signOutSucceeds = false

        val outcome = AccountDeletionCleanup.run(context, x)

        assertTrue("$outcome", outcome is AccountDeletionCleanup.Outcome.Deferred)
        assertEquals(DeletionStage.CONFIRMED, IdentityStore.deletion(context)!!.stage)
        // Room untouched.
        assertNotNull(db.reactionDao().find(before))
        assertEquals(1, db.reactionOutboxDao().count())
        // Per-account facts untouched.
        assertEquals(1_000L, LastSyncStore.lastUploadAt(context, x))
        // Identity never became None.
        assertTrue(IdentityStore.state(context) is IdentityState.Registered)
    }

    /**
     * **D. A death after the session cleared but before the cutover converges.**
     *
     * Modelled as the state such a death leaves: `CONFIRMED` on disk, no session, rows
     * still present. The next reconciliation finishes locally and asks the server
     * nothing - `CONFIRMED` never does.
     */
    @Test
    fun d_a_death_after_sign_out_converges_without_a_server_call() = runBlocking {
        db.reactionDao().like(before, "Artist", "Title", "myata", likedAt = 1_000L)
        auth.session = null

        IdentityReconciler.reconcile(context, sessionUid = null)

        assertEquals(IdentityState.None, IdentityStore.state(context))
        assertNull(IdentityStore.deletion(context))
        assertEquals(0, db.reactionDao().allReactions().size)
        assertEquals(0, db.reactionOutboxDao().count())
        assertNull(LastSyncStore.lastUploadAt(context, x))
        assertEquals("CONFIRMED must never call the server", 0, auth.deleteCalls)
        assertEquals(0, auth.statusCalls)
    }

    /**
     * The cutover runs under `SyncLease` as well, when reached through reconciliation.
     *
     * Both locks matter and neither substitutes for the other: `SyncLease` excludes
     * drains and pulls, the gate excludes taps.
     */
    @Test
    fun e_the_cutover_runs_under_the_sync_lease_too() = runBlocking {
        val leaseHeld = CompletableDeferred<Unit>()
        val releaseLease = CompletableDeferred<Unit>()
        val holder = launch {
            SyncLease.withExclusive {
                leaseHeld.complete(Unit)
                releaseLease.await()
            }
        }
        leaseHeld.await()

        val reconciliation = async { IdentityReconciler.reconcile(context, sessionUid = null) }
        yield()

        // Blocked on the lease: no cleanup has happened.
        assertFalse(reconciliation.isCompleted)
        assertTrue(IdentityStore.state(context) is IdentityState.Registered)

        releaseLease.complete(Unit)
        holder.join()
        reconciliation.await()

        assertEquals(IdentityState.None, IdentityStore.state(context))
    }
}
