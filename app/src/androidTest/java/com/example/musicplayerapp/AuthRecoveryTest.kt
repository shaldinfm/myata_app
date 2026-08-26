package com.example.musicplayerapp

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.ReactionDao
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.data.supabase.AuthFailure
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.EmailAuthRepository
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import com.example.musicplayerapp.data.supabase.RecoveryResult
import com.example.musicplayerapp.data.supabase.SupabaseEmailAuthApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Password recovery as a domain, with no screens and no mail.
 *
 * The flow is three steps and the middle one is an authentication, which is the fact
 * the whole file is arranged around: asking for a mail changes nothing about who this
 * device is, and typing the code back in changes everything. Getting that boundary
 * wrong in either direction is a real failure - starting a handoff on the strength of
 * somebody typing an address into a form, or ending up as the recovered account while
 * the disk still says anonymous.
 *
 * **No real mail is sent by anything here.** The Maileroo quota behind the project is
 * shared with another product, and every call in this suite stops at a fake.
 */
@RunWith(AndroidJUnit4::class)
class AuthRecoveryTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var dao: ReactionDao
    private lateinit var auth: FakeEmailAuthApi
    private lateinit var sync: RecordingSyncApi
    private lateinit var identity: CountingIdentity

    private val x = "11111111-1111-4111-8111-111111111111"
    private val y = "22222222-2222-4222-8222-222222222222"

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

    // ==================== J. requesting ====================

    @Test
    fun j_requesting_a_reset_reaches_the_recovery_call_and_nothing_else() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        like(depeche)

        val result = EmailAuthRepository.requestPasswordReset(context, "listener@example.com")

        assertEquals(RecoveryResult.Requested, result)
        assertEquals(listOf("listener@example.com"), auth.resetRequests)

        // Nothing else moved. Asking for a mail is not authentication: nobody has
        // proved anything, and the address may not even have an account.
        assertEquals(IdentityState.Anonymous(x), IdentityStore.state(context))
        assertNull("requesting a mail must not start a handoff", IdentityStore.handoff(context))
        assertTrue(sync.retirements.isEmpty())
        assertEquals("no drain, so no identity to ask for", 0, identity.calls)
        assertNull(IdentityStore.authAttempt(context))
    }

    @Test
    fun a_refused_request_is_reported_without_touching_the_identity() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        auth.failure = AuthFailure.RateLimited("over_email_send_rate_limit")

        val result = EmailAuthRepository.requestPasswordReset(context, "listener@example.com")

        assertTrue("$result", result is RecoveryResult.Failed)
        assertTrue((result as RecoveryResult.Failed).failure is AuthFailure.RateLimited)
        assertEquals(IdentityState.Anonymous(x), IdentityStore.state(context))
        assertNull(IdentityStore.handoff(context))
    }

    // ==================== J. verifying ====================

    @Test
    fun j_a_valid_code_reaches_the_password_reset_state() = runBlocking {
        val result = EmailAuthRepository.verifyRecoveryCode(context, "listener@example.com", "123456")

        assertEquals(RecoveryResult.PasswordResetAuthorized(y), result)
        assertEquals(
            listOf(FakeEmailAuthApi.Credentials("listener@example.com", "123456")),
            auth.verifications,
        )
        // The verification established a session, so this install is that account now.
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
    }

    @Test
    fun j_a_wrong_code_and_an_expired_one_keep_their_own_meanings() = runBlocking {
        auth.failure = AuthFailure.InvalidRecoveryCode("not accepted")
        val wrong = EmailAuthRepository.verifyRecoveryCode(context, "listener@example.com", "000000")
        assertTrue("$wrong", (wrong as RecoveryResult.Failed).failure is AuthFailure.InvalidRecoveryCode)

        auth.failure = AuthFailure.RecoveryCodeExpired("otp_expired")
        val stale = EmailAuthRepository.verifyRecoveryCode(context, "listener@example.com", "123456")
        assertTrue("$stale", (stale as RecoveryResult.Failed).failure is AuthFailure.RecoveryCodeExpired)

        // Neither committed an identity, and neither left a claim behind.
        assertEquals(IdentityState.None, IdentityStore.state(context))
        assertNull(IdentityStore.authAttempt(context))
    }

    /**
     * Verification *is* authentication, so it is routed like one.
     *
     * An anonymous install that recovers a password ends up as the recovered account
     * just as surely as one that signs in. Anything else would leave the device
     * holding Y's session while its own storage still said X.
     */
    @Test
    fun verifying_a_code_from_anonymous_goes_through_the_handoff() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        like(depeche)

        val result = EmailAuthRepository.verifyRecoveryCode(context, "listener@example.com", "123456")

        assertEquals(RecoveryResult.PasswordResetAuthorized(y), result)
        assertEquals(listOf(x), sync.retirements)
        assertEquals(setOf(depeche), sync.adoptedBy.getValue(y).keys)
        assertTrue("adoption writes no events", sync.eventsBy(y).isEmpty())
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertNull(IdentityStore.handoff(context))
        assertEquals("local Room is never the cloud's copy", 1, dao.allReactions().size)
    }

    @Test
    fun a_failed_verification_from_anonymous_stays_anonymous() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        like(depeche)
        auth.failure = AuthFailure.RecoveryCodeExpired("otp_expired")

        val result = EmailAuthRepository.verifyRecoveryCode(context, "listener@example.com", "123456")

        assertTrue("$result", (result as RecoveryResult.Failed).failure is AuthFailure.RecoveryCodeExpired)
        assertEquals(IdentityState.Anonymous(x), IdentityStore.state(context))
        // Retired on the way past and rebuilt from Room, exactly as a failed sign-in.
        assertEquals(setOf(depeche), sync.adoptedBy.getValue(x).keys)
        assertNull(IdentityStore.handoff(context))
    }

    // ==================== J. setting the new password ====================

    @Test
    fun j_a_new_password_is_reported_as_set_or_as_refused() = runBlocking {
        EmailAuthRepository.verifyRecoveryCode(context, "listener@example.com", "123456")

        val ok = EmailAuthRepository.updatePassword(context, "a-better-secret")
        assertEquals(RecoveryResult.PasswordUpdated(y), ok)
        assertEquals(listOf("a-better-secret"), auth.passwordUpdates)

        auth.failure = AuthFailure.WeakOrInvalidPassword(listOf("length"), "too short")
        val refused = EmailAuthRepository.updatePassword(context, "x")
        assertTrue("$refused", refused is RecoveryResult.Failed)
        val failure = (refused as RecoveryResult.Failed).failure
        assertTrue("$failure", failure is AuthFailure.WeakOrInvalidPassword)
        assertEquals(
            "the rule that was missed is what a form has to point at",
            listOf("length"),
            (failure as AuthFailure.WeakOrInvalidPassword).reasons,
        )
    }

    @Test
    fun setting_a_password_changes_no_identity() = runBlocking {
        IdentityStore.markRegistered(context, y)
        auth.session = y

        EmailAuthRepository.updatePassword(context, "a-better-secret")

        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertNull(IdentityStore.handoff(context))
        assertTrue(sync.retirements.isEmpty())
    }

    // ==================== the wire contract ====================

    /**
     * The OTP type, pinned against the string GoTrue expects.
     *
     * `OtpType.Email` has six entries and choosing the wrong one compiles perfectly,
     * then rejects every code a listener ever types - a failure that would look like a
     * mail-template problem for as long as anybody cared to look.
     */
    @Test
    fun the_recovery_code_is_verified_as_a_recovery_token() {
        assertEquals("recovery", SupabaseEmailAuthApi.RECOVERY_OTP.type)
    }

    // ==================== I. recovery mints nothing ====================

    @Test
    fun i_no_recovery_step_mints_an_anonymous_identity() = runBlocking {
        EmailAuthRepository.requestPasswordReset(context, "listener@example.com")
        assertEquals(IdentityState.None, IdentityStore.state(context))

        auth.failure = AuthFailure.InvalidRecoveryCode()
        EmailAuthRepository.verifyRecoveryCode(context, "listener@example.com", "000000")
        assertEquals(IdentityState.None, IdentityStore.state(context))

        EmailAuthRepository.updatePassword(context, "irrelevant")
        assertEquals(IdentityState.None, IdentityStore.state(context))

        assertEquals("nothing in recovery may reach the minting boundary", 0, identity.calls)
    }
}
