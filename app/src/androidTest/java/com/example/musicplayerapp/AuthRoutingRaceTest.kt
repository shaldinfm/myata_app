package com.example.musicplayerapp

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.Reaction
import com.example.musicplayerapp.data.ReactionDao
import com.example.musicplayerapp.data.ReactionOutboxDao
import com.example.musicplayerapp.data.supabase.AuthResult
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.EmailAuthRepository
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.ListenerIdentity
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import com.example.musicplayerapp.data.supabase.ReactionSyncEngine
import com.example.musicplayerapp.data.supabase.SyncLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two races around authenticating while a drain is in flight.
 *
 * Both come from the same removal. `apply_reaction_event_batch` takes its identity
 * from `auth.uid()` and accepts no `listener_id`, so the database no longer refuses a
 * batch whose declared owner disagrees with the session. The client checks ownership
 * itself just before the call - and that check is only worth something if nothing can
 * authenticate as somebody else between it and the request leaving.
 *
 * **The uid race.** `EmailAuthRepository.direct` used to take no lease at all. A
 * sign-out and a sign-in as another account could land while a batch built and
 * ownership-checked as X was in flight, and the request would carry the new account's
 * token: X's reactions and X's history stored under Y, legitimately, because Y is who
 * asked.
 *
 * **The routing race.** Deciding the route from a state read, then waiting for the
 * lease, then acting on what was read, is the same bug one level up. A drain that
 * mints an anonymous identity while a sign-in waits would leave that sign-in still
 * believing it had nothing to preserve - and it would authenticate directly and
 * orphan the identity the drain had just created.
 */
@RunWith(AndroidJUnit4::class)
class AuthRoutingRaceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var dao: ReactionDao
    private lateinit var outbox: ReactionOutboxDao
    private lateinit var auth: FakeEmailAuthApi
    private lateinit var sync: RecordingSyncApi
    private lateinit var identity: CountingIdentity

    private val x = "11111111-1111-4111-8111-111111111111"
    private val y = "22222222-2222-4222-8222-222222222222"
    private val track = "a".repeat(64)

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.reactionDao()
        outbox = db.reactionOutboxDao()
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
        if (::db.isInitialized) db.close()
    }

    // ==================== A: the uid race ====================

    /**
     * **A.** While a drain owns the lease, no direct authentication may complete.
     *
     * The drain here is the real engine, blocked inside `applyBatch` - which is
     * exactly where the ownership check has already passed and the request has not
     * yet gone out. That is the window the guard cannot cover on its own, and the
     * lease is what closes it.
     */
    @Test
    fun a_a_direct_sign_in_cannot_authenticate_while_a_drain_holds_the_lease() = runBlocking {
        dao.like(track, "Artist", "Title", "myata", likedAt = 1_000L)

        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = BlockingBatchApi(onCall = { inFlight.complete(Unit); release.await() })

        val drain = async(Dispatchers.IO) {
            ReactionSyncEngine(
                reactions = dao,
                outbox = outbox,
                api = backend,
                identity = { ListenerIdentity.Available(x) },
            ).drain()
        }
        withTimeout(10_000) { inFlight.await() }

        // The drain is inside the RPC, holding the lease. A sign-in starts now.
        val signIn = async(Dispatchers.IO) {
            EmailAuthRepository.signIn(context, "someone@example.com", "password")
        }

        // Give it every chance to get through. It must not.
        repeat(20) { delay(25) }
        assertEquals(
            "no authentication may happen while a batch is in flight for another uid",
            0,
            auth.authCalls,
        )
        assertFalse("and it must still be waiting", signIn.isCompleted)
        assertEquals(
            "the batch is still X's while it is in flight",
            IdentityState.None,
            IdentityStore.state(context),
        )

        // The RPC completes as X, and only then may Y authenticate.
        release.complete(Unit)
        drain.await()

        val result = withTimeout(10_000) { signIn.await() }
        assertTrue("$result", result is AuthResult.Success)
        assertEquals(1, auth.authCalls)
        assertEquals(
            "the batch executed as X, never as Y",
            listOf(x),
            backend.sentAs,
        )
    }

    // ==================== B: the routing race ====================

    /**
     * **B.** A route decided before the lease was held must not be acted on.
     *
     * The sequence the owner named: the sign-in begins while the install has no
     * identity at all, a drain mints an anonymous X before the sign-in can proceed,
     * and the sign-in then gets its turn. It must notice X and hand off, not
     * authenticate directly and leave X behind with rows nobody will ever claim.
     *
     * Staged deterministically by holding the lease while the mint is committed,
     * which is the same interleaving without depending on a scheduler.
     */
    @Test
    fun b_a_stale_none_observation_does_not_orphan_an_anonymous_identity() = runBlocking {
        dao.like(track, "Artist", "Title", "myata", likedAt = 1_000L)
        assertEquals(IdentityState.None, IdentityStore.state(context))

        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.IO) {
            SyncLease.withExclusive { held.complete(Unit); release.await() }
        }
        withTimeout(10_000) { held.await() }

        // The sign-in starts while the install is None, and blocks on the lease.
        val signIn = async(Dispatchers.IO) {
            EmailAuthRepository.signIn(context, "someone@example.com", "password")
        }
        repeat(10) { delay(25) }
        assertFalse(signIn.isCompleted)

        // The drain mints an anonymous identity while the sign-in waits.
        IdentityStore.adoptAnonymous(context, x)
        assertEquals(IdentityState.Anonymous(x), IdentityStore.state(context))

        release.complete(Unit)
        holder.await()

        val result = withTimeout(15_000) { signIn.await() }

        assertTrue("$result", result is AuthResult.Success)
        assertEquals(
            "the fresh anonymous identity has to be retired, not abandoned",
            listOf(x),
            sync.retirements,
        )
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertNull("and nothing may be left half-switched", IdentityStore.handoff(context))

        // The handoff contract, unchanged.
        assertEquals(
            "local state wins and is adopted into the destination",
            mapOf(track to Reaction.LIKED.name),
            sync.adoptedBy[y],
        )
        assertEquals("the Collection survives", Reaction.LIKED, dao.find(track)!!.reaction)
        assertTrue(
            "adoption invents no history",
            sync.eventsBy(y).isEmpty(),
        )
        // Exactly two listeners are ever involved: X, retired, and Y, adopted into.
        // A third would be the orphan this test exists to rule out.
        //
        // `identity.calls` is deliberately not asserted to be zero: the handoff drains
        // before it switches, and a drain has to ask who it is. Asking is not minting -
        // the provider hands back the identity that already exists.
        assertEquals(
            "no third listener may appear",
            setOf(y),
            sync.adoptedBy.keys,
        )
    }

    // ==================== C: the stable routes ====================

    /** **C.** A settled `None` still authenticates directly. */
    @Test
    fun c_stable_none_authenticates_directly() = runBlocking {
        val result = EmailAuthRepository.signIn(context, "someone@example.com", "password")

        assertTrue("$result", result is AuthResult.Success)
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertTrue("nothing to retire", sync.retirements.isEmpty())
    }

    /** **C.** A settled `SignedOut` still authenticates directly, resuming rather than restoring. */
    @Test
    fun c_stable_signed_out_authenticates_directly() = runBlocking {
        dao.like(track, "Artist", "Title", "myata", likedAt = 1_000L)
        IdentityStore.markRegistered(context, x)
        IdentityStore.signOut(context)
        assertEquals(IdentityState.SignedOut(x), IdentityStore.state(context))

        val result = EmailAuthRepository.signIn(context, "someone@example.com", "password")

        assertTrue("$result", result is AuthResult.Success)
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertTrue("a signed-out install has nothing to retire", sync.retirements.isEmpty())
        assertEquals("and its Collection is untouched", Reaction.LIKED, dao.find(track)!!.reaction)
    }

    /** **C.** A settled `Anonymous` still hands off, exactly as before. */
    @Test
    fun c_stable_anonymous_still_hands_off() = runBlocking {
        dao.like(track, "Artist", "Title", "myata", likedAt = 1_000L)
        IdentityStore.adoptAnonymous(context, x)

        val result = EmailAuthRepository.signIn(context, "someone@example.com", "password")

        assertTrue("$result", result is AuthResult.Success)
        assertEquals(listOf(x), sync.retirements)
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertEquals(mapOf(track to Reaction.LIKED.name), sync.adoptedBy[y])
        assertNull(IdentityStore.handoff(context))
    }

    /** Registration from `SignedOut` stays undefined: one person, one account. */
    @Test
    fun c_registration_from_signed_out_remains_undefined() = runBlocking {
        IdentityStore.markRegistered(context, x)
        IdentityStore.signOut(context)

        val result = EmailAuthRepository.register(context, "a@example.com", "password", "Name")

        assertTrue("$result", result is AuthResult.Failed)
        assertEquals(0, auth.authCalls)
        assertEquals(IdentityState.SignedOut(x), IdentityStore.state(context))
    }

    /** The lease is released whatever the route concluded, including a refusal. */
    @Test
    fun the_lease_is_never_left_held() = runBlocking {
        IdentityStore.markRegistered(context, x)

        // Registered is undefined for sign-in: the route refuses under the lease.
        val refused = EmailAuthRepository.signIn(context, "a@example.com", "password")
        assertTrue("$refused", refused is AuthResult.Failed)

        // If the lease had leaked, this would never return.
        val reacquired = withTimeout(5_000) { SyncLease.withExclusive { true } }
        assertTrue("the lease must be released on every route, refusals included", reacquired)
    }
}

/**
 * A sync backend whose batch call blocks, so a test can stand inside the window
 * between the ownership check and the request going out.
 */
private class BlockingBatchApi(
    private val onCall: suspend () -> Unit,
) : com.example.musicplayerapp.data.supabase.ReactionSyncApi {

    /** The listener each batch was sent as. Asserted to contain no stranger. */
    val sentAs = mutableListOf<String>()

    override suspend fun applyBatch(
        trackKey: String,
        events: List<com.example.musicplayerapp.data.ReactionOutboxEntry>,
        current: com.example.musicplayerapp.data.TrackReaction,
        listenerId: String,
    ): com.example.musicplayerapp.data.supabase.BatchOutcome {
        sentAs += listenerId
        onCall()
        return com.example.musicplayerapp.data.supabase.BatchOutcome.Applied(current.asRemote(1L))
    }

    override suspend fun deliverEvent(
        entry: com.example.musicplayerapp.data.ReactionOutboxEntry,
        listenerId: String,
    ) = com.example.musicplayerapp.data.supabase.SyncOutcome.Success

    override suspend fun reconcileCurrentState(
        trackKey: String,
        current: com.example.musicplayerapp.data.TrackReaction?,
        listenerId: String,
    ) = com.example.musicplayerapp.data.supabase.SyncOutcome.Success

    override suspend fun retireAllCurrentState(listenerId: String) =
        com.example.musicplayerapp.data.supabase.SyncOutcome.Success

    /** See the other fakes: an accidental pull must be loud, not plausible. */
    override suspend fun fetchReactionsPage(listenerId: String, afterRev: Long, limit: Int):
        com.example.musicplayerapp.data.supabase.PullPage =
        throw AssertionError("this suite must not pull; fetchReactionsPage was called")
}
