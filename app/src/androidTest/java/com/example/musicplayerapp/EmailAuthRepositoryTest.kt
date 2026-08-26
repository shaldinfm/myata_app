package com.example.musicplayerapp

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.ReactionDao
import com.example.musicplayerapp.data.ReactionOutboxDao
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.data.supabase.AuthFailure
import com.example.musicplayerapp.data.supabase.AuthResult
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.EmailAuthRepository
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import com.example.musicplayerapp.data.supabase.SupabaseEmailAuthApi
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
 * Registering and signing in, from each of the states an install can be in.
 *
 * Real Room, real [com.example.musicplayerapp.data.supabase.ReactionSyncEngine], real
 * [com.example.musicplayerapp.data.supabase.IdentityHandoff] - fake auth and fake
 * PostgREST. That split is deliberate and it is what separates these from
 * `IdentityHandoffTest`: that suite drives the handoff with a drain that simply
 * empties the outbox, because it is testing the handoff. This one lets the **actual**
 * drain run, so the assertions about what reached which identity are assertions about
 * the code that will run on a phone.
 *
 * It is also why the no-synthetic-events rule is asserted as *no events written as the
 * destination* rather than *no events at all*. A real drain writes X's pending events,
 * as it should; the rule is that adoption invents nothing for Y.
 */
@RunWith(AndroidJUnit4::class)
class EmailAuthRepositoryTest {

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

    private val depeche = TrackKey.of("Depeche Mode", "Enjoy the Silence")!!
    private val cave = TrackKey.of("Nick Cave", "Red Right Hand")!!

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
        db.close()
    }

    private suspend fun like(key: String, at: Long = 1_000L) =
        dao.like(key, "Artist", "Title", "myata", at, at)

    // ==================== A. registration from NONE ====================

    @Test
    fun a_registration_from_none_creates_the_account_and_commits_it() = runBlocking {
        assertEquals(IdentityState.None, IdentityStore.state(context))

        val result = EmailAuthRepository.register(context, "listener@example.com", "s3cret!", "Денис")

        assertEquals(AuthResult.Success(y), result)
        assertEquals(
            listOf(FakeEmailAuthApi.SignUp("listener@example.com", "s3cret!", "Денис")),
            auth.signUps,
        )
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))

        // Nothing existed to hand over, so nothing did.
        assertNull("registering from NONE is not a handoff", IdentityStore.handoff(context))
        assertTrue(sync.retirements.isEmpty())
        assertNull("the attempt marker must not outlive the attempt", IdentityStore.authAttempt(context))
    }

    /**
     * The metadata key is a wire contract, not an implementation detail.
     *
     * The Supabase dashboard reads `display_name`, and so will whatever screen shows a
     * listener their own name. A rename here is invisible until somebody's name stops
     * appearing, which is why the constant is pinned by a test rather than trusted.
     */
    @Test
    fun a_registration_stores_the_name_under_the_key_supabase_displays() {
        assertEquals("display_name", SupabaseEmailAuthApi.DISPLAY_NAME)
    }

    // ==================== B and C. sign-in with nothing to hand over ====================

    @Test
    fun b_sign_in_from_none_commits_the_account() = runBlocking {
        val result = EmailAuthRepository.signIn(context, "listener@example.com", "s3cret!")

        assertEquals(AuthResult.Success(y), result)
        assertEquals(1, auth.signIns.size)
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertTrue(sync.retirements.isEmpty())
    }

    @Test
    fun c_sign_in_from_signed_out_resumes_as_the_account() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        IdentityStore.signOut(context)
        assertEquals(IdentityState.SignedOut(x), IdentityStore.state(context))

        val result = EmailAuthRepository.signIn(context, "listener@example.com", "s3cret!")

        assertEquals(AuthResult.Success(y), result)
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        // A signed-out install holds no session, so there is nothing of X's to retire
        // and no handoff to run - the rows simply resume under the account.
        assertTrue(sync.retirements.isEmpty())
        assertNull(IdentityStore.handoff(context))
    }

    // ==================== D. registration from ANONYMOUS ====================

    @Test
    fun d_registration_from_anonymous_routes_through_the_handoff() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        like(depeche)
        like(cave)

        val result = EmailAuthRepository.register(context, "listener@example.com", "s3cret!", "Денис")

        assertEquals(AuthResult.Success(y), result)

        // X's current state was retired, and it happened while X was still the live
        // identity - which is the only moment RLS would have allowed it.
        assertEquals(listOf(x), sync.retirements)

        // Y received the device's current opinion about both tracks.
        assertEquals(setOf(depeche, cave), sync.adoptedBy.getValue(y).keys)

        // The real drain sent X's history as X. Adoption invented nothing for Y.
        assertEquals(2, sync.eventsBy(x).size)
        assertTrue("adoption must write no events", sync.eventsBy(y).isEmpty())

        // Local Room is untouched throughout - it was never the cloud's copy.
        assertEquals(2, dao.allReactions().size)
        assertEquals(0, outbox.count())

        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertNull("the handoff record must be cleared", IdentityStore.handoff(context))
    }

    // ==================== E. sign-in from ANONYMOUS ====================

    @Test
    fun e_sign_in_from_anonymous_gives_the_same_guarantees() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        like(depeche)

        val result = EmailAuthRepository.signIn(context, "listener@example.com", "s3cret!")

        assertEquals(AuthResult.Success(y), result)
        assertEquals(listOf(x), sync.retirements)
        assertEquals(setOf(depeche), sync.adoptedBy.getValue(y).keys)
        assertTrue(sync.eventsBy(y).isEmpty())
        assertEquals(1, dao.allReactions().size)
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertNull(IdentityStore.handoff(context))
    }

    // ==================== F. failure from ANONYMOUS ====================

    @Test
    fun f_an_auth_failure_from_anonymous_rolls_back_and_stays_anonymous() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        like(depeche)
        auth.failure = AuthFailure.InvalidCredentials("Invalid login credentials")

        val result = EmailAuthRepository.signIn(context, "listener@example.com", "wrong")

        // The typed reason survives the handoff, which reports only "a uid, or null".
        assertTrue("$result", result is AuthResult.Failed)
        assertTrue(
            "the form needs the real reason, not the handoff's",
            (result as AuthResult.Failed).failure is AuthFailure.InvalidCredentials,
        )

        // Still X, and X is whole: retired on the way past, then rebuilt from Room.
        assertEquals(IdentityState.Anonymous(x), IdentityStore.state(context))
        assertEquals(listOf(x), sync.retirements)
        assertEquals(setOf(depeche), sync.adoptedBy.getValue(x).keys)
        assertNull("a rolled-back handoff owes nothing", IdentityStore.handoff(context))
        assertTrue("nothing may be attributed to a destination that never existed",
            sync.eventsBy(y).isEmpty())
        assertEquals(1, dao.allReactions().size)
    }

    /**
     * The rule that outranks rolling back.
     *
     * If the remote switch actually happened, a failure report is not permission to
     * undo it. Rolling back here would try to rebuild X's state while holding Y's
     * token - refused by RLS - and would leave a device whose storage says X and whose
     * session says Y, which is precisely the split the handoff exists to prevent.
     */
    @Test
    fun f2_a_failure_report_with_a_live_destination_session_goes_forward_anyway() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        like(depeche)
        auth.failure = AuthFailure.Unknown(detail = "reported a failure after switching")
        auth.sessionDespiteFailure = true

        val result = EmailAuthRepository.signIn(context, "listener@example.com", "s3cret!")

        assertEquals(AuthResult.Success(y), result)
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertEquals(setOf(depeche), sync.adoptedBy.getValue(y).keys)
        assertNull(IdentityStore.handoff(context))
    }

    /**
     * Rule 2 of the frozen handoff contract, reached through the real drain.
     *
     * The outbox cannot be emptied, so the switch is abandoned and **nothing** is
     * written - no retirement, no marker, no state change.
     */
    @Test
    fun f3_an_outbox_that_will_not_drain_abandons_the_switch_untouched() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        like(depeche)
        // No identity for the drain: the engine stops without touching a row.
        val stuck = CountingIdentity(null)
        ReactionSyncBackend.overrideForInstrumentation({ sync }, stuck.asProvider())

        val result = EmailAuthRepository.register(context, "listener@example.com", "s3cret!", "Денис")

        assertTrue("$result", result is AuthResult.Failed)
        assertEquals(IdentityState.Anonymous(x), IdentityStore.state(context))
        assertTrue("nothing may be retired before the outbox is empty", sync.retirements.isEmpty())
        assertNull(IdentityStore.handoff(context))
        assertTrue("the destination must never have been asked for", auth.signUps.isEmpty())
        assertEquals(1, outbox.count())
    }

    // ==================== undefined transitions ====================

    @Test
    fun a_transition_with_no_defined_answer_is_refused_rather_than_guessed() = runBlocking {
        // Registering from a signed-out install: nothing records whether lastUid was
        // anonymous or an account, and a handoff cannot run without X's session. The
        // safe answer is to decline until an owner decides what it should mean.
        IdentityStore.adoptAnonymous(context, x)
        IdentityStore.signOut(context)

        val result = EmailAuthRepository.register(context, "listener@example.com", "s3cret!", "Денис")

        assertTrue("$result", result is AuthResult.Failed)
        assertEquals(IdentityState.SignedOut(x), IdentityStore.state(context))
        assertTrue("a refused transition must not reach the server", auth.signUps.isEmpty())
        assertTrue(sync.retirements.isEmpty())
    }

    @Test
    fun signing_into_a_second_account_from_a_first_is_refused() = runBlocking {
        IdentityStore.markRegistered(context, y)

        val result = EmailAuthRepository.signIn(context, "other@example.com", "s3cret!")

        assertTrue("$result", result is AuthResult.Failed)
        // Retiring a real account's remote state because another account signed in on
        // this device would destroy data the person still uses elsewhere.
        assertTrue(sync.retirements.isEmpty())
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
    }

    // ==================== I. nothing mints an anonymous identity ====================

    @Test
    fun i_no_path_here_mints_an_anonymous_identity() = runBlocking {
        // Registration and sign-in from NONE land on REGISTERED, never on ANONYMOUS -
        // there is no state in between that a crash could be mistaken for.
        EmailAuthRepository.register(context, "listener@example.com", "s3cret!", "Денис")
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))

        IdentityStore.clearForTest(context)
        EmailAuthRepository.signIn(context, "listener@example.com", "s3cret!")
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))

        // A failed authentication leaves NONE as NONE. Failing is not a reason to
        // acquire an identity.
        IdentityStore.clearForTest(context)
        auth.failure = AuthFailure.InvalidCredentials()
        EmailAuthRepository.signIn(context, "listener@example.com", "wrong")
        assertEquals(IdentityState.None, IdentityStore.state(context))
        assertNull("a resolved attempt leaves no marker", IdentityStore.authAttempt(context))
    }

    @Test
    fun a_direct_authentication_never_asks_for_a_listener_identity() = runBlocking {
        // The identity boundary is the only thing in the app that can mint. A path
        // that never reaches it cannot mint by accident, now or after a refactor.
        EmailAuthRepository.register(context, "listener@example.com", "s3cret!", "Денис")

        assertEquals(
            "registering from NONE has no outbox to drain and no identity to ask for",
            0,
            identity.calls,
        )
    }

    @Test
    fun a_failed_direct_authentication_leaves_no_durable_claim_behind() = runBlocking {
        auth.failure = AuthFailure.NetworkFailure("offline")

        val result = EmailAuthRepository.signIn(context, "listener@example.com", "s3cret!")

        assertTrue("$result", result is AuthResult.Failed)
        assertFalse("nothing may be owed after a resolved failure",
            IdentityStore.handoffInProgress(context))
        assertNull(IdentityStore.authAttempt(context))
        assertEquals(IdentityState.None, IdentityStore.state(context))
    }
}
