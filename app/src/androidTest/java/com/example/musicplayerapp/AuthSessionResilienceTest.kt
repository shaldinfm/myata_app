package com.example.musicplayerapp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.supabase.AccountRefresh
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.KnownAccount
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import com.example.musicplayerapp.data.supabase.StartupAccountGate
import com.example.musicplayerapp.ui.profile.ProfileEntry
import com.example.musicplayerapp.ui.profile.ProfileRoute
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Without a VPN I get thrown out of my account after a while" (3.6.6 report).
 *
 * supabase-kt keeps a session it could not refresh for a *network* reason in storage and
 * retries, but its live status stops being `Authenticated` (an expired token, status
 * `RefreshFailure`; or back from the background, `Initializing` until the refresh lands).
 * Every account surface used to decide on the live status, so an hour after sign-in, on a
 * network that could not reach Supabase, the listener was shown as a guest - with their
 * session still on the device. These pin the difference between that and a session that
 * is really gone, which only a sign-out or a refresh token the **server** refused produces.
 *
 * [FakeEmailAuthApi.sessionLive] = false is the first; `session = null` the second.
 */
@RunWith(AndroidJUnit4::class)
class AuthSessionResilienceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var auth: FakeEmailAuthApi
    private val account = "22222222-2222-4222-8222-222222222222"

    @Before
    fun open() {
        auth = FakeEmailAuthApi().also {
            it.uid = account
            it.accountName = "Денис"
            it.accountEmail = "name@example.com"
        }
        EmailAuthBackend.overrideForInstrumentation { auth } // also resets the startup gate
        ReactionSyncBackend.overrideForInstrumentation({ RecordingSyncApi() }, CountingIdentity(null).asProvider())
        AccountRefresh.resetForTest()
        KnownAccount.forget()
        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)

        IdentityStore.markRegistered(context, account)
        auth.session = account
    }

    @After
    fun close() {
        auth.release()
        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
        TestIsolation.restoreBackends()
    }

    @Test
    fun a_temporary_network_failure_is_not_a_logout() = runBlocking {
        // The access token expired and the refresh cannot reach Supabase.
        auth.sessionLive = false

        assertEquals(
            "an unreachable server must not route a signed-in listener to the guest profile",
            R.id.profile_authenticated,
            ProfileRoute.destination(context),
        )
        val header = ProfileEntry.resolve(context)
        assertTrue("HOME must keep the account, not the guest header: $header", header is ProfileEntry.Header.Account)
        assertEquals("Денис", (header as ProfileEntry.Header.Account).name)

        assertEquals(IdentityState.Registered(account), IdentityStore.state(context))
        assertEquals("nothing may sign out over a network failure", 0, auth.localSignOuts)
        assertTrue("nobody had to sign in again", auth.signIns.isEmpty())
    }

    @Test
    fun an_expired_token_with_a_working_refresh_token_recovers_on_its_own() = runBlocking {
        auth.sessionLive = false
        assertEquals(R.id.profile_authenticated, ProfileRoute.destination(context))

        // The network is back and the plugin's own retry refreshed the session.
        auth.sessionLive = true

        assertEquals(R.id.profile_authenticated, ProfileRoute.destination(context))
        assertEquals(account, auth.currentUid())
        assertEquals(IdentityState.Registered(account), IdentityStore.state(context))
        assertEquals(0, auth.localSignOuts)
        assertTrue(auth.signIns.isEmpty())
    }

    @Test
    fun a_refresh_token_the_server_refused_is_handled_apart_from_a_network_error() = runBlocking {
        // The server answered and refused the refresh token: supabase-kt deleted the stored
        // session. That is the one case the account has really left this device.
        auth.session = null

        assertEquals(
            "no stored session: the guest profile, with its way to sign in again",
            R.id.profile,
            ProfileRoute.destination(context),
        )
        // The identity is not demoted and nothing is signed out by the app - the guest
        // screen's sign-in is what brings the account back (EmailAuthRepository's
        // lost-session route).
        assertEquals(IdentityState.Registered(account), IdentityStore.state(context))
        assertEquals(0, auth.localSignOuts)
    }

    @Test
    fun a_relaunch_with_a_stored_but_unrefreshable_session_starts_as_the_account() {
        auth.sessionLive = false
        StartupAccountGate.resetForTest(timeout = 10_000)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { StartupAccountGate.start(context) }
        val deadline = System.currentTimeMillis() + 10_000
        while (StartupAccountGate.outcome == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }

        assertEquals(StartupAccountGate.Outcome.Account, StartupAccountGate.outcome)
        assertEquals("Денис", KnownAccount.of(IdentityStore.state(context))?.displayName)
        assertEquals(0, auth.localSignOuts)
    }
}
