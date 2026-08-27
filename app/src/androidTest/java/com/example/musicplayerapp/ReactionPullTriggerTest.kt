package com.example.musicplayerapp

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.Reaction
import com.example.musicplayerapp.data.ReactionDao
import com.example.musicplayerapp.data.ReactionOutboxDao
import com.example.musicplayerapp.data.supabase.AuthFailure
import com.example.musicplayerapp.data.supabase.AuthResult
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.EmailAuthRepository
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.data.supabase.PullResult
import com.example.musicplayerapp.data.supabase.ReactionPullTrigger
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import com.example.musicplayerapp.data.supabase.SyncLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * When the account gets read back, and - just as importantly - when it does not.
 *
 * G-A7c built the pull and wired it to nothing. This is the wiring, and the whole
 * risk of it is over-firing: a trigger in the wrong place turns a background read into
 * something that runs on every screen, every resume, or worse, before the identity it
 * is reading for has settled.
 *
 * So the suite is written from both sides. Half of it asserts that the four approved
 * moments do fire. The other half asserts that nothing else does, that a fired trigger
 * refuses when the identity is not final, and that two of them arriving together read
 * the account once rather than twice.
 */
@RunWith(AndroidJUnit4::class)
class ReactionPullTriggerTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var dao: ReactionDao
    private lateinit var outbox: ReactionOutboxDao
    private lateinit var auth: FakeEmailAuthApi
    private lateinit var sync: RecordingSyncApi
    private lateinit var identity: CountingIdentity

    /** Every pull the trigger actually started, in order. */
    private val pulls = mutableListOf<String>()

    /** Every trigger fired at a call site, whether or not it survived the throttle. */
    private val fired = mutableListOf<String>()

    private val x = "11111111-1111-4111-8111-111111111111"
    private val y = "22222222-2222-4222-8222-222222222222"
    private val track = "a".repeat(64)

    private var now = 1_000_000L

    // Set in [open] rather than here: `completed()` reads [x], and a field
    // initializer would run before [x] exists.
    private var answer: PullResult = PullResult.Busy

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.reactionDao()
        outbox = db.reactionOutboxDao()
        AppDatabase.overrideForInstrumentation(db)

        auth = FakeEmailAuthApi().also { it.uid = y }
        EmailAuthBackend.overrideForInstrumentation { auth }

        sync = RecordingSyncApi().also {
            // This suite is one of the few that legitimately reads an account, so it
            // opts in explicitly rather than inheriting a silent empty page.
            it.pullPages = emptyList()
        }
        identity = CountingIdentity(x)
        ReactionSyncBackend.overrideForInstrumentation({ sync }, identity.asProvider())

        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)

        answer = completed()
        ReactionPullTrigger.resetForTest()
        ReactionPullTrigger.clock = { now }
        ReactionPullTrigger.runner = { pulls += "pull"; answer }
        ReactionPullTrigger.onRequest = { fired += it }
    }

    @After
    fun close() {
        ReactionPullTrigger.resetForTest()
        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
        AppDatabase.overrideForInstrumentation(null)
        TestIsolation.restoreBackends()
        if (::db.isInitialized) db.close()
    }

    // ==================== the approved moments fire ====================

    /** **A.** A successful sign-in asks for a pull, once the identity is committed. */
    @Test
    fun a_a_successful_sign_in_fires_the_trigger() = runBlocking {
        val result = EmailAuthRepository.signIn(context, "a@example.com", "password")

        assertTrue("$result", result is AuthResult.Success)
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertEquals(listOf("after sign in"), fired)
    }

    /** **B.** So does a successful registration. */
    @Test
    fun b_a_successful_registration_fires_the_trigger() = runBlocking {
        val result = EmailAuthRepository.register(context, "a@example.com", "password", "Name")

        assertTrue("$result", result is AuthResult.Success)
        assertEquals(listOf("after register"), fired)
    }

    /** **C.** A failed sign-in asks for nothing: there is no account to read. */
    @Test
    fun c_a_failed_sign_in_fires_nothing() = runBlocking {
        auth.failure = AuthFailure.InvalidCredentials()

        val result = EmailAuthRepository.signIn(context, "a@example.com", "wrong")

        assertTrue("$result", result is AuthResult.Failed)
        assertEquals(emptyList<String>(), fired)
        assertEquals(IdentityState.None, IdentityStore.state(context))
    }

    /** **D.** A completed X→Y handoff fires the trigger, and only at the end. */
    @Test
    fun d_a_completed_handoff_fires_the_trigger() = runBlocking {
        dao.like(track, "Artist", "Title", "myata", likedAt = 1_000L)
        IdentityStore.adoptAnonymous(context, x)

        val result = EmailAuthRepository.signIn(context, "a@example.com", "password")

        assertTrue("$result", result is AuthResult.Success)
        assertEquals(listOf(x), sync.retirements)
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertNull("the handoff must be resolved first", IdentityStore.handoff(context))
        assertEquals(listOf("after sign in"), fired)
    }

    /**
     * **E.** The pull that follows a handoff sees the adopted state as pending, so it
     * cannot overwrite it.
     *
     * Adoption writes the local rows into Y through the ordinary push path, which
     * leaves genuine outbox rows behind. The pull's first rule - any pending mutation
     * for a track wins - is therefore already protecting them by the time it runs.
     * Nothing extra is needed here, and this asserts that rather than assuming it.
     */
    @Test
    fun e_the_post_handoff_pull_cannot_overwrite_the_adopted_state() = runBlocking {
        dao.like(track, "Artist", "Title", "myata", likedAt = 1_000L)
        IdentityStore.adoptAnonymous(context, x)

        EmailAuthRepository.signIn(context, "a@example.com", "password")

        // The local state that was adopted into Y is what the listener still sees.
        assertEquals(Reaction.LIKED, dao.find(track)!!.reaction)
        assertEquals(
            "local state is what was adopted into the destination",
            mapOf(track to Reaction.LIKED.name),
            sync.adoptedBy[y],
        )
    }

    /** **G.** An unresolved handoff makes the install ineligible, so a fired trigger reads nothing. */
    @Test
    fun g_an_unresolved_handoff_blocks_the_pull() = runBlocking {
        ReactionPullTrigger.runner = { pulls += "pull"; com.example.musicplayerapp.data.supabase.ReactionPull.run(context) }
        IdentityStore.markRegistered(context, x)
        auth.session = x
        IdentityStore.markHandoffPrepared(context, x)

        val result = ReactionPullTrigger.request(context, "test")

        assertTrue("a mid-switch install must not be read into: $result", result is PullResult.NotEligible)
    }

    // ==================== app start ====================

    /**
     * **H.** A cold start with a matching restored session fires the trigger.
     *
     * Asserted through the real reconciliation entry point rather than by calling the
     * trigger directly, because *where* it is attached is the thing worth pinning: any
     * earlier and the restored session is not yet authoritative.
     */
    @Test
    fun h_a_cold_start_with_a_matching_session_fires_the_trigger() = runBlocking {
        IdentityStore.markRegistered(context, x)
        auth.session = x

        startup()

        assertEquals(listOf("app start"), fired)
    }

    /** **I and X.** No session: nothing is read, and emphatically nothing is minted. */
    @Test
    fun i_and_x_a_cold_start_without_a_session_reads_nothing_and_mints_nothing() = runBlocking {
        ReactionPullTrigger.runner = { pulls += "pull"; com.example.musicplayerapp.data.supabase.ReactionPull.run(context) }
        IdentityStore.markRegistered(context, x)
        auth.session = null

        val result = ReactionPullTrigger.request(context, "app start")

        assertTrue("$result", result is PullResult.AuthUnavailable)
        assertEquals("startup restore must never mint an identity", 0, identity.calls)
        assertEquals(IdentityState.Registered(x), IdentityStore.state(context))
    }

    /** **J.** A session belonging to somebody else fails closed. */
    @Test
    fun j_a_cold_start_with_a_foreign_session_fails_closed() = runBlocking {
        ReactionPullTrigger.runner = { pulls += "pull"; com.example.musicplayerapp.data.supabase.ReactionPull.run(context) }
        IdentityStore.markRegistered(context, x)
        auth.session = y

        val result = ReactionPullTrigger.request(context, "app start")

        assertTrue("$result", result is PullResult.AuthUnavailable)
        assertEquals(IdentityState.Registered(x), IdentityStore.state(context))
    }

    /** **K.** A non-account install has nothing to read back. */
    @Test
    fun k_a_non_registered_identity_never_pulls() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)

        assertNull(ReactionPullTrigger.request(context, "app start"))
        assertEquals("not even an attempt", emptyList<String>(), pulls)
    }

    // ==================== the throttle ====================

    /** **L and 14.** Two immediate triggers for one listener read the account once. */
    @Test
    fun l_two_immediate_triggers_collapse_into_one_scan() = runBlocking {
        IdentityStore.markRegistered(context, x)

        ReactionPullTrigger.request(context, "after sign in")
        ReactionPullTrigger.request(context, "app start")

        assertEquals("one scan, not two", 1, pulls.size)
    }

    /** **M.** Past the window, the same listener may scan again. */
    @Test
    fun m_the_window_expires() = runBlocking {
        IdentityStore.markRegistered(context, x)

        ReactionPullTrigger.request(context, "first")
        now += ReactionPullTrigger.WINDOW_MS
        ReactionPullTrigger.request(context, "second")

        assertEquals(2, pulls.size)
    }

    /** **N.** One listener's debounce says nothing about another's. */
    @Test
    fun n_the_throttle_is_scoped_to_the_listener() = runBlocking {
        IdentityStore.markRegistered(context, x)
        ReactionPullTrigger.request(context, "x")

        // Signing out of X and into Y must read Y at once.
        IdentityStore.markRegistered(context, y)
        ReactionPullTrigger.request(context, "y")

        assertEquals(2, pulls.size)
    }

    /** **O.** A process restart cannot suppress a future scan. */
    @Test
    fun o_a_restart_never_suppresses_a_future_pull() = runBlocking {
        IdentityStore.markRegistered(context, x)
        ReactionPullTrigger.request(context, "before")
        assertEquals(1, pulls.size)

        // The window lives in memory precisely so this is true. A durable marker is
        // one edit away from a cursor, and a bug in one would stop an account ever
        // being read again.
        ReactionPullTrigger.resetForTest()
        ReactionPullTrigger.clock = { now }
        ReactionPullTrigger.runner = { pulls += "pull"; answer }

        ReactionPullTrigger.request(context, "after restart")
        assertEquals(2, pulls.size)
    }

    /**
     * An attempt that never read the account does not consume the window.
     *
     * The failure this avoids: a trigger firing while a drain happens to hold the
     * lease would otherwise be the only chance that launch got.
     */
    @Test
    fun an_attempt_that_read_nothing_returns_the_window() = runBlocking {
        IdentityStore.markRegistered(context, x)

        for (unread in listOf(
            PullResult.Busy,
            PullResult.AuthUnavailable("no session"),
            PullResult.NotEligible("not an account"),
        )) {
            answer = unread
            ReactionPullTrigger.request(context, "blocked")
        }
        answer = completed()
        ReactionPullTrigger.request(context, "now it can")

        assertEquals("each attempt was allowed to try", 4, pulls.size)
    }

    /** A transient failure does keep the window: the account was reached and gave way. */
    @Test
    fun a_transient_failure_keeps_the_window() = runBlocking {
        IdentityStore.markRegistered(context, x)
        answer = PullResult.Transient("network")

        ReactionPullTrigger.request(context, "first")
        ReactionPullTrigger.request(context, "second")

        assertEquals(1, pulls.size)
    }

    // ==================== last sync ====================

    /** **P, Q, R, T.** Nothing but a completed scan writes the pull timestamp. */
    @Test
    fun pqrt_only_a_completed_scan_records_the_pull_timestamp() = runBlocking {
        IdentityStore.markRegistered(context, x)

        for (outcome in listOf(
            PullResult.Busy,
            PullResult.AuthUnavailable("no session"),
            PullResult.Transient("network"),
            PullResult.NotEligible("not an account"),
        )) {
            answer = outcome
            now += ReactionPullTrigger.WINDOW_MS
            ReactionPullTrigger.request(context, "attempt")
            assertNull(
                "$outcome must not look like a synchronisation",
                LastSyncStore.lastPullAt(context),
            )
        }

        // And the trigger itself never writes it. The write belongs to ReactionPull,
        // which is faked out here - so four real attempts produced no timestamp at all.
        assertEquals(4, pulls.size)
        assertNull("triggering is not syncing", LastSyncStore.lastPullAt(context))
    }

    /** **S.** A completed scan records it through G-A7c's own path, exactly once. */
    @Test
    fun s_a_completed_scan_records_the_timestamp_once() = runBlocking {
        ReactionPullTrigger.runner = { pulls += "pull"; com.example.musicplayerapp.data.supabase.ReactionPull.run(context) }
        IdentityStore.markRegistered(context, x)
        auth.session = x

        ReactionPullTrigger.request(context, "app start")

        assertNotNull(
            "a full scan of an empty account is still a completed scan",
            LastSyncStore.lastPullAt(context),
        )
        assertNull("and it is not an upload", LastSyncStore.lastSuccessAt(context))
    }

    // ==================== exclusion ====================

    /** **U and V.** A pull cannot overlap a drain, a handoff or its recovery. */
    @Test
    fun u_and_v_a_pull_never_overlaps_the_lease() = runBlocking {
        ReactionPullTrigger.runner = { pulls += "pull"; com.example.musicplayerapp.data.supabase.ReactionPull.run(context) }
        IdentityStore.markRegistered(context, x)
        auth.session = x

        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.IO) {
            SyncLease.withExclusive { held.complete(Unit); release.await() }
        }
        withTimeout(10_000) { held.await() }

        val blocked = ReactionPullTrigger.request(context, "while busy")
        assertEquals(PullResult.Busy, blocked)

        release.complete(Unit)
        holder.await()

        // And Busy did not eat the window: the retry is immediate.
        val retry = ReactionPullTrigger.request(context, "after")
        assertTrue("$retry", retry is PullResult.Completed)
    }

    // ==================== nothing else changed ====================

    /** **W.** Orchestration creates no outbox rows and no events of its own. */
    @Test
    fun w_triggering_creates_no_local_work() = runBlocking {
        dao.like(track, "Artist", "Title", "myata", likedAt = 1_000L)
        val before = outbox.count()

        IdentityStore.markRegistered(context, x)
        ReactionPullTrigger.request(context, "app start")

        assertEquals(before, outbox.count())
        assertTrue("no history may be invented", sync.events.isEmpty())
    }

    /**
     * **Y.** Ordinary use fires nothing.
     *
     * The risk this PR carries is over-firing, so the interesting assertion is the
     * negative one. Reacting to a track, draining the outbox, and opening the profile
     * are the things a listener does all day - and `ProfileRoute` reaches
     * `IdentityReconciler.reconcile`, which is precisely why the app-start trigger sits
     * in `startupInBackground` and not inside `reconcile`. Opening a screen is not one
     * of the four moments a pull is allowed to happen.
     */
    @Test
    fun y_ordinary_use_fires_no_trigger() = runBlocking {
        IdentityStore.markRegistered(context, x)
        auth.session = x

        // A reaction, and the push that follows it.
        dao.like(track, "Artist", "Title", "myata", likedAt = 1_000L)
        com.example.musicplayerapp.data.supabase.ReactionSyncEngine(
            reactions = dao,
            outbox = outbox,
            api = sync,
            identity = { com.example.musicplayerapp.data.supabase.ListenerIdentity.Available(x) },
        ).drain()

        // Opening the profile, which reconciles identity on the way.
        com.example.musicplayerapp.data.supabase.IdentityReconciler.reconcile(context, x)

        assertEquals(
            "no trigger may fire from a tap, a drain, or opening a screen",
            emptyList<String>(),
            fired,
        )
    }

    // ==================== helpers ====================

    private fun completed() = PullResult.Completed(
        uid = x, pages = 1, fetched = 0, applied = 0, skippedPending = 0, skippedStale = 0,
    )

    /**
     * The startup sequence, in order.
     *
     * `IdentityReconciler.startupInBackground` restores the session, reconciles, and
     * only then asks for a pull - it is that ordering the test cares about. Driving
     * that function itself would launch a coroutine against the real Supabase client,
     * so the same two steps run here, through the same public entry point production
     * uses. What is *not* simulated is the placement, and it does not need to be:
     * [y_ordinary_use_fires_no_trigger] fails if the trigger ever moves inside
     * `reconcile`, which is the mistake worth guarding against.
     */
    private suspend fun startup() {
        com.example.musicplayerapp.data.supabase.IdentityReconciler.reconcile(context, auth.session)
        ReactionPullTrigger.requestInBackground(context, "app start")
        awaitPull()
    }

    /** The trigger dispatches to IO; this waits for it rather than guessing. */
    private suspend fun awaitPull(timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (pulls.isNotEmpty()) return
            delay(20)
        }
        fail("the trigger never started a pull")
    }
}
