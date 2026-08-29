package com.example.musicplayerapp

import android.content.Context
import android.view.View
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.supabase.DeletionRecord
import com.example.musicplayerapp.data.supabase.DeletionStage
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The pending-deletion screen, while the deletion resolves underneath it.
 *
 * ## The staleness this suite exists for
 *
 * The presentation used to be decided once, during view creation. That is exactly the
 * wrong moment: `IdentityReconciler` runs on every start and resolves the deletion
 * moments later, and this screen is where the listener is sitting when it does. The
 * pending copy and the hidden CTAs stayed up until they navigated away and came back.
 *
 * ## And a disappeared marker does not mean "guest"
 *
 * Two different things clear it, and they want opposite screens. A completed cleanup
 * leaves `IdentityState.None`, and the ordinary guest screen is right. A **definitive
 * refusal** clears the marker and leaves `Registered(X)` untouched - the account still
 * exists, and this screen is then the wrong one entirely. Test B is that case, and it
 * is the one a "marker gone, show guest" shortcut would get wrong.
 *
 * Nothing here polls: the screen is registered with `IdentityStore` and the store
 * reports its own writes.
 */
@RunWith(AndroidJUnit4::class)
class DeletionPresentationTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var auth: FakeEmailAuthApi
    private lateinit var sync: RecordingSyncApi

    private val account = "22222222-2222-4222-8222-222222222222"
    private val request = "99999999-9999-4999-8999-999999999999"

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
        ReactionSyncBackend.overrideForInstrumentation({ sync }, CountingIdentity(account).asProvider())

        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
    }

    @After
    fun close() {
        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
        AppDatabase.overrideForInstrumentation(null)
        TestIsolation.restoreBackends()
        if (::db.isInitialized) db.close()
    }

    // ==================== the marker resolving under a live screen ====================

    /**
     * **A.** REQUESTED on screen, cleanup completes, ordinary guest appears in place.
     *
     * No reopening: the fragment is never navigated away from and never recreated.
     */
    @Test
    fun a_requested_resolving_to_none_restores_the_ordinary_guest() {
        IdentityStore.markRegistered(context, account)
        IdentityStore.markDeletionRequested(context, request, account)

        onThePendingGuest(R.string.profile_deletion_pending_heading) {
            // What a completed cleanup leaves behind, while the screen is still up.
            IdentityStore.forgetDeletedAccount(context)

            awaitOn("the ordinary guest copy") { activity ->
                activity.text(R.id.profile_guest_heading) ==
                    activity.getString(R.string.profile_guest_heading)
            }
            on { activity ->
                assertEquals(R.id.profile, activity.currentDestinationId())
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.profile_sign_in).visibility,
                )
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.profile_create_account).visibility,
                )
            }
        }
        assertEquals(IdentityState.None, IdentityStore.state(context))
    }

    /**
     * **B.** A refusal clears the marker and leaves the account. The screen must go up.
     *
     * The case a "marker gone, therefore guest" shortcut gets wrong: nothing was
     * deleted, `Registered(X)` stands and the session matches, so the canonical route
     * is the authenticated profile.
     */
    @Test
    fun b_a_refusal_routes_back_to_the_authenticated_profile() {
        IdentityStore.markRegistered(context, account)
        auth.session = account
        IdentityStore.markDeletionRequested(context, request, account)

        onThePendingGuest(R.string.profile_deletion_pending_heading) {
            // Exactly what a definitive refusal leaves: marker gone, identity intact.
            IdentityStore.clearDeletionMarker(context)

            awaitOn("the authenticated profile") { activity ->
                activity.currentDestinationId() == R.id.profile_authenticated
            }
        }
        assertEquals(IdentityState.Registered(account), IdentityStore.state(context))
        assertNull(IdentityStore.deletion(context))
    }

    /** **C.** CONFIRMED on screen, cleanup finishes, ordinary guest appears. */
    @Test
    fun c_confirmed_resolving_to_none_restores_the_ordinary_guest() {
        IdentityStore.markRegistered(context, account)
        IdentityStore.markDeletionConfirmed(context, request, account)

        onThePendingGuest(R.string.profile_deletion_confirmed_heading) {
            IdentityStore.forgetDeletedAccount(context)

            awaitOn("the ordinary guest copy") { activity ->
                activity.text(R.id.profile_guest_heading) ==
                    activity.getString(R.string.profile_guest_heading)
            }
            on { activity ->
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.profile_sign_in).visibility,
                )
            }
        }
    }

    /** A stage change under a live screen repaints, rather than keeping the old copy. */
    @Test
    fun d_requested_becoming_confirmed_repaints_in_place() {
        IdentityStore.markRegistered(context, account)
        IdentityStore.markDeletionRequested(context, request, account)

        onThePendingGuest(R.string.profile_deletion_pending_heading) {
            IdentityStore.markDeletionConfirmed(context, request, account)

            awaitOn("the confirmed copy") { activity ->
                activity.text(R.id.profile_guest_heading) ==
                    activity.getString(R.string.profile_deletion_confirmed_heading)
            }
            on { activity ->
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.profile_sign_in).visibility,
                )
            }
        }
    }

    /**
     * **D.** The observer goes with the view, and a later change reaches nothing.
     *
     * Asserted by resolving the deletion after the screen has been left: if the
     * registration outlived the view, the callback would touch a destroyed binding and
     * this would crash rather than pass.
     */
    @Test
    fun e_the_observer_is_released_with_the_view() {
        IdentityStore.markRegistered(context, account)
        IdentityStore.markDeletionRequested(context, request, account)

        withMainActivity {
            openProfileAndSettle()
            awaitOn("the pending copy") { activity ->
                activity.text(R.id.profile_guest_heading) ==
                    activity.getString(R.string.profile_deletion_pending_heading)
            }

            // Leave the screen; the view is destroyed with it.
            on { it.onBackPressedDispatcher.onBackPressed() }
            awaitOn("the profile to be gone") { it.currentDestinationId() != R.id.profile }

            // A resolution that now has nobody to tell.
            IdentityStore.forgetDeletedAccount(context)
            Thread.sleep(300)

            on { activity ->
                assertTrue("the activity must still be alive", !activity.isFinishing)
            }
        }
        assertEquals(IdentityState.None, IdentityStore.state(context))
    }

    /** The watcher itself: it reports the value, and stops when closed. */
    @Test
    fun f_the_store_watcher_reports_and_releases() {
        // The watcher reports from whichever thread wrote, so a plain ArrayList would
        // be iterated here while it is being appended to.
        val seen: MutableList<DeletionRecord?> =
            java.util.Collections.synchronizedList(mutableListOf())
        val handle = IdentityStore.watchDeletion(context) { seen += it }

        IdentityStore.markDeletionRequested(context, request, account)
        awaitValue("a REQUESTED report") { seen.snapshot().any { it?.stage == DeletionStage.REQUESTED } }

        IdentityStore.markDeletionConfirmed(context, request, account)
        awaitValue("a CONFIRMED report") { seen.snapshot().any { it?.stage == DeletionStage.CONFIRMED } }

        IdentityStore.clearDeletionMarker(context)
        awaitValue("a cleared report") { seen.snapshot().any { it == null } }

        handle.close()
        val countAtClose = seen.size
        IdentityStore.markDeletionRequested(context, request, account)
        Thread.sleep(300)
        assertEquals("a closed watcher must report nothing", countAtClose, seen.size)
    }

    // ==================== helpers ====================

    /**
     * Opens the pending guest screen and runs [body] **while it is still alive**.
     *
     * The block has to be inside the scenario: `withMainActivity` closes the activity
     * when it returns, and the whole point of this suite is what happens to a screen
     * that is still on display when the deletion resolves underneath it.
     */
    private fun onThePendingGuest(heading: Int, body: () -> Unit) {
        withMainActivity {
            openProfileAndSettle()
            awaitOn("the pending copy") { activity ->
                activity.currentDestinationId() == R.id.profile &&
                    activity.text(R.id.profile_guest_heading) == activity.getString(heading)
            }
            on { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.profile_sign_in).visibility)
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.profile_create_account).visibility,
                )
            }
            body()
        }
    }

    /** `runOnMainSync`, never `onActivity`: see `DeleteAccountUiTest` for why. */
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

    /** A copy taken under the list's own lock, safe to iterate. */
    private fun <T> List<T>.snapshot(): List<T> = synchronized(this) { toList() }

    private fun awaitValue(what: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
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
