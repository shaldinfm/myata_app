package com.example.musicplayerapp

import android.content.Context
import com.example.musicplayerapp.fragments.NEUTRAL_TAG
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.supabase.AuthFailure
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import com.example.musicplayerapp.data.supabase.StartupAccountGate
import com.example.musicplayerapp.data.supabase.StartupAccountGate.Outcome
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * G6a: the launch splash waits for a registered install's **local** session restore, and
 * never longer than [StartupAccountGate.TIMEOUT_MS].
 *
 * Fake auth throughout; [FakeEmailAuthApi.restoreGate] and [FakeEmailAuthApi.restoreThrows]
 * are the plugin's restore. "The first frame after the splash" is observed the way the
 * splash itself decides it: [StartupAccountGate.isHolding] is read on the main thread, and
 * it turns false in the same main-thread step that repaints HOME - so any main-thread read
 * that sees it false sees the header the first released frame draws.
 */
@RunWith(AndroidJUnit4::class)
class StartupGateTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var auth: FakeEmailAuthApi
    private val account = "22222222-2222-4222-8222-222222222222"

    @get:Rule
    val timeout: Timeout = Timeout.builder().withTimeout(120, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    @Before
    fun open() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
            ).close()
        }
        auth = FakeEmailAuthApi().also {
            it.uid = account
            it.accountName = "Денис"
            it.accountEmail = "name@example.com"
        }
        EmailAuthBackend.overrideForInstrumentation { auth } // also resets the gate
        ReactionSyncBackend.overrideForInstrumentation({ RecordingSyncApi() }, CountingIdentity(null).asProvider())
        com.example.musicplayerapp.data.supabase.AccountRefresh.resetForTest()
        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
    }

    @After
    fun close() {
        auth.release()
        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
        TestIsolation.restoreBackends()
    }

    private fun signedIn(avatar: String? = "myata-07") {
        IdentityStore.markRegistered(context, account)
        auth.session = account
        auth.accountAvatar = avatar
    }

    @Test
    fun a_a_fast_restore_releases_the_splash_onto_the_named_header() {
        signedIn()
        withMainActivity {
            val header = awaitRelease()
            assertEquals(Outcome.Account, StartupAccountGate.outcome)
            header.assertNamed("myata-07")
        }
    }

    @Test
    fun b_a_slow_restore_within_the_timeout_keeps_the_splash_until_it_settles() {
        StartupAccountGate.resetForTest(timeout = 10_000)
        signedIn()
        val restore = CompletableDeferred<Unit>().also { auth.restoreGate = it }
        // Not ActivityScenario: its launch waits for the first frame, which is exactly what the
        // held splash withholds, so it would only return once the splash had been released.
        launchWithoutWaitingForAFrame {
            await("the gate waiting on the restore") { auth.restoreWaits >= 1 }
            Thread.sleep(400)
            main {
                assertTrue("still on the splash", StartupAccountGate.isHolding)
                headerOrNull()?.let { assertFalse("never the guest header behind the splash", it.isGuest) }
            }
            restore.complete(Unit)
            val header = awaitRelease()
            assertEquals(Outcome.Account, StartupAccountGate.outcome)
            header.assertNamed("myata-07")
        }
    }

    @Test
    fun c_a_restore_that_throws_releases_the_splash_onto_a_neutral_header() {
        signedIn()
        auth.restoreThrows = IllegalStateException("storage unreadable")
        withMainActivity {
            val started = System.currentTimeMillis()
            val header = awaitRelease()
            assertTrue("released at once, not at the timeout", System.currentTimeMillis() - started < StartupAccountGate.TIMEOUT_MS)
            assertEquals(Outcome.Unresolved, StartupAccountGate.outcome)
            header.assertNeutral()
            Thread.sleep(500)
            main { requireHeader().assertNeutral() }
            // Usable: Settings opens and comes back.
            openSettingsAndSettle()
            main { it.onBackPressedDispatcher.onBackPressed() }
            await("HOME again") { it.currentDestinationIdOrNull() == R.id.home }
            Thread.sleep(300)
            main { requireHeader().assertNeutral() }
        }
        assertEquals(IdentityState.Registered(account), IdentityStore.state(context))
        assertEquals("no sign-out", 0, auth.localSignOuts)
    }

    @Test
    fun d_a_restore_that_never_completes_is_released_by_the_timeout_and_resolves_later() {
        StartupAccountGate.resetForTest(timeout = 400)
        signedIn()
        val restore = CompletableDeferred<Unit>().also { auth.restoreGate = it }
        withMainActivity {
            val header = awaitRelease(timeoutMs = 5_000)
            assertEquals(Outcome.Unresolved, StartupAccountGate.outcome)
            header.assertNeutral()
            Thread.sleep(500)
            main { requireHeader().assertNeutral() }
            // The restore finally settles: HOME's own read draws the account, without navigation.
            restore.complete(Unit)
            await("the named header once the restore settles") {
                it.findViewById<View>(R.id.profile_entry)?.tag == "myata-07" && it.text(R.id.home_greeting) == "Привет, Денис!"
            }
            main { requireHeader().assertNamed("myata-07") }
        }
        assertEquals(0, auth.localSignOuts)
    }

    @Test
    fun d2_a_restore_that_outlives_its_own_narrower_ceiling_is_released_by_it() {
        StartupAccountGate.resetForTest(timeout = 10_000, restoreTimeout = 300)
        signedIn()
        auth.restoreGate = CompletableDeferred()
        withMainActivity {
            val header = awaitRelease(timeoutMs = 5_000)
            assertEquals(Outcome.Unresolved, StartupAccountGate.outcome)
            header.assertNeutral()
        }
        assertEquals(0, auth.localSignOuts)
    }

    @Test
    fun d3_a_blocking_client_construction_cannot_hold_the_splash_past_the_ceiling() {
        // No suspension point: a coroutine timeout alone could not interrupt this.
        StartupAccountGate.resetForTest(timeout = 500)
        signedIn()
        val blocked = java.util.concurrent.CountDownLatch(1).also { auth.prepareBlocksThread = it }
        try {
            withMainActivity {
                val started = System.currentTimeMillis()
                val header = awaitRelease(timeoutMs = 5_000)
                assertTrue("released by the ceiling while construction is still blocked", blocked.count == 1L)
                assertTrue("within about the ceiling", System.currentTimeMillis() - started < 3_000)
                assertEquals(Outcome.Unresolved, StartupAccountGate.outcome)
                // Only the gate's construction is blocked in this fake, so HOME's own read may
                // already have drawn the account; what must never appear is the guest header.
                assertFalse("never a false guest header", header.isGuest)
            }
        } finally {
            blocked.countDown()
        }
        assertEquals(0, auth.localSignOuts)
    }

    @Test
    fun e_a_guest_install_is_never_held_and_gets_the_guest_header() {
        auth.restoreGate = CompletableDeferred() // would hang anybody who waited
        withMainActivity {
            main { assertFalse("a guest is never held", StartupAccountGate.isHolding) }
            assertEquals(Outcome.NotRegistered, StartupAccountGate.outcome)
            await("HOME") { it.currentDestinationIdOrNull() == R.id.home && it.findViewById<View>(R.id.home_greeting) != null }
            main { requireHeader().assertGuest() }
        }
        assertEquals("nobody waited for a session", 0, auth.restoreWaits)
    }

    @Test
    fun f_an_offline_cached_session_opens_as_the_account_without_waiting_for_the_network() {
        signedIn()
        auth.refreshFailure = AuthFailure.NetworkFailure("offline")
        val refresh = CompletableDeferred<Unit>().also { auth.refreshGate = it } // a network that never answers
        withMainActivity {
            val header = awaitRelease()
            assertEquals(Outcome.Account, StartupAccountGate.outcome)
            header.assertNamed("myata-07")
            await("the refresh, held") { auth.refreshCalls >= 1 }
            main { requireHeader().assertNamed("myata-07") }
            auth.refreshGate = null
            refresh.complete(Unit)
            Thread.sleep(500)
            main { requireHeader().assertNamed("myata-07") }
        }
        assertEquals(IdentityState.Registered(account), IdentityStore.state(context))
        assertEquals(0, auth.localSignOuts)
    }

    @Test
    fun g_definitively_no_session_releases_onto_the_guest_header() {
        IdentityStore.markRegistered(context, account)
        auth.session = null
        withMainActivity {
            val header = awaitRelease()
            assertEquals(Outcome.NoAccount, StartupAccountGate.outcome)
            header.assertGuest()
        }
        assertEquals(0, auth.localSignOuts)
    }

    // ==================== helpers ====================

    private fun launchWithoutWaitingForAFrame(body: () -> Unit) {
        context.startActivity(
            android.content.Intent(context, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        try {
            body()
        } finally {
            runCatching { InstrumentationRegistry.getInstrumentation().runOnMainSync { resumedMainActivity().finish() } }
            Thread.sleep(500)
        }
    }

    private class Header(val greeting: String, val greetingVisible: Boolean, val tag: Any?, val glyph: Boolean) {
        val isGuest get() = greetingVisible && greeting == "Привет!" && tag == null
        fun assertNamed(key: String) {
            assertTrue("greeting visible", greetingVisible)
            assertEquals("Привет, Денис!", greeting)
            assertEquals(key, tag)
        }
        fun assertGuest() {
            assertTrue("greeting visible", greetingVisible)
            assertEquals("Привет!", greeting)
            assertNull(tag)
            assertTrue("the person glyph", glyph)
        }
        fun assertNeutral() {
            assertFalse("no greeting while unresolved (was «$greeting»)", greetingVisible)
            assertEquals("the neutral control", NEUTRAL_TAG, tag)
            assertFalse("no glyph while unresolved", glyph)
        }
    }

    private fun headerOrNull(): Header? {
        val activity = resumedMainActivity()
        if (activity.currentDestinationIdOrNull() != R.id.home) return null
        val greeting = activity.findViewById<TextView>(R.id.home_greeting) ?: return null
        val entry = activity.findViewById<android.widget.ImageView>(R.id.profile_entry) ?: return null
        return Header(
            greeting.text.toString(),
            greeting.visibility == View.VISIBLE,
            entry.tag,
            entry.drawable is android.graphics.drawable.VectorDrawable,
        )
    }

    private fun requireHeader(): Header = headerOrNull() ?: error("HOME's header is not on screen")

    /**
     * Until the splash is released with HOME's header on screen, read in the same main-thread
     * step - so what this returns is what the first released frame shows.
     */
    private fun awaitRelease(timeoutMs: Long = 15_000): Header {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var header: Header? = null
            runCatching { main { if (!StartupAccountGate.isHolding) header = headerOrNull() } }
            header?.let { return it }
            Thread.sleep(10)
        }
        fail("the splash was not released within ${timeoutMs}ms; holding=${StartupAccountGate.isHolding} waits=${auth.restoreWaits}")
        error("unreachable")
    }

    private fun await(what: String, timeoutMs: Long = 15_000, check: (MainActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            runCatching { main { ok = check(it) } }
            if (ok) return
            Thread.sleep(20)
        }
        fail("timed out waiting for $what")
    }

    private fun main(block: (MainActivity) -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { block(resumedMainActivity()) } catch (t: Throwable) { failure = t }
        }
        failure?.let { throw it }
    }

    private fun MainActivity.text(id: Int): String = findViewById<TextView>(id)?.text?.toString().orEmpty()
}
