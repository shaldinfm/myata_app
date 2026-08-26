package com.example.musicplayerapp

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.data.supabase.AuthResult
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.EmailAuthRepository
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.ListenerIdentity
import com.example.musicplayerapp.data.supabase.ListenerSession
import com.example.musicplayerapp.data.supabase.SupabaseConfig
import com.example.musicplayerapp.data.supabase.SupabaseModule
import io.github.jan.supabase.postgrest.postgrest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Email/password auth against the real project - **and it is gated twice on purpose.**
 *
 * ## Why a second gate
 *
 * Every other live suite in this repository can clean up after itself with the key the
 * app already ships: `reactions` rows have a delete policy and RLS scopes it to the
 * listener, so [ReactionSyncLiveTest] tidies its own fixtures. This one cannot. A
 * registered `auth.users` row is removable only through the admin API, which needs the
 * project's **secret** key - a key that must never exist on a developer machine, in
 * this repository, or anywhere near an APK.
 *
 * So a run of this suite leaves rows in production that nothing available here can
 * delete. That is a decision for whoever owns the project, not a side effect of typing
 * a Gradle command, and it gets its own flag:
 *
 * ```
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.liveSupabase=true \
 *   -Pandroid.testInstrumentationRunnerArguments.liveAuthFixtures=true \
 *   "-Pandroid.testInstrumentationRunnerArguments.class=com.example.musicplayerapp.EmailAuthLiveTest"
 * ```
 *
 * Without `liveAuthFixtures=true` every test here skips, including under
 * `liveSupabase=true`. **This suite has deliberately not been run as part of G-A4b2
 * delivery**, because running it would have created exactly the uncleanable fixtures
 * the brief said to stop before.
 *
 * ## What it never does
 *
 * **It sends no mail.** There is no call to `requestPasswordReset` anywhere in this
 * file and there must never be one: the project's SMTP allowance is shared with
 * another production product, and recovery delivery is validated by hand, once, after
 * the owner has configured Custom SMTP. The typed-code half of recovery is covered
 * offline by [AuthRecoveryTest].
 *
 * ## What it leaves behind, and how to remove it
 *
 * Every address is `zz-ga4b2-<uuid>@example.com` - the same `zz-` convention the sync
 * fixtures use, so they sort together and are obvious in a user list. Every created
 * uid and address is logged under [TAG]. `reactions` rows are deleted here;
 * `reaction_events` rows and the `auth.users` rows are not, and cannot be:
 *
 *  - **auth.users** - Dashboard → Authentication → Users, filter `zz-ga4b2`, delete;
 *  - **reaction_events** - owner-side SQL, there is deliberately no client policy.
 */
@RunWith(AndroidJUnit4::class)
class EmailAuthLiveTest {

    private companion object {
        /** Grep this in logcat for everything a run created. */
        const val TAG = "AuthLiveProbe"

        /** The second gate. Not `liveSupabase`, and not implied by it. */
        const val FIXTURES_ARG = "liveAuthFixtures"

        const val WHY =
            "this suite creates auth.users rows that no key in this repository can " +
                "delete. Re-run with -Pandroid.testInstrumentationRunnerArguments." +
                "liveAuthFixtures=true only if you are willing to remove them by hand."

        const val PASSWORD = "zz-ga4b2-Passw0rd!"
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private val created = mutableListOf<String>()

    private val depeche = TrackKey.of("Depeche Mode", "Enjoy the Silence")!!

    @Before
    fun open() {
        LiveSupabase.assumeOptedIn()
        assumeTrue(
            WHY,
            InstrumentationRegistry.getArguments()
                .getString(FIXTURES_ARG)?.trim().equals("true", ignoreCase = true),
        )
        assumeTrue("no Supabase project configured for this build", SupabaseConfig.isConfigured)

        // In-memory Room, so a developer's own Collection is never a test fixture.
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        AppDatabase.overrideForInstrumentation(db)
        IdentityStore.clearForTest(context)
        runBlocking { SupabaseModule.client(context)?.let { EmailAuthBackend.api(context).signOutLocal() } }
    }

    @After
    fun close() {
        // A skipped test still gets its @After. Both gates live in @Before, so on
        // every ordinary run this method is reached having set nothing up - and
        // tearing down what was never built is the one way this suite could fail a
        // build it is designed to stay out of.
        if (!::db.isInitialized) return

        runBlocking {
            // The half that can be cleaned, is. Everything else is reported.
            for (uid in created) {
                runCatching {
                    SupabaseModule.client(context)!!.postgrest.from("reactions").delete {
                        filter { eq("listener_id", uid) }
                    }
                }.onFailure { Log.w(TAG, "could not tidy reactions for $uid: ${it.message}") }
                Log.w(TAG, "LEFT BEHIND - delete this auth.users row by hand: $uid")
            }
            runCatching { EmailAuthBackend.api(context).signOutLocal() }
        }
        IdentityStore.clearForTest(context)
        AppDatabase.overrideForInstrumentation(null)
        TestIsolation.restoreBackends()
        db.close()
    }

    private fun address(): String = "zz-ga4b2-${UUID.randomUUID()}@example.com"

    private fun record(uid: String, email: String) {
        created += uid
        Log.w(TAG, "created $uid for $email")
    }

    // ==================== 1 ====================

    /**
     * Confirm Email off means a sign-up hands back a session, not a promise.
     *
     * The single most load-bearing dashboard setting in v1: with confirmation on, the
     * same call returns a user and no session, the repository reports
     * `EmailNotConfirmed`, and every registration in the app dead-ends.
     */
    @Test
    fun a_fresh_signup_returns_a_usable_session() = runBlocking {
        val email = address()

        val result = EmailAuthRepository.register(context, email, PASSWORD, "ZZ GA4b2")

        assertTrue("registration failed: $result", result is AuthResult.Success)
        val uid = (result as AuthResult.Success).uid
        record(uid, email)

        assertEquals(IdentityState.Registered(uid), IdentityStore.state(context))
        assertEquals("the session is what RLS will enforce", uid, EmailAuthBackend.api(context).currentUid())
    }

    // ==================== 2 ====================

    @Test
    fun a_local_sign_out_and_sign_in_returns_the_same_identity() = runBlocking {
        val email = address()
        val registered = EmailAuthRepository.register(context, email, PASSWORD, "ZZ GA4b2")
        assertTrue("$registered", registered is AuthResult.Success)
        val uid = (registered as AuthResult.Success).uid
        record(uid, email)

        // The frozen logout contract's shape: LOCAL scope, token cleared, state paused.
        assertTrue(EmailAuthBackend.api(context).signOutLocal())
        IdentityStore.signOut(context)
        assertEquals(IdentityState.SignedOut(uid), IdentityStore.state(context))

        val back = EmailAuthRepository.signIn(context, email, PASSWORD)

        assertEquals(
            "signing back in must return the same auth.uid(), or the listener has " +
                "been split in two",
            AuthResult.Success(uid),
            back,
        )
        assertEquals(IdentityState.Registered(uid), IdentityStore.state(context))
    }

    // ==================== 3 ====================

    @Test
    fun an_anonymous_install_can_register_through_the_real_handoff() = runBlocking {
        val anonymous = mintAnonymously()
        db.reactionDao().like(depeche, "Depeche Mode", "Enjoy the Silence", "myata", 1_000L, 1_000L)

        val email = address()
        val result = EmailAuthRepository.register(context, email, PASSWORD, "ZZ GA4b2")

        assertTrue("handoff registration failed: $result", result is AuthResult.Success)
        val uid = (result as AuthResult.Success).uid
        record(uid, email)

        assertTrue("the handoff must produce a different identity", uid != anonymous)
        assertEquals(IdentityState.Registered(uid), IdentityStore.state(context))
        assertNull("nothing may be owed afterwards", IdentityStore.handoff(context))
        assertEquals("local Room is never the cloud's copy", 1, db.reactionDao().allReactions().size)
    }

    // ==================== 4 ====================

    @Test
    fun an_anonymous_install_can_sign_into_an_existing_account_through_the_real_handoff() = runBlocking {
        // An account to sign into, made on a clean install and then stepped away from.
        val email = address()
        val made = EmailAuthRepository.register(context, email, PASSWORD, "ZZ GA4b2")
        assertTrue("$made", made is AuthResult.Success)
        val account = (made as AuthResult.Success).uid
        record(account, email)

        EmailAuthBackend.api(context).signOutLocal()
        IdentityStore.clearForTest(context)

        val anonymous = mintAnonymously()
        db.reactionDao().like(depeche, "Depeche Mode", "Enjoy the Silence", "myata", 2_000L, 2_000L)

        val result = EmailAuthRepository.signIn(context, email, PASSWORD)

        assertEquals(AuthResult.Success(account), result)
        assertTrue(account != anonymous)
        assertEquals(IdentityState.Registered(account), IdentityStore.state(context))
        assertNull(IdentityStore.handoff(context))
        assertEquals(1, db.reactionDao().allReactions().size)
    }

    /**
     * An anonymous identity, through the only boundary allowed to create one.
     *
     * Recorded like any other fixture: it is a real `auth.users` row and it is just as
     * undeletable from here as a registered one.
     */
    private suspend fun mintAnonymously(): String {
        val identity = ListenerSession.identity(context)
        assertTrue("could not mint an anonymous identity: $identity", identity is ListenerIdentity.Available)
        val uid = (identity as ListenerIdentity.Available).uid
        assertNotNull(uid)
        record(uid, "anonymous")
        assertEquals(IdentityState.Anonymous(uid), IdentityStore.state(context))
        return uid
    }
}
