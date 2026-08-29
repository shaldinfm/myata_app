package com.example.musicplayerapp

import android.content.Context
import android.content.DialogInterface
import android.view.View
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.fragments.ProfileAuthenticatedFragment
import com.example.musicplayerapp.data.supabase.AccountDeletionResult
import com.example.musicplayerapp.data.supabase.DeleteAccountOutcome
import com.example.musicplayerapp.data.supabase.DeletionStage
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import com.example.musicplayerapp.ui.profile.DeleteAccountViewModel
import com.example.musicplayerapp.ui.profile.leavesTheAccountScreen
import com.example.musicplayerapp.ui.profile.message
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The delete-account row, its two confirmations, and the five things that can come back.
 *
 * ## What this suite is really guarding
 *
 * The orchestrator is already proven; what is new here is a **button that can destroy
 * an account**, so the assertions that matter are the ones about *not* calling it:
 * that the first dialog alone calls nothing, that cancelling either step calls
 * nothing, and that a listener who taps the destructive button repeatedly - or rotates
 * the device while it is running - still issues exactly one request. A second request
 * would mint a second token for one deletion and orphan the first, whose receipt is
 * the only thing that can resolve it if a response is lost.
 *
 * The other half is honesty of copy. `CleanupDeferred` and `Unresolved` are **not**
 * successes and **not** failures, and the mapping that decides which outcome may say
 * what is asserted directly rather than through a screenshot.
 *
 * Every answer comes from the fake behind `EmailAuthBackend`. Nothing here reaches a
 * live project, and `MyataTestRunner`'s refusing backend is asserted to still refuse.
 */
@RunWith(AndroidJUnit4::class)
class DeleteAccountUiTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var auth: FakeEmailAuthApi
    private lateinit var sync: RecordingSyncApi

    private val account = "22222222-2222-4222-8222-222222222222"
    private val anonymous = "11111111-1111-4111-8111-111111111111"

    @get:Rule
    val timeout: Timeout = Timeout.builder()
        .withTimeout(120, TimeUnit.SECONDS)
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

        auth = FakeEmailAuthApi().also { it.uid = account }
        EmailAuthBackend.overrideForInstrumentation { auth }

        sync = RecordingSyncApi().also { it.pullPages = emptyList() }
        ReactionSyncBackend.overrideForInstrumentation({ sync }, CountingIdentity(anonymous).asProvider())

        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
    }

    @After
    fun close() {
        auth.signOutGate?.complete(Unit)
        auth.gate?.complete(Unit)
        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
        AppDatabase.overrideForInstrumentation(null)
        TestIsolation.restoreBackends()
        if (::db.isInitialized) db.close()
    }

    // ==================== the row exists only where it may ====================

    /** A guest never sees a delete row: it is not in that layout at all. */
    @Test
    fun a_the_guest_screen_has_no_delete_row() {
        IdentityStore.adoptAnonymous(context, anonymous)

        withMainActivity {
            openProfileAndSettle()
            on { activity ->
                assertEquals(R.id.profile, activity.currentDestinationId())
                assertNull(
                    "an anonymous listener must not have a delete control",
                    activity.findViewById<View?>(R.id.profile_row_delete_account),
                )
            }
        }
    }

    /** Nor a signed-out install. */
    @Test
    fun b_a_signed_out_install_has_no_delete_row() {
        IdentityStore.markRegistered(context, account)
        IdentityStore.signOut(context)

        withMainActivity {
            openProfileAndSettle()
            on { activity ->
                assertEquals(R.id.profile, activity.currentDestinationId())
                assertNull(activity.findViewById<View?>(R.id.profile_row_delete_account))
            }
        }
    }

    /** A registered account with a matching session has it, enabled and not spinning. */
    @Test
    fun c_the_account_screen_has_the_row() {
        onTheAccountScreen {
            on { activity ->
                val row = activity.findViewById<View>(R.id.profile_row_delete_account)
                assertNotNull(row)
                assertTrue(row.isEnabled)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.profile_row_delete_account_progress).visibility)
            }
        }
    }

    // ==================== the confirmations ====================

    /** The first dialog appears and calls nothing. */
    @Test
    fun d_the_first_confirmation_alone_deletes_nothing() {
        onTheAccountScreen {
            tapDeleteRow()
            assertDialogTitle(R.string.delete_account_confirm_title)

            assertEquals("the first step must not reach the orchestrator", 0, auth.deleteCalls)
            assertNull(IdentityStore.deletion(context))
        }
    }

    /** Cancelling the first calls nothing and leaves the account alone. */
    @Test
    fun e_cancelling_the_first_deletes_nothing() {
        onTheAccountScreen {
            tapDeleteRow()
            tapDialog(negative = true)

            assertEquals(0, auth.deleteCalls)
            assertNull(IdentityStore.deletion(context))
            assertEquals(IdentityState.Registered(account), IdentityStore.state(context))
        }
    }

    /** Continuing shows the second, still without calling anything. */
    @Test
    fun f_the_second_confirmation_is_required() {
        onTheAccountScreen {
            tapDeleteRow()
            tapDialog()

            assertDialogTitle(R.string.delete_account_final_title)
            assertEquals("only the final button may delete", 0, auth.deleteCalls)
        }
    }

    /** Cancelling the second calls nothing either. */
    @Test
    fun g_cancelling_the_second_deletes_nothing() {
        onTheAccountScreen {
            tapDeleteRow()
            tapDialog()
            tapDialog(negative = true)

            assertEquals(0, auth.deleteCalls)
            assertNull(IdentityStore.deletion(context))
        }
    }

    // ==================== one request, whatever the listener does ====================

    /**
     * Repeated taps on the destructive button while the request is blocked issue one.
     *
     * The guard being proved is the ViewModel's job, not the disabled view: the second
     * tap is delivered directly to the fragment's click listener, bypassing whatever
     * the row's enabled flag happens to be.
     */
    @Test
    fun h_repeated_final_taps_issue_exactly_one_request() {
        val release = CompletableDeferred<Unit>()
        auth.deleteGate = release
        auth.deleteOutcome = DeleteAccountOutcome.AlreadyDeleted

        onTheAccountScreen {
            confirmBothSteps()

            // The request is in flight and the row says so.
            awaitOn("the row to show its progress") { activity ->
                activity.findViewById<View>(R.id.profile_row_delete_account_progress)
                    .visibility == View.VISIBLE
            }
            on { activity ->
                assertFalse(activity.findViewById<View>(R.id.profile_row_delete_account).isEnabled)
            }

            // Three more attempts, straight at the listener.
            repeat(3) { on { it.findViewById<View>(R.id.profile_row_delete_account).performClick() } }

            release.complete(Unit)
            awaitOn("the deletion to settle") { IdentityStore.state(context) == IdentityState.None }

            assertEquals("exactly one orchestrator invocation", 1, auth.deleteCalls)
        }
    }

    /** A recreation while the request runs does not start a second one. */
    @Test
    fun i_recreation_while_loading_issues_no_second_request() {
        val release = CompletableDeferred<Unit>()
        auth.deleteGate = release
        auth.deleteOutcome = DeleteAccountOutcome.AlreadyDeleted

        onTheAccountScreen {
            confirmBothSteps()
            awaitOn("the request to start") { auth.deleteCalls == 1 }

            // Recreated on the main thread, never through `ActivityScenario.recreate()`.
            // That helper waits for the instrumentation to go idle, and a visible
            // indeterminate ProgressBar means it never does - the recreate would block
            // until the suite timed out. The spinner is the whole point of this test.
            //
            // The ViewModel is fragment-scoped and survives the configuration change,
            // which is what carries the in-flight job across it.
            on { it.recreate() }
            awaitOn("the account screen to come back") { activity ->
                activity.currentDestinationId() == R.id.profile_authenticated
            }

            release.complete(Unit)
            awaitOn("the deletion to settle") { IdentityStore.state(context) == IdentityState.None }

            assertEquals("a rotation must not re-issue the request", 1, auth.deleteCalls)
        }
    }

    // ==================== the five outcomes ====================

    /** Deleted lands on the ordinary guest screen, with its CTAs back. */
    @Test
    fun j_deleted_reaches_the_normal_guest_state() {
        auth.deleteOutcome = DeleteAccountOutcome.Deleted(1, 1, 1)

        onTheAccountScreen {
            confirmBothSteps()
            awaitOn("the guest profile") { activity ->
                activity.currentDestinationId() == R.id.profile
            }
        }

        assertEquals(IdentityState.None, IdentityStore.state(context))
        assertNull(IdentityStore.deletion(context))
        assertGuestControlsVisible(true)
    }

    /** A definitive refusal stays put, says nothing about success, and re-enables. */
    @Test
    fun k_refused_stays_on_the_account_screen_and_allows_a_retry() {
        auth.deleteOutcome = DeleteAccountOutcome.Refused("42501")

        onTheAccountScreen {
            confirmBothSteps()
            awaitOn("the row to come back") { activity ->
                activity.findViewById<View>(R.id.profile_row_delete_account).isEnabled
            }
            on { activity ->
                assertEquals(R.id.profile_authenticated, activity.currentDestinationId())
            }
        }

        assertEquals(IdentityState.Registered(account), IdentityStore.state(context))
        assertNull("the orchestrator retracts the marker on a refusal", IdentityStore.deletion(context))
    }

    /** Unresolved leaves for the pending guest presentation, keeping REQUESTED. */
    @Test
    fun l_unresolved_shows_the_pending_guest_presentation() {
        auth.deleteOutcome = DeleteAccountOutcome.Failed(
            com.example.musicplayerapp.data.supabase.AuthFailure.NetworkFailure("offline")
        )

        onTheAccountScreen {
            confirmBothSteps()
            awaitOn("the guest profile") { it.currentDestinationId() == R.id.profile }
        }

        assertEquals(DeletionStage.REQUESTED, IdentityStore.deletion(context)!!.stage)
        assertPendingCopy(R.string.profile_deletion_pending_heading)
        assertGuestControlsVisible(false)
    }

    /** CleanupDeferred does the same, with the confirmed copy and CONFIRMED retained. */
    @Test
    fun m_cleanup_deferred_shows_the_confirmed_presentation() {
        auth.deleteOutcome = DeleteAccountOutcome.AlreadyDeleted
        auth.signOutSucceeds = false

        onTheAccountScreen {
            confirmBothSteps()
            awaitOn("the guest profile") { it.currentDestinationId() == R.id.profile }
        }

        assertEquals(DeletionStage.CONFIRMED, IdentityStore.deletion(context)!!.stage)
        assertPendingCopy(R.string.profile_deletion_confirmed_heading)
        assertGuestControlsVisible(false)
    }

    /** With no marker the guest screen is exactly what it always was. */
    @Test
    fun n_the_ordinary_guest_screen_is_unchanged() {
        IdentityStore.adoptAnonymous(context, anonymous)

        withMainActivity {
            openProfileAndSettle()
            on { activity ->
                assertEquals(
                    activity.getString(R.string.profile_guest_heading),
                    activity.text(R.id.profile_guest_heading),
                )
            }
        }
        assertGuestControlsVisible(true)
    }

    // ==================== the mapping, asserted directly ====================

    /**
     * Which outcomes may speak, and which may leave.
     *
     * Asserted as values because the copy rules are the product decision: only
     * `Deleted` may sound like success, and neither `Unresolved` nor `CleanupDeferred`
     * may be phrased as a failure or offer a retry.
     */
    @Test
    fun o_the_outcome_mapping_says_what_it_should() {
        assertEquals(R.string.delete_account_done, DeleteAccountViewModel.Outcome.Deleted.message())
        assertEquals(R.string.delete_account_refused, DeleteAccountViewModel.Outcome.Refused.message())
        assertEquals(
            R.string.delete_account_unavailable,
            DeleteAccountViewModel.Outcome.NotEligible.message(),
        )
        assertNull(DeleteAccountViewModel.Outcome.Unresolved.message())
        assertNull(DeleteAccountViewModel.Outcome.CleanupDeferred.message())

        assertTrue(DeleteAccountViewModel.Outcome.Deleted.leavesTheAccountScreen())
        assertTrue(DeleteAccountViewModel.Outcome.Unresolved.leavesTheAccountScreen())
        assertTrue(DeleteAccountViewModel.Outcome.CleanupDeferred.leavesTheAccountScreen())
        assertFalse(DeleteAccountViewModel.Outcome.Refused.leavesTheAccountScreen())
        assertFalse(DeleteAccountViewModel.Outcome.NotEligible.leavesTheAccountScreen())
    }

    /** The runner's backend still refuses a real deletion. */
    @Test
    fun p_the_instrumentation_backend_refuses_a_production_deletion() {
        TestIsolation.restoreBackends()
        kotlinx.coroutines.runBlocking {
            val outcome = EmailAuthBackend.api(context).deleteAccount("probe")
            assertTrue(
                "the offline backend must refuse: $outcome",
                outcome is DeleteAccountOutcome.Failed,
            )
        }
        // Put the suite's own fake back for @After.
        EmailAuthBackend.overrideForInstrumentation { auth }
    }

    // ==================== helpers ====================

    private fun onTheAccountScreen(body: (ActivityScenario<MainActivity>) -> Unit) {
        IdentityStore.markRegistered(context, account)
        auth.session = account

        withMainActivity { scenario ->
            openProfileAndSettle()
            awaitOn("the account card") { activity ->
                activity.currentDestinationId() == R.id.profile_authenticated &&
                    activity.findViewById<View>(R.id.profile_account_card).visibility == View.VISIBLE
            }
            body(scenario)
        }
    }

    private fun tapDeleteRow() {
        on { it.findViewById<View>(R.id.profile_row_delete_account).performClick() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun confirmBothSteps() {
        tapDeleteRow()
        tapDialog()
        tapDialog()
    }

    /**
     * Presses one of the confirmation's buttons, on the main thread.
     *
     * Deliberately not Espresso. Its root picker waits for the dialog window to take
     * focus, and on this emulator that window reports `has-window-focus=false`
     * indefinitely - a fact about the harness, not about the screen. Pressing the
     * button the dialog actually holds tests the same thing without depending on
     * window focus at all, and matches how the rest of this suite drives views.
     */
    private fun tapDialog(negative: Boolean = false) {
        val which = if (negative) DialogInterface.BUTTON_NEGATIVE else DialogInterface.BUTTON_POSITIVE
        awaitOn("a confirmation dialog") { it.confirmationDialog()?.isShowing == true }
        on { activity ->
            val dialog = activity.confirmationDialog() ?: error("no confirmation on screen")
            dialog.getButton(which).performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun assertDialogTitle(@androidx.annotation.StringRes title: Int) {
        awaitOn("a confirmation dialog") { it.confirmationDialog()?.isShowing == true }
        on { activity ->
            val shown = activity.confirmationDialog()!!
                .findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)
            assertEquals(activity.getString(title), shown?.text.toString())
        }
    }

    /** The confirmation the authenticated profile is currently holding, if any. */
    private fun MainActivity.confirmationDialog(): androidx.appcompat.app.AlertDialog? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment)
            as androidx.navigation.fragment.NavHostFragment
        val profile = host.childFragmentManager.fragments
            .filterIsInstance<ProfileAuthenticatedFragment>()
            .firstOrNull()
        return profile?.confirmation
    }

    private fun assertPendingCopy(heading: Int) {
        withMainActivity {
            openProfileAndSettle()
            on { activity ->
                assertEquals(R.id.profile, activity.currentDestinationId())
                assertEquals(activity.getString(heading), activity.text(R.id.profile_guest_heading))
            }
        }
    }

    private fun assertGuestControlsVisible(visible: Boolean) {
        withMainActivity {
            openProfileAndSettle()
            on { activity ->
                val expected = if (visible) View.VISIBLE else View.GONE
                assertEquals(expected, activity.findViewById<View>(R.id.profile_sign_in).visibility)
                assertEquals(expected, activity.findViewById<View>(R.id.profile_create_account).visibility)
            }
        }
    }

    /**
     * `runOnMainSync`, never `onActivity`.
     *
     * A visible indeterminate `ProgressBar` keeps the instrumentation from ever going
     * idle, and `ActivityScenario.onActivity` waits for idle before it runs - so on a
     * screen with a spinner it blocks until the timeout. This suite has a spinner by
     * design.
     */
    private fun on(block: (MainActivity) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val current = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull() ?: error("no resumed MainActivity")
            block(current)
        }
    }

    private fun awaitOn(what: String, timeoutMs: Long = 20_000, check: (MainActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var satisfied = false
            runCatching { on { satisfied = check(it) } }
            if (satisfied) return
            Thread.sleep(25)
        }
        error("timed out waiting for $what")
    }

    private fun MainActivity.currentDestinationId(): Int? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment)
            as androidx.navigation.fragment.NavHostFragment
        return host.navController.currentDestination?.id
    }

    private fun MainActivity.text(id: Int): String =
        findViewById<android.widget.TextView>(id).text.toString()
}
