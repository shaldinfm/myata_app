package com.example.musicplayerapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.PatternMatcher
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.lastfm.LastfmApi
import com.example.musicplayerapp.data.lastfm.LastfmBackend
import com.example.musicplayerapp.data.lastfm.LastfmConfig
import com.example.musicplayerapp.data.lastfm.LastfmRequest
import com.example.musicplayerapp.data.lastfm.LastfmStoredSession
import com.example.musicplayerapp.data.lastfm.LastfmTransportResult
import com.example.musicplayerapp.data.lastfm.PrefsLastfmSessionStore
import com.example.musicplayerapp.ui.lastfm.LastfmAuthLauncher
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Last.fm screen wired to real state: the ViewModel, the fragment, the real
 * `lastfm_session` store - over a scripted transport.
 *
 * Nothing here can reach Last.fm. The transport is [ScriptedApi], installed over
 * the runner's offline one and put back in `@After`; the request factory carries
 * fabricated credentials so signed requests can be *built*; and the browser is never
 * started - [LastfmAuthLauncher] hands the Intent to the test instead. One test uses
 * the real launcher under a blocking monitor, which records the launch and starts
 * nothing.
 */
@RunWith(AndroidJUnit4::class)
class LastfmAuthFlowTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store get() = PrefsLastfmSessionStore(context)

    private val api = ScriptedApi()
    private val launched = Collections.synchronizedList(mutableListOf<Intent>())

    @Before
    fun setUp() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
            ).close()
        }
        store.write(LastfmStoredSession.EMPTY)
        LastfmConfig.configuredOverrideForTest = true
        LastfmBackend.overrideForInstrumentation { api }
        LastfmBackend.overrideRequestsForInstrumentation { LiveLastfm.fakeRequests() }
        LastfmAuthLauncher.launchOverrideForTest = { launched += it }
    }

    @After
    fun tearDown() {
        LastfmAuthLauncher.launchOverrideForTest = null
        // The offline transport, never null - see LiveLastfm.restoreOffline.
        LiveLastfm.restoreOffline()
        LastfmConfig.configuredOverrideForTest = null
        store.write(LastfmStoredSession.EMPTY)
    }

    // ---- connecting --------------------------------------------------------------

    @Test
    fun connect_opens_the_exact_authorization_url_and_commits_the_token_first() {
        api.reply("auth.getToken", """{"token":"tok-flow-1"}""")

        withMainActivity {
            openLastfm()
            tap(R.id.lastfm_action)
            awaitTrue("the browser intent") { launched.size == 1 }

            val intent = launched.single()
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertTrue(intent.categories.contains(Intent.CATEGORY_BROWSABLE))
            assertEquals(
                "https://www.last.fm/api/auth/?api_key=${LiveLastfm.FAKE_API_KEY}&token=tok-flow-1",
                intent.dataString,
            )
            assertEquals("committed before the launch", "tok-flow-1", store.read().pendingToken)

            awaitHeading(R.string.lastfm_card_pending_heading)
            on { a ->
                val second = a.findViewById<TextView>(R.id.lastfm_action_secondary)
                assertEquals(View.VISIBLE, second.visibility)
                assertEquals(a.getString(R.string.lastfm_action_cancel), second.text.toString())
            }
            // Opening the browser is not a resume: nothing is exchanged yet.
            assertEquals(listOf("auth.getToken"), api.calls)
        }
    }

    // ---- resuming ----------------------------------------------------------------

    @Test
    fun resuming_with_a_pending_token_exchanges_once_links_and_shows_the_lifetime_count() {
        seedPending("tok-flow-2")
        api.reply("auth.getSession", SESSION_OK)
        api.reply("user.getInfo", """{"user":{"name":"f0ul482","playcount":"1248"}}""")

        withMainActivity { scenario ->
            openLastfm()
            awaitHeading(R.string.lastfm_card_connected_heading)
            awaitText(R.id.lastfm_body_2, "Всего в Last.fm: 1 248 скробблов")
            on { a -> assertEquals("f0ul482", a.findViewById<TextView>(R.id.lastfm_body_1).text.toString()) }

            assertEquals(
                LastfmStoredSession(sessionKey = "sk-flow", username = "f0ul482"),
                store.read(),
            )

            // Another resume: already linked, so nothing more is exchanged, and the
            // count is not fetched again for the same account.
            cycle(scenario)
            Thread.sleep(300)
            assertEquals(1, api.calls.count { it == "auth.getSession" })
            assertEquals(1, api.calls.count { it == "user.getInfo" })
        }
    }

    @Test
    fun resuming_without_a_pending_token_makes_no_request() {
        withMainActivity { scenario ->
            openLastfm()
            awaitHeading(R.string.lastfm_card_disconnected_heading)
            cycle(scenario)
            cycle(scenario)
            Thread.sleep(300)
            assertEquals(emptyList<String>(), api.calls)
        }
    }

    @Test
    fun error_14_keeps_the_same_token_and_reopening_uses_it() {
        seedPending("tok-flow-3")
        api.replyAlways("auth.getSession", """{"error":14,"message":"Unauthorized Token"}""")

        withMainActivity {
            openLastfm()
            awaitHeading(R.string.lastfm_card_pending_heading)
            assertEquals("tok-flow-3", store.read().pendingToken)

            tap(R.id.lastfm_action) // Открыть Last.fm
            awaitTrue("the browser intent") { launched.size == 1 }

            assertTrue(launched.single().dataString!!.endsWith("&token=tok-flow-3"))
            assertFalse("no new token was requested", "auth.getToken" in api.calls)
        }
    }

    // ---- the ways out ------------------------------------------------------------

    @Test
    fun cancel_returns_to_disconnected_and_forgets_the_token() {
        seedPending("tok-flow-4")
        api.replyAlways("auth.getSession", """{"error":14,"message":"Unauthorized Token"}""")

        withMainActivity {
            openLastfm()
            awaitHeading(R.string.lastfm_card_pending_heading)

            tap(R.id.lastfm_action_secondary) // Отменить
            awaitHeading(R.string.lastfm_card_disconnected_heading)
            assertEquals(LastfmStoredSession.EMPTY, store.read())
        }
    }

    @Test
    fun disconnect_clears_everything_locally_and_tells_Last_fm_nothing() {
        store.write(LastfmStoredSession(sessionKey = "sk-flow", username = "f0ul482"))
        api.reply("user.getInfo", """{"user":{"name":"f0ul482","playcount":"5"}}""")

        withMainActivity {
            openLastfm()
            awaitHeading(R.string.lastfm_card_connected_heading)

            tap(R.id.lastfm_action) // Отключить
            awaitHeading(R.string.lastfm_card_disconnected_heading)
            assertEquals(LastfmStoredSession.EMPTY, store.read())
            assertEquals("disconnect is local only", listOf("user.getInfo"), api.calls)
        }
    }

    @Test
    fun the_re_auth_card_offers_disconnect_as_its_way_out() {
        // Not reachable from any P3b code path - only a direct write produces it - but
        // it is rendered and wired, ready for the error-9 path scrobbling will bring.
        store.write(LastfmStoredSession(username = "f0ul482"))

        withMainActivity {
            openLastfm()
            awaitHeading(R.string.lastfm_card_reauth_heading)
            on { a ->
                assertEquals(
                    a.getString(R.string.lastfm_action_disconnect),
                    a.findViewById<TextView>(R.id.lastfm_action_secondary).text.toString(),
                )
            }

            tap(R.id.lastfm_action_secondary) // Отключить
            awaitHeading(R.string.lastfm_card_disconnected_heading)
            assertEquals(LastfmStoredSession.EMPTY, store.read())
        }
    }

    @Test
    fun a_count_that_cannot_be_read_leaves_the_account_linked() {
        store.write(LastfmStoredSession(sessionKey = "sk-flow", username = "f0ul482"))
        // user.getInfo unscripted: the fake answers "unreachable".

        withMainActivity {
            openLastfm()
            awaitHeading(R.string.lastfm_card_connected_heading)
            awaitTrue("the lookup ran") { "user.getInfo" in api.calls }
            Thread.sleep(200)
            on { a -> assertEquals(View.INVISIBLE, a.findViewById<View>(R.id.lastfm_body_2).visibility) }
            assertEquals("still linked", "sk-flow", store.read().sessionKey)
        }
    }

    // ---- recreation --------------------------------------------------------------

    @Test
    fun recreation_and_a_fresh_launch_restore_the_pending_card_from_disk() {
        seedPending("tok-flow-5")
        api.replyAlways("auth.getSession", """{"error":14,"message":"Unauthorized Token"}""")

        withMainActivity { scenario ->
            openLastfm()
            awaitHeading(R.string.lastfm_card_pending_heading)
            scenario.recreate()
            awaitHeading(R.string.lastfm_card_pending_heading)
        }

        // A new activity and a new ViewModel: everything comes back from the store.
        api.replyAlways("auth.getSession", SESSION_OK)
        api.reply("user.getInfo", """{"user":{"name":"f0ul482","playcount":"1"}}""")
        withMainActivity {
            openLastfm()
            awaitHeading(R.string.lastfm_card_connected_heading)
            assertEquals("sk-flow", store.read().sessionKey)
        }
    }

    // ---- the Settings row --------------------------------------------------------

    @Test
    fun the_Settings_row_says_each_state() {
        withMainActivity { scenario ->
            openSettingsAndSettle()
            val cases = listOf(
                LastfmStoredSession.EMPTY to context.getString(R.string.settings_lastfm_not_connected),
                LastfmStoredSession(pendingToken = "t", pendingTokenIssuedAt = System.currentTimeMillis()) to
                    context.getString(R.string.settings_lastfm_pending),
                LastfmStoredSession(sessionKey = "sk", username = "f0ul482") to "f0ul482",
                LastfmStoredSession(username = "f0ul482") to context.getString(R.string.settings_lastfm_reauth),
            )
            for ((session, expected) in cases) {
                store.write(session)
                cycle(scenario)
                awaitText(R.id.settings_row_lastfm_value, expected)
            }
            // The row never fetches anything.
            assertEquals(emptyList<String>(), api.calls)
        }
    }

    // ---- the real launcher -------------------------------------------------------

    @Test
    fun the_real_launcher_fires_a_browser_intent_at_Last_fm_without_opening_a_browser() {
        LastfmAuthLauncher.launchOverrideForTest = null
        api.reply("auth.getToken", """{"token":"tok-flow-6"}""")
        // Blocking: the launch is recorded and nothing is started.
        val monitor = instrumentation.addMonitor(
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
                addDataAuthority("www.last.fm", null)
                addDataPath("/api/auth/", PatternMatcher.PATTERN_PREFIX)
            },
            Instrumentation.ActivityResult(Activity.RESULT_OK, null),
            true,
        )
        try {
            withMainActivity {
                openLastfm()
                tap(R.id.lastfm_action)
                awaitTrue("the monitor saw the launch") { monitor.hits >= 1 }
                assertEquals(1, monitor.hits)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    // ==================== harness ====================

    private fun seedPending(token: String) {
        store.write(LastfmStoredSession(pendingToken = token, pendingTokenIssuedAt = System.currentTimeMillis()))
    }

    private fun openLastfm() {
        openSettingsAndSettle()
        tap(R.id.settings_row_lastfm)
        awaitTrue("the Last.fm screen") {
            var here = false
            instrumentation.runOnMainSync {
                here = resumedMainActivity().currentDestinationIdOrNull() == R.id.settings_lastfm
            }
            here
        }
    }

    /** Pause and resume the activity - what returning from the browser looks like. */
    private fun cycle(scenario: ActivityScenario<MainActivity>) {
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.RESUMED)
    }

    private fun on(block: (MainActivity) -> Unit) {
        instrumentation.runOnMainSync { block(resumedMainActivity()) }
    }

    private fun tap(id: Int) {
        instrumentation.runOnMainSync { resumedMainActivity().findViewById<View>(id).performClick() }
    }

    private fun awaitHeading(res: Int) = awaitText(R.id.lastfm_card_heading, context.getString(res))

    private fun awaitText(id: Int, expected: String) {
        var last: String? = null
        awaitTrue("text of $id to be \"$expected\" (last: \"$last\")") {
            instrumentation.runOnMainSync {
                last = resumedMainActivity().findViewById<TextView>(id)?.text?.toString()
            }
            last == expected
        }
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /**
     * Answers from a script and records every method it was asked for. Anything
     * unscripted is unreachable - the answer that changes nothing.
     */
    private class ScriptedApi : LastfmApi {
        private val once = mutableMapOf<String, ArrayDeque<String>>()
        private val always = mutableMapOf<String, String>()
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Synchronized fun reply(method: String, body: String) {
            once.getOrPut(method) { ArrayDeque() }.addLast(body)
        }

        @Synchronized fun replyAlways(method: String, body: String) {
            once.remove(method)
            always[method] = body
        }

        override suspend fun send(request: LastfmRequest): LastfmTransportResult {
            calls += request.method
            val body = synchronized(this) {
                once[request.method]?.removeFirstOrNull() ?: always[request.method]
            }
            return if (body == null) LastfmTransportResult.Unreachable("Unscripted")
            else LastfmTransportResult.Body(body)
        }
    }

    private companion object {
        const val SESSION_OK = """{"session":{"name":"f0ul482","key":"sk-flow","subscriber":0}}"""
    }
}
