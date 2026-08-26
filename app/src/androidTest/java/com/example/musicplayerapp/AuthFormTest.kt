package com.example.musicplayerapp

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.data.supabase.AuthFailure
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two auth forms doing their job: refusing bad input, running exactly one
 * request, showing what failed, and getting out of the way when it worked.
 *
 * Real activity, real nav graph, real fragments, real `EmailAuthRepository`, real
 * `IdentityHandoff`, real Room - fake auth and fake PostgREST. The seam is at the
 * network and nowhere higher, so a successful sign-in from an anonymous install
 * really does drain an outbox, really does retire the source identity and really
 * does adopt the local rows, and this suite can assert all three.
 *
 * ## The gate
 *
 * Several of these are claims about a request that is *in flight* - the spinner, the
 * double-submit guard, surviving a recreation. A fake that returned immediately would
 * let every one of them pass without the state they describe ever existing, so the
 * fake is held open by a `CompletableDeferred` the test releases when it has finished
 * looking.
 */
@RunWith(AndroidJUnit4::class)
class AuthFormTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var auth: FakeEmailAuthApi
    private lateinit var sync: RecordingSyncApi

    private val x = "11111111-1111-4111-8111-111111111111"
    private val y = "22222222-2222-4222-8222-222222222222"

    private val depeche = TrackKey.of("Depeche Mode", "Enjoy the Silence")!!

    @Before
    fun open() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
            ).close()
        }

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        AppDatabase.overrideForInstrumentation(db)

        auth = FakeEmailAuthApi().also { it.uid = y }
        EmailAuthBackend.overrideForInstrumentation { auth }

        sync = RecordingSyncApi()
        ReactionSyncBackend.overrideForInstrumentation({ sync }, CountingIdentity(x).asProvider())

        IdentityStore.clearForTest(context)
    }

    @After
    fun close() {
        auth.release()
        IdentityStore.clearForTest(context)
        AppDatabase.overrideForInstrumentation(null)
        TestIsolation.restoreBackends()
        db.close()
    }

    // ==================== 5: local validation ====================

    @Test
    fun a_malformed_address_is_refused_without_a_request() {
        signIn { scenario ->
            scenario.type(R.id.auth_email, "denis at example.com")
            scenario.type(R.id.auth_password, "s3cret!!")
            scenario.tap(R.id.auth_submit)

            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.visibilityOf(R.id.auth_email_error))
                assertEquals(
                    activity.getString(R.string.auth_error_email_format),
                    activity.text(R.id.auth_email_error),
                )
                assertEquals(View.GONE, activity.visibilityOf(R.id.auth_password_error))
            }

            // The point of validating locally: a round trip for a value already known
            // to be wrong costs the listener time and tells them less than this does.
            assertEquals("nothing may be sent", 0, auth.authCalls)
            assertTrue(auth.signIns.isEmpty())
        }
    }

    @Test
    fun an_empty_password_is_refused_without_a_request() {
        signIn { scenario ->
            scenario.type(R.id.auth_email, "denis@example.com")
            scenario.tap(R.id.auth_submit)

            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.auth_error_password_blank),
                    activity.text(R.id.auth_password_error),
                )
            }
            assertEquals(0, auth.authCalls)
        }
    }

    /**
     * Sign-in does **not** apply the create-account minimum, and that is deliberate.
     *
     * Supabase's own default minimum is six, so an account made before this app
     * existed can have a shorter password than this app would let somebody choose.
     * Refusing it here would lock that listener out permanently, with a message that
     * is simply false about an account that works.
     */
    @Test
    fun sign_in_accepts_a_password_shorter_than_the_create_account_minimum() {
        signIn { scenario ->
            auth.gate = CompletableDeferred()
            scenario.type(R.id.auth_email, "denis@example.com")
            scenario.type(R.id.auth_password, "123456")
            scenario.tap(R.id.auth_submit)

            scenario.await("the request to start") { auth.authCalls == 1 }
            scenario.onActivity {
                assertEquals(View.GONE, it.visibilityOf(R.id.auth_password_error))
            }

            auth.release()
            scenario.await("the request to settle") { it.currentDestinationId() == R.id.profile }
        }
    }

    @Test
    fun create_account_refuses_a_blank_name_and_a_short_password() {
        createAccount { scenario ->
            scenario.type(R.id.auth_name, "   ")
            scenario.type(R.id.auth_email, "denis@example.com")
            scenario.type(R.id.auth_password, "short")
            scenario.tap(R.id.auth_submit)

            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.auth_error_name_blank),
                    activity.text(R.id.auth_name_error),
                )
                assertEquals(
                    activity.getString(R.string.auth_error_password_short),
                    activity.text(R.id.auth_password_error),
                )
                assertEquals(View.GONE, activity.visibilityOf(R.id.auth_email_error))
            }
            assertEquals(0, auth.authCalls)
        }
    }

    @Test
    fun the_name_and_address_are_trimmed_on_the_way_to_the_account() {
        createAccount { scenario ->
            scenario.type(R.id.auth_name, "  Денис  ")
            scenario.type(R.id.auth_email, "  denis@example.com  ")
            scenario.type(R.id.auth_password, "s3cret!!")
            scenario.tap(R.id.auth_submit)

            scenario.await("the registration to finish") { auth.signUps.size == 1 }
            assertEquals(
                FakeEmailAuthApi.SignUp("denis@example.com", "s3cret!!", "Денис"),
                auth.signUps.single(),
            )
        }
    }

    // ==================== 6 and 7: one request, and the screen says so ====================

    @Test
    fun a_double_tap_produces_exactly_one_request() {
        signIn { scenario ->
            auth.gate = CompletableDeferred()
            scenario.fill()

            scenario.tap(R.id.auth_submit)
            scenario.tap(R.id.auth_submit)
            scenario.tap(R.id.auth_submit)

            scenario.await("the request to start") { auth.authCalls >= 1 }
            assertEquals("three taps, one account", 1, auth.authCalls)

            // Let it finish inside the scenario. A coroutine still parked on the gate
            // when the activity tears down is a request in flight through a
            // destroyed window, which the API 24 image does not survive reliably -
            // and an @After release is already one teardown too late.
            auth.release()
            scenario.await("the request to settle") { it.currentDestinationId() == R.id.profile }
        }
    }

    @Test
    fun a_request_in_flight_closes_every_route_to_a_second_one() {
        signIn { scenario ->
            auth.gate = CompletableDeferred()
            scenario.fill()
            scenario.tap(R.id.auth_submit)
            scenario.await("the loading state") { it.visibilityOf(R.id.auth_submit_progress) == View.VISIBLE }

            scenario.onActivity { activity ->
                // The indicator replaces the label in the button's centre - INVISIBLE
                // rather than GONE, so the 52dp button does not change size.
                assertEquals(View.INVISIBLE, activity.visibilityOf(R.id.auth_submit_label))

                assertFalse(activity.findViewById<View>(R.id.auth_submit).isEnabled)
                assertFalse(activity.findViewById<View>(R.id.auth_email).isEnabled)
                assertFalse(activity.findViewById<View>(R.id.auth_password).isEnabled)
                assertFalse(activity.findViewById<View>(R.id.auth_create_account).isEnabled)
                assertFalse(activity.findViewById<View>(R.id.auth_continue_as_guest).isEnabled)

                // Back is deliberately still live. A screen with a spinner and no way
                // out is worse than a cancelled request, and the identity layer
                // survives a process death at any point in this call.
                assertTrue(activity.findViewById<View>(R.id.auth_back).isEnabled)
            }

            // And the disabled controls really do nothing.
            scenario.tap(R.id.auth_create_account)
            scenario.onActivity { assertEquals(R.id.auth_sign_in, it.currentDestinationId()) }

            auth.release()
            scenario.await("the request to settle") { it.currentDestinationId() == R.id.profile }
        }
    }

    @Test
    fun the_loading_state_clears_when_the_request_fails() {
        signIn { scenario ->
            auth.failure = AuthFailure.NetworkFailure("offline")
            scenario.fill()
            scenario.tap(R.id.auth_submit)

            scenario.await("the failure to land") { it.visibilityOf(R.id.auth_form_error) == View.VISIBLE }
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.visibilityOf(R.id.auth_submit_progress))
                assertEquals(View.VISIBLE, activity.visibilityOf(R.id.auth_submit_label))
                assertTrue(activity.findViewById<View>(R.id.auth_submit).isEnabled)
                assertTrue(activity.findViewById<View>(R.id.auth_email).isEnabled)
            }
        }
    }

    // ==================== 8: every failure reaches the screen ====================

    @Test
    fun every_typed_failure_lands_in_the_inline_area_with_its_own_message() {
        val table = listOf(
            AuthFailure.InvalidCredentials() to R.string.auth_error_invalid_credentials,
            AuthFailure.EmailAlreadyRegistered() to R.string.auth_error_email_taken,
            AuthFailure.InvalidEmail() to R.string.auth_error_email_format,
            AuthFailure.WeakOrInvalidPassword() to R.string.auth_error_password_short,
            AuthFailure.NetworkFailure() to R.string.auth_error_network,
            AuthFailure.RateLimited() to R.string.auth_error_rate_limited,
            AuthFailure.SessionNotEstablished(
                AuthFailure.SessionNotEstablished.Reason.NO_SESSION
            ) to R.string.auth_error_session,
            AuthFailure.Unknown() to R.string.auth_error_unknown,
        )

        for ((failure, message) in table) {
            auth.failure = failure
            auth.session = null

            signIn { scenario ->
                scenario.fill()
                scenario.tap(R.id.auth_submit)
                scenario.await("$failure") { it.visibilityOf(R.id.auth_form_error) == View.VISIBLE }

                scenario.onActivity { activity ->
                    assertEquals("$failure", activity.getString(message), activity.text(R.id.auth_form_error))

                    // One area, below the form. No dialog and no Toast, and the
                    // screen stays where it is so the listener can correct and retry.
                    assertEquals(R.id.auth_sign_in, activity.currentDestinationId())
                    assertEquals(
                        "a remote failure belongs to the form, not to a field",
                        View.GONE,
                        activity.visibilityOf(R.id.auth_email_error),
                    )
                }
            }

            assertEquals("$failure", IdentityState.None, IdentityStore.state(context))
        }
    }

    @Test
    fun nothing_the_server_said_reaches_the_screen() {
        auth.failure = AuthFailure.Unknown(
            status = 400,
            code = "validation_failed",
            detail = "new row violates row-level security policy for table reactions",
        )

        signIn { scenario ->
            scenario.fill()
            scenario.tap(R.id.auth_submit)
            scenario.await("the failure") { it.visibilityOf(R.id.auth_form_error) == View.VISIBLE }

            scenario.onActivity { activity ->
                val shown = activity.text(R.id.auth_form_error)
                assertEquals(activity.getString(R.string.auth_error_unknown), shown)
                assertFalse("server text leaked to the screen", shown.contains("row-level"))
                assertFalse(shown.contains("validation_failed"))
            }
        }
    }

    // ==================== 9: success with nothing to hand over ====================

    @Test
    fun a_successful_sign_in_from_none_commits_the_account_and_returns_to_the_profile() {
        signIn { scenario ->
            scenario.fill()
            scenario.tap(R.id.auth_submit)

            scenario.await("the navigation back") { it.currentDestinationId() == R.id.profile }
        }

        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        // Nothing was handed over, so nothing was retired.
        assertTrue(sync.retirements.isEmpty())
    }

    @Test
    fun a_successful_registration_from_none_commits_the_account() {
        createAccount { scenario ->
            scenario.type(R.id.auth_name, "Денис")
            scenario.type(R.id.auth_email, "denis@example.com")
            scenario.type(R.id.auth_password, "s3cret!!")
            scenario.tap(R.id.auth_submit)

            scenario.await("the navigation back") { it.currentDestinationId() == R.id.profile }
        }

        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertEquals("Денис", auth.signUps.single().displayName)
    }

    // ==================== 10: success with an identity to hand over ====================

    /**
     * The whole stack, from a tap to a retired identity.
     *
     * The fragment does none of this and knows none of it: it calls the repository
     * once. Everything asserted below - the drain, the retirement, the adoption, the
     * committed state - is G-A4b1 and G-A4b2 running for real underneath a button.
     */
    @Test
    fun a_sign_in_from_anonymous_runs_the_real_handoff() {
        IdentityStore.adoptAnonymous(context, x)
        runBlocking {
            db.reactionDao().like(depeche, "Depeche Mode", "Enjoy the Silence", "myata", 1_000L, 1_000L)
        }

        signIn { scenario ->
            scenario.fill()
            scenario.tap(R.id.auth_submit)
            scenario.await("the handoff to finish", 20_000) { it.currentDestinationId() == R.id.profile }
        }

        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertEquals("the source identity must be retired", listOf(x), sync.retirements)
        assertEquals(setOf(depeche), sync.adoptedBy.getValue(y).keys)
        assertTrue("adoption writes no events", sync.eventsBy(y).isEmpty())
        assertTrue("nothing is owed afterwards", IdentityStore.handoff(context) == null)
        assertEquals("local Room is never the cloud's copy", 1, runBlocking { db.reactionDao().allReactions().size })
    }

    @Test
    fun a_failed_sign_in_from_anonymous_leaves_the_listener_where_they_were() {
        IdentityStore.adoptAnonymous(context, x)
        runBlocking {
            db.reactionDao().like(depeche, "Depeche Mode", "Enjoy the Silence", "myata", 1_000L, 1_000L)
        }
        auth.failure = AuthFailure.InvalidCredentials()

        signIn { scenario ->
            scenario.fill()
            scenario.tap(R.id.auth_submit)
            scenario.await("the rollback", 20_000) { it.visibilityOf(R.id.auth_form_error) == View.VISIBLE }

            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.auth_error_invalid_credentials),
                    activity.text(R.id.auth_form_error),
                )
                assertEquals("the screen stays put so they can retry", R.id.auth_sign_in,
                    activity.currentDestinationId())
            }
        }

        assertEquals(IdentityState.Anonymous(x), IdentityStore.state(context))
        assertEquals(setOf(depeche), sync.adoptedBy.getValue(x).keys)
    }

    // ============ loading terminates on every path there is ============

    /**
     * The regression test for the stall.
     *
     * `AuthViewModel.submit` used to have no exception boundary: a throw from any
     * layer below skipped the line that replaces the loading state, so the button
     * span forever - and the same uncaught exception took the process down on its way
     * past `viewModelScope`. Both symptoms, one missing `try`.
     *
     * The fake throws the plainest thing it can, because the point is not which
     * exception it is. The point is that *nothing* below this ViewModel is allowed to
     * decide whether the screen stops spinning.
     */
    @Test
    fun a_backend_that_throws_reports_a_failure_instead_of_spinning_forever() {
        signIn { scenario ->
            auth.throwOnCall = IllegalStateException("attempt to re-open an already-closed object")
            scenario.fill()
            scenario.tap(R.id.auth_submit)

            scenario.await("the throw to be reported") {
                it.visibilityOf(R.id.auth_form_error) == View.VISIBLE
            }

            scenario.onActivity { activity ->
                assertEquals(
                    "a throw is not something a listener can be told about precisely",
                    activity.getString(R.string.auth_error_unknown),
                    activity.text(R.id.auth_form_error),
                )
                // The three things that were broken.
                assertEquals(View.GONE, activity.visibilityOf(R.id.auth_submit_progress))
                assertEquals(View.VISIBLE, activity.visibilityOf(R.id.auth_submit_label))
                assertTrue("the button must be usable again", activity.findViewById<View>(R.id.auth_submit).isEnabled)
                assertTrue(activity.findViewById<View>(R.id.auth_email).isEnabled)
                // And the process is still here, which is the other half of the bug.
                assertEquals(R.id.auth_sign_in, activity.currentDestinationId())
            }
        }
    }

    @Test
    fun a_throw_leaves_the_screen_able_to_try_again() {
        signIn { scenario ->
            auth.throwOnCall = RuntimeException("boom")
            scenario.fill()
            scenario.tap(R.id.auth_submit)
            scenario.await("the first failure") { it.visibilityOf(R.id.auth_form_error) == View.VISIBLE }

            // A screen that survived the throw but could not be retried would be only
            // half fixed.
            auth.throwOnCall = null
            scenario.tap(R.id.auth_submit)
            scenario.await("the retry to succeed") { it.currentDestinationId() == R.id.profile }
        }

        assertEquals(2, auth.authCalls)
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
    }

    /**
     * Every terminal outcome the screen can reach, and the one thing true of all of
     * them: the button is not spinning afterwards.
     *
     * Written as a table rather than five tests because the claim is about the *set*
     * being exhaustive - a sixth outcome added later should be added here, and the
     * table is where somebody will look.
     */
    @Test
    fun loading_terminates_on_every_terminal_path() {
        val paths: List<Pair<String, () -> Unit>> = listOf(
            "success" to { auth.failure = null; auth.throwOnCall = null },
            "typed failure" to { auth.failure = AuthFailure.RateLimited(); auth.throwOnCall = null },
            "session not established" to {
                auth.failure = null; auth.throwOnCall = null; auth.establishesSession = false
            },
            "throw" to { auth.throwOnCall = IllegalStateException("boom") },
        )

        for ((name, arrange) in paths) {
            IdentityStore.clearForTest(context)
            auth.failure = null
            auth.throwOnCall = null
            auth.establishesSession = true
            arrange()

            signIn { scenario ->
                scenario.fill()
                scenario.tap(R.id.auth_submit)

                scenario.await("$name to settle") { activity ->
                    activity.currentDestinationId() == R.id.profile ||
                        activity.visibilityOf(R.id.auth_form_error) == View.VISIBLE
                }

                scenario.onActivity { activity ->
                    if (activity.currentDestinationId() == R.id.auth_sign_in) {
                        assertEquals(
                            "$name left the button spinning",
                            View.GONE,
                            activity.visibilityOf(R.id.auth_submit_progress),
                        )
                        assertTrue("$name left the button disabled",
                            activity.findViewById<View>(R.id.auth_submit).isEnabled)
                    }
                }
            }
        }
    }

    /**
     * Backing out mid-request cancels it, and that must not take anything with it.
     *
     * `Назад` stays live while a request is in flight on purpose - a screen with a
     * spinner and no way out is worse than a cancelled request - so this is a path a
     * listener can reach with one tap, and it goes through the same `finally`.
     */
    @Test
    fun leaving_mid_request_cancels_it_without_crashing_or_stranding_the_screen() {
        signIn { scenario ->
            auth.gate = CompletableDeferred()
            scenario.fill()
            scenario.tap(R.id.auth_submit)
            scenario.await("the loading state") { it.visibilityOf(R.id.auth_submit_progress) == View.VISIBLE }

            scenario.tap(R.id.auth_back)
            scenario.onActivity { assertEquals(R.id.profile, it.currentDestinationId()) }

            auth.release()

            // Coming back gets a clean form, not the ghost of the cancelled attempt.
            scenario.tap(R.id.profile_sign_in)
            scenario.onActivity { activity ->
                assertEquals(R.id.auth_sign_in, activity.currentDestinationId())
                assertEquals(View.GONE, activity.visibilityOf(R.id.auth_submit_progress))
                assertEquals(View.GONE, activity.visibilityOf(R.id.auth_form_error))
                assertTrue(activity.findViewById<View>(R.id.auth_submit).isEnabled)
            }
        }
    }

    // ==================== 11: a recreation mid-request ====================

    /**
     * An activity recreation while a request is running.
     *
     * `MainActivity` declares `configChanges` for orientation, so a rotation does not
     * recreate anything and would prove nothing. `recreate()` forces the real thing -
     * views destroyed, fragments rebuilt from saved state - which is the stronger
     * version of the same question and also covers a process the system rebuilds for
     * its own reasons.
     */
    @Test
    fun a_recreation_mid_request_neither_repeats_it_nor_loses_it() {
        signIn { scenario ->
            auth.gate = CompletableDeferred()
            scenario.fill()
            scenario.tap(R.id.auth_submit)
            scenario.await("the loading state") { it.visibilityOf(R.id.auth_submit_progress) == View.VISIBLE }

            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertEquals("the request must not be started again", 1, auth.authCalls)
            scenario.onActivity { activity ->
                assertEquals("the rebuilt screen must still show it is working",
                    View.VISIBLE, activity.visibilityOf(R.id.auth_submit_progress))
                assertEquals(R.id.auth_sign_in, activity.currentDestinationId())
            }

            // And the result still arrives, on the screen that replaced the one that
            // asked for it.
            auth.release()
            scenario.await("the navigation back") { it.currentDestinationId() == R.id.profile }
        }

        assertEquals(1, auth.authCalls)
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
    }

    @Test
    fun a_recreation_keeps_an_error_on_screen() {
        signIn { scenario ->
            auth.failure = AuthFailure.RateLimited()
            scenario.fill()
            scenario.tap(R.id.auth_submit)
            scenario.await("the failure") { it.visibilityOf(R.id.auth_form_error) == View.VISIBLE }

            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                assertEquals(
                    "an error that vanished on recreation would look like it worked",
                    activity.getString(R.string.auth_error_rate_limited),
                    activity.text(R.id.auth_form_error),
                )
            }
            assertEquals("and nothing was retried", 1, auth.authCalls)
        }
    }

    // ==================== helpers ====================

    private fun signIn(body: (ActivityScenario<MainActivity>) -> Unit) =
        openAuth(R.id.profile_sign_in, R.id.auth_sign_in, body)

    private fun createAccount(body: (ActivityScenario<MainActivity>) -> Unit) =
        openAuth(R.id.profile_create_account, R.id.auth_create_account, body)

    private fun openAuth(
        cta: Int,
        destination: Int,
        body: (ActivityScenario<MainActivity>) -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.tap(R.id.profile_entry)
            scenario.tap(cta)
            scenario.onActivity { assertEquals(destination, it.currentDestinationId()) }
            body(scenario)
        }
    }

    /** The smallest valid sign-in, so a test can be about something else. */
    private fun ActivityScenario<MainActivity>.fill() {
        type(R.id.auth_email, "denis@example.com")
        type(R.id.auth_password, "s3cret!!")
    }

    private fun ActivityScenario<MainActivity>.type(id: Int, value: String) {
        onActivity { it.findViewById<EditText>(id).setText(value) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun ActivityScenario<MainActivity>.tap(id: Int) {
        onActivity { it.findViewById<View>(id).performClick() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /**
     * Waits for something the main thread has not done yet.
     *
     * The auth call runs on `Dispatchers.IO` and posts its result back, so
     * `waitForIdleSync` alone is not enough: it drains the queue as it is now, not
     * the message that has not been posted yet.
     */
    private fun ActivityScenario<MainActivity>.await(
        what: String,
        timeoutMs: Long = 10_000,
        check: (MainActivity) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var satisfied = false
            onActivity { satisfied = check(it) }
            if (satisfied) return
            Thread.sleep(25)
        }
        fail("timed out after ${timeoutMs}ms waiting for $what")
    }

    private fun MainActivity.text(id: Int): String = findViewById<TextView>(id).text.toString()

    private fun MainActivity.visibilityOf(id: Int): Int = findViewById<View>(id).visibility

    private fun MainActivity.currentDestinationId(): Int? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment)
            as androidx.navigation.fragment.NavHostFragment
        return host.navController.currentDestination?.id
    }
}
