package com.example.musicplayerapp

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.supabase.AuthFailure
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * auth-recovery as a screen: three stages, one address, one code, and one way out.
 *
 * Real activity, real nav graph, real fragment, real `EmailAuthRepository`, real
 * `IdentityStore` - fake auth and fake PostgREST, exactly as `AuthFormTest` arranges
 * it. The seam is at the network and nowhere higher, so the handoff a recovery from an
 * anonymous install performs really happens here.
 *
 * **No test in this file sends mail.** Every call stops at [FakeEmailAuthApi]; nothing
 * constructs the real API, and the project's custom SMTP allowance is never touched.
 * Live delivery was a separate, owner-run gate, passed once in G-A4c2.
 *
 * ## What each test is defending
 *
 * Four of these fail against an implementation that looks reasonable:
 *
 *  - `the_address_survives_a_recreation` fails if the address is read off the
 *    `EditText` at submit time, because the request group is gone by then and a
 *    recreation has rebuilt the field empty;
 *  - `back_is_refused_while_the_password_is_being_set` fails if Back is left live the
 *    way auth-sign-in leaves it, because the identity is already committed by then;
 *  - `an_accepted_code_is_never_sent_twice` fails if a retry re-runs the whole submit,
 *    which would spend a consumed OTP and report the listener's correct code as wrong;
 *  - `the_request_result_never_varies_with_the_address` fails the moment anything on
 *    the request stage learns to say "no such account".
 */
@RunWith(AndroidJUnit4::class)
class AuthRecoveryUiTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var auth: FakeEmailAuthApi
    private lateinit var sync: RecordingSyncApi

    private val x = "11111111-1111-4111-8111-111111111111"
    private val y = "22222222-2222-4222-8222-222222222222"

    private val address = "denis@example.com"
    private val other = "someone.else@example.com"

    /** The same backstop `AuthFormTest` carries, for the same reason. */
    @get:Rule
    val timeout: Timeout = Timeout.builder()
        .withTimeout(90, TimeUnit.SECONDS)
        .withLookingForStuckThread(true)
        .build()

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

    // ==================== the request stage ====================

    @Test
    fun a_malformed_address_is_refused_without_a_request() {
        recovery { scenario ->
            scenario.type(R.id.auth_email, "denis at example.com")
            scenario.tap(R.id.auth_submit)

            on { activity ->
                assertEquals(
                    activity.getString(R.string.auth_error_email_format),
                    activity.text(R.id.auth_email_error),
                )
                assertEquals(View.VISIBLE, activity.visibilityOf(R.id.auth_recovery_request_group))
            }
            assertTrue("nothing may be sent", auth.resetRequests.isEmpty())
        }
    }

    /**
     * The enumeration rule, stated as an equality rather than as an absence.
     *
     * The fake answers `Requested` for every address, exactly as the server does - it
     * has no notion of which addresses exist. So the claim worth testing is not "the
     * screen does not say the account is missing", which would pass trivially, but that
     * **two different addresses produce byte-identical screens**. Anything a future edit
     * adds that varies with the address fails here.
     */
    @Test
    fun the_request_result_never_varies_with_the_address() {
        recovery { scenario ->
            val first = scenario.requestAndCapture(address)

            // Back to the request stage - allowed, because no code has been accepted.
            scenario.tap(R.id.auth_back)
            on { assertEquals(View.VISIBLE, it.visibilityOf(R.id.auth_recovery_request_group)) }

            val second = scenario.requestAndCapture(other)

            assertEquals("the two runs must be indistinguishable", first, second)
            assertEquals(
                context.getString(R.string.auth_recovery_sent_notice),
                first.notice,
            )
            assertTrue(
                "the notice must not name the address",
                !first.notice.contains(address) && !first.notice.contains(other),
            )
        }
    }

    @Test
    fun a_transport_failure_keeps_the_listener_on_the_request_stage() {
        auth.failure = AuthFailure.NetworkFailure("no network")

        recovery { scenario ->
            scenario.type(R.id.auth_email, address)
            scenario.tap(R.id.auth_submit)
            scenario.await("the failure to render") {
                it.visibilityOf(R.id.auth_form_error) == View.VISIBLE
            }

            on { activity ->
                assertEquals(
                    activity.getString(R.string.auth_error_network),
                    activity.text(R.id.auth_form_error),
                )
                assertEquals(View.VISIBLE, activity.visibilityOf(R.id.auth_recovery_request_group))
                assertEquals(View.GONE, activity.visibilityOf(R.id.auth_recovery_code_group))
            }
        }
    }

    /**
     * Back out of the request stage, and this install is exactly what it was.
     *
     * `requestPasswordReset` writes nothing durable - no attempt marker, no handoff, no
     * identity - so leaving is a plain `popBackStack` and there is nothing to repair.
     */
    @Test
    fun leaving_the_request_stage_changes_no_identity() {
        IdentityStore.adoptAnonymous(context, x)

        recovery { scenario ->
            scenario.type(R.id.auth_email, address)
            scenario.tap(R.id.auth_submit)
            scenario.await("the code stage") {
                it.visibilityOf(R.id.auth_recovery_code_group) == View.VISIBLE
            }

            scenario.tap(R.id.auth_back)   // back to REQUEST
            scenario.tap(R.id.auth_back)   // out of recovery

            scenario.await("the sign-in screen") { it.currentDestinationId() == R.id.auth_sign_in }
            assertEquals(IdentityState.Anonymous(x), IdentityStore.state(context))
            assertTrue("no code may have been sent", auth.verifications.isEmpty())
        }
    }

    // ==================== the address the ViewModel owns ====================

    /**
     * The address belongs to the ViewModel, and the hidden field cannot override it.
     *
     * The request group is `GONE` at the CODE stage, but its `EditText` is still in the
     * hierarchy and Android restores its text across a recreation - so "the field is
     * empty by then" is exactly the assumption that must not be relied on. This test
     * makes the two implementations disagree instead: the hidden field is set to a
     * *different* address, and the code must still be verified against the one a mail
     * was actually asked for. An implementation that read the field at submit time
     * sends `stale@example.com` here and fails.
     */
    @Test
    fun the_address_is_the_one_a_mail_was_asked_for_not_the_one_in_the_field() {
        recovery { scenario ->
            scenario.type(R.id.auth_email, address)
            scenario.tap(R.id.auth_submit)
            scenario.await("the code stage") {
                it.visibilityOf(R.id.auth_recovery_code_group) == View.VISIBLE
            }
            assertEquals(listOf(address), auth.resetRequests)

            recreateActivity()

            on {
                assertEquals(
                    "the stage must survive the recreation",
                    View.VISIBLE,
                    it.visibilityOf(R.id.auth_recovery_code_group),
                )
            }

            // Whatever the hidden field says now, it is not the address under recovery.
            scenario.type(R.id.auth_email, "stale@example.com")
            on {
                assertEquals(
                    "the field really does hold the wrong address at submit time",
                    "stale@example.com",
                    it.findViewById<EditText>(R.id.auth_email).text.toString(),
                )
            }

            scenario.type(R.id.auth_recovery_code, "123456")
            scenario.type(R.id.auth_recovery_password, "n3wpassword")
            scenario.tap(R.id.auth_submit)

            scenario.await("the verification") { auth.verifications.isNotEmpty() }
            assertEquals(
                "the code must be verified against the address that was asked",
                address,
                auth.verifications.last().email,
            )
            assertEquals("no second mail may have been asked for", 1, auth.resetRequests.size)
        }
    }

    // ==================== the code stage ====================

    @Test
    fun a_blank_code_and_a_short_password_are_refused_without_a_request() {
        recovery { scenario ->
            scenario.reachCodeStage()

            scenario.type(R.id.auth_recovery_code, "   ")
            scenario.type(R.id.auth_recovery_password, "short")
            scenario.tap(R.id.auth_submit)

            on { activity ->
                assertEquals(
                    activity.getString(R.string.auth_error_code_blank),
                    activity.text(R.id.auth_recovery_code_error),
                )
                assertEquals(
                    activity.getString(R.string.auth_error_password_short),
                    activity.text(R.id.auth_recovery_password_error),
                )
            }
            assertTrue(auth.verifications.isEmpty())
            assertTrue(auth.passwordUpdates.isEmpty())
        }
    }

    /** A wrong code and an expired one are different problems and must read differently. */
    @Test
    fun a_wrong_code_and_an_expired_one_keep_their_own_words() {
        recovery { scenario ->
            scenario.reachCodeStage()

            auth.failure = AuthFailure.InvalidRecoveryCode("nope")
            scenario.submitCode()
            scenario.await("the wrong-code message") {
                it.visibilityOf(R.id.auth_recovery_code_error) == View.VISIBLE
            }
            var wrong = ""
            on { wrong = it.text(R.id.auth_recovery_code_error) }
            assertEquals(context.getString(R.string.auth_error_recovery_code_invalid), wrong)

            auth.failure = AuthFailure.RecoveryCodeExpired("too late")
            scenario.tap(R.id.auth_submit)
            scenario.await("the expired-code message") {
                it.text(R.id.auth_recovery_code_error) ==
                    context.getString(R.string.auth_error_recovery_code_expired)
            }

            var expired = ""
            on { expired = it.text(R.id.auth_recovery_code_error) }
            assertNotEquals("the two must not share a sentence", wrong, expired)
        }
    }

    /**
     * The code is spent once, whatever happens to the password afterwards.
     *
     * Verifying a recovery OTP consumes it. A retry that re-ran the whole submit would
     * send a consumed code, be refused, and tell the listener their correct code is
     * wrong - while this install already holds a session for the account.
     */
    @Test
    fun an_accepted_code_is_never_sent_twice() {
        recovery { scenario ->
            scenario.reachCodeStage()

            auth.updateFailure = AuthFailure.NetworkFailure("dropped")
            scenario.submitCode()
            scenario.await("the password failure") {
                it.visibilityOf(R.id.auth_recovery_password_error) == View.VISIBLE
            }

            assertEquals(1, auth.verifications.size)
            assertEquals(1, auth.passwordUpdates.size)
            on {
                assertEquals(
                    "the spent code must be locked",
                    false,
                    it.findViewById<View>(R.id.auth_recovery_code).isEnabled,
                )
                assertEquals(
                    "nothing may claim the password changed",
                    View.GONE,
                    it.visibilityOf(R.id.auth_recovery_done_group),
                )
            }

            auth.updateFailure = null
            scenario.tap(R.id.auth_submit)
            scenario.await("the retry to succeed") {
                it.visibilityOf(R.id.auth_recovery_done_group) == View.VISIBLE
            }

            assertEquals("the code must not be sent again", 1, auth.verifications.size)
            assertEquals(2, auth.passwordUpdates.size)
        }
    }

    /**
     * The window this screen exists to protect.
     *
     * Between `verifyRecoveryCode` returning and `updatePassword` finishing, the account
     * has been handed to this install and the password is still the one the listener
     * could not remember. A Back there cancels the coroutine and strands them. So Back
     * is refused - the band control, and the dispatcher every system Back and predictive
     * gesture arrives through.
     */
    @Test
    fun back_is_refused_while_the_password_is_being_set() {
        recovery { scenario ->
            scenario.reachCodeStage()

            auth.updateGate = CompletableDeferred()
            scenario.submitCode()

            // Parked *after* the identity has been committed, which is the whole point.
            scenario.await("the password call to be in flight") { auth.passwordUpdates.size == 1 }
            assertEquals(1, auth.verifications.size)

            on { it.onBackPressedDispatcher.onBackPressed() }
            sync()
            on {
                assertEquals(
                    "the system Back must not leave",
                    R.id.auth_recovery,
                    it.currentDestinationId(),
                )
            }

            scenario.tap(R.id.auth_back)
            on {
                assertEquals(
                    "the band control must not leave either",
                    R.id.auth_recovery,
                    it.currentDestinationId(),
                )
                assertEquals(View.VISIBLE, it.visibilityOf(R.id.auth_submit_progress))
            }

            auth.release()
            scenario.await("the password to be set") {
                it.visibilityOf(R.id.auth_recovery_done_group) == View.VISIBLE
            }
            assertEquals("the sequence must have finished", 1, auth.passwordUpdates.size)
        }
    }

    /**
     * Leaving after the code was accepted but the password was not.
     *
     * The worst state this screen can be in: the account **is** already this install's -
     * `verifyRecoveryCode` committed `Registered(Y)` - and the password is still the old
     * one, because `updatePassword` failed. Whatever the listener presses next, they
     * must not be shown a sign-in form for an account they are already signed in to.
     *
     * That is not a hypothetical arrangement of screens. Recovery is pushed **on top of**
     * auth-sign-in deliberately, so that abandoning it at the request stage returns the
     * half-filled form - which means a plain `popBackStack` from here lands on exactly
     * the wrong screen, and the guest profile's forwarding never gets a chance to run.
     *
     * And the code must not be re-sent on the way out: it is spent.
     */
    @Test
    fun back_after_a_failed_password_update_lands_on_the_account_not_the_sign_in_form() {
        IdentityStore.adoptAnonymous(context, x)

        recovery { scenario ->
            scenario.reachCodeStage()

            auth.updateFailure = AuthFailure.NetworkFailure("dropped")
            scenario.submitCode()
            scenario.await("the password failure") {
                it.visibilityOf(R.id.auth_recovery_password_error) == View.VISIBLE
            }

            // The two halves of the state this test is about.
            assertEquals(
                "the code bought a session, so the identity is already Y",
                IdentityState.Registered(y),
                IdentityStore.state(context),
            )
            assertEquals(1, auth.verifications.size)

            on { it.onBackPressedDispatcher.onBackPressed() }
            sync()

            scenario.await("the authenticated profile") {
                it.currentDestinationId() == R.id.profile_authenticated
            }
            on {
                assertNotEquals(
                    "a signed-in listener must never be shown the sign-in form",
                    R.id.auth_sign_in,
                    it.currentDestinationId(),
                )
                assertNotEquals(R.id.auth_recovery, it.currentDestinationId())
            }

            assertEquals("the spent code must not be sent again", 1, auth.verifications.size)
            assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        }
    }

    @Test
    fun a_double_tap_runs_one_request() {
        recovery { scenario ->
            scenario.reachCodeStage()

            auth.gate = CompletableDeferred()
            scenario.type(R.id.auth_recovery_code, "123456")
            scenario.type(R.id.auth_recovery_password, "n3wpassword")
            scenario.tap(R.id.auth_submit)
            scenario.tap(R.id.auth_submit)
            scenario.await("the first call") { auth.verifications.isNotEmpty() }

            assertEquals("one tap, one code", 1, auth.verifications.size)

            auth.release()
            scenario.await("it to settle") {
                it.visibilityOf(R.id.auth_recovery_done_group) == View.VISIBLE
            }
        }
    }

    // ==================== done ====================

    /**
     * Recovering from an anonymous install ends registered, through the existing handoff.
     *
     * This screen does none of that: `EmailAuthRepository.verifyRecoveryCode` routes it
     * exactly as a sign-in, and the assertion here is that the screen did not get in the
     * way of it.
     */
    @Test
    fun a_completed_recovery_lands_on_the_account_and_is_registered() {
        IdentityStore.adoptAnonymous(context, x)

        recovery { scenario ->
            scenario.reachCodeStage()
            scenario.submitCode()

            scenario.await("the done stage") {
                it.visibilityOf(R.id.auth_recovery_done_group) == View.VISIBLE
            }
            on { activity ->
                assertEquals(
                    activity.getString(R.string.auth_recovery_done_action),
                    activity.text(R.id.auth_submit_label),
                )
            }

            // The stage is the completion signal; pressing the button is the navigation.
            scenario.tap(R.id.auth_submit)
            scenario.await("profile-authenticated") {
                it.currentDestinationId() == R.id.profile_authenticated
            }

            assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        }
    }

    // ==================== helpers ====================

    /** What the request stage renders, reduced to the part that must never vary. */
    private data class RequestOutcome(val notice: String, val codeGroupVisible: Int)

    private fun ActivityScenario<MainActivity>.requestAndCapture(email: String): RequestOutcome {
        type(R.id.auth_email, email)
        tap(R.id.auth_submit)
        await("the code stage for $email") {
            it.visibilityOf(R.id.auth_recovery_code_group) == View.VISIBLE
        }

        var outcome = RequestOutcome("", View.GONE)
        on {
            outcome = RequestOutcome(
                notice = it.text(R.id.auth_recovery_sent_notice),
                codeGroupVisible = it.visibilityOf(R.id.auth_recovery_code_group),
            )
        }
        return outcome
    }

    private fun ActivityScenario<MainActivity>.reachCodeStage() {
        type(R.id.auth_email, address)
        tap(R.id.auth_submit)
        await("the code stage") {
            it.visibilityOf(R.id.auth_recovery_code_group) == View.VISIBLE
        }
    }

    private fun ActivityScenario<MainActivity>.submitCode() {
        type(R.id.auth_recovery_code, "123456")
        type(R.id.auth_recovery_password, "n3wpassword")
        tap(R.id.auth_submit)
    }

    private fun recovery(body: (ActivityScenario<MainActivity>) -> Unit) {
        withMainActivity { scenario ->
            try {
                openProfileAndSettle()
                scenario.tap(R.id.profile_sign_in)
                on { assertEquals(R.id.auth_sign_in, it.currentDestinationId()) }

                scenario.tap(R.id.auth_forgot_password)
                on { assertEquals(R.id.auth_recovery, it.currentDestinationId()) }

                body(scenario)
            } finally {
                auth.release()
            }
        }
    }

    private fun withMainActivity(body: (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            body(scenario)
        }
    }

    private fun openProfileAndSettle() {
        // Two taps since G1: the header control opens settings, and `Row / Профиль`
        // inside it routes to a profile.
        on { it.findViewById<View>(R.id.settings_entry).performClick() }
        sync()
        awaitDestination(R.id.settings)

        on { it.findViewById<View>(R.id.settings_row_profile).performClick() }
        sync()
        awaitDestination(R.id.profile)
    }

    private fun awaitDestination(id: Int, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var there = false
            on { there = it.currentDestinationId() == id }
            if (there) return
            Thread.sleep(25)
        }
        fail("never reached destination $id")
    }

    /** See `AuthFormTest.on`: `onActivity` deadlocks on API 24 against a live spinner. */
    private fun on(block: (MainActivity) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val current = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull() ?: error("no resumed MainActivity")
            block(current)
        }
    }

    /** See `AuthFormTest.recreateActivity`: `ActivityScenario.recreate` waits for idle. */
    private fun recreateActivity() {
        var previous: MainActivity? = null
        on {
            previous = it
            it.recreate()
        }

        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            var replaced = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val current = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .firstOrNull()
                replaced = current != null && current !== previous
            }
            if (replaced) return
            Thread.sleep(25)
        }
        fail("the activity never came back after recreate()")
    }

    private fun sync() = InstrumentationRegistry.getInstrumentation().runOnMainSync { }

    private fun ActivityScenario<MainActivity>.type(id: Int, value: String) {
        on { it.findViewById<EditText>(id).setText(value) }
        sync()
    }

    private fun ActivityScenario<MainActivity>.tap(id: Int) {
        on { it.findViewById<View>(id).performClick() }
        sync()
    }

    private fun ActivityScenario<MainActivity>.await(
        what: String,
        timeoutMs: Long = 10_000,
        check: (MainActivity) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var satisfied = false
            on { satisfied = check(it) }
            if (satisfied) return
            Thread.sleep(25)
        }

        var where = "?"
        runCatching {
            on {
                where = it.currentDestinationId()?.let { id ->
                    runCatching { it.resources.getResourceEntryName(id) }.getOrNull()
                } ?: "unknown"
            }
        }
        fail(
            "timed out after ${timeoutMs}ms waiting for $what (destination=$where, " +
                "resets=${auth.resetRequests.size}, verifications=${auth.verifications.size}, " +
                "updates=${auth.passwordUpdates.size}, state=${IdentityStore.state(context)})"
        )
    }

    private fun MainActivity.text(id: Int): String = findViewById<TextView>(id).text.toString()

    private fun MainActivity.visibilityOf(id: Int): Int =
        findViewById<View>(id)?.visibility ?: View.GONE

    private fun MainActivity.currentDestinationId(): Int? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment)
            as androidx.navigation.fragment.NavHostFragment
        return host.navController.currentDestination?.id
    }
}
