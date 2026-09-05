package com.example.musicplayerapp

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.data.supabase.AuthAttempt
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * The authenticated profile: who gets it, what it says, and how a listener leaves.
 *
 * Real activity, real nav graph, real fragments, real `EmailAuthRepository`, real
 * `IdentityReconciler`, real Room - fake auth and fake PostgREST. The seam is at the
 * network and nowhere higher, so a logout here really does write `SIGNED_OUT`,
 * really does leave Room alone, and this suite can assert both.
 *
 * The `on { }` accessor and the timeout rule are the same ones `AuthFormTest` uses,
 * for the same reason: `ActivityScenario.onActivity` waits for the main looper to go
 * idle, and this screen has no spinner but the ones it navigates to do.
 */
@RunWith(AndroidJUnit4::class)
class ProfileAuthenticatedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var auth: FakeEmailAuthApi
    private lateinit var sync: RecordingSyncApi
    private lateinit var identity: CountingIdentity

    private val account = "22222222-2222-4222-8222-222222222222"
    private val anonymous = "11111111-1111-4111-8111-111111111111"
    private val depeche = TrackKey.of("Depeche Mode", "Enjoy the Silence")!!

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

        auth = FakeEmailAuthApi().also { it.uid = account }
        EmailAuthBackend.overrideForInstrumentation { auth }

        sync = RecordingSyncApi()
        identity = CountingIdentity(anonymous)
        ReactionSyncBackend.overrideForInstrumentation({ sync }, identity.asProvider())

        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
    }

    @After
    fun close() {
        auth.release()
        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
        AppDatabase.overrideForInstrumentation(null)
        TestIsolation.restoreBackends()
        db.close()
    }

    // ==================== A-D: who gets which profile ====================

    @Test
    fun a_none_opens_the_guest_profile() = assertProfileFor(IdentityState.None, R.id.profile)

    @Test
    fun b_anonymous_opens_the_guest_profile() {
        IdentityStore.adoptAnonymous(context, anonymous)
        assertProfileIs(R.id.profile)
    }

    @Test
    fun c_signed_out_opens_the_guest_profile() {
        IdentityStore.adoptAnonymous(context, anonymous)
        IdentityStore.signOut(context)
        assertProfileIs(R.id.profile)
    }

    /**
     * The whole point of the PR: a registered listener no longer meets `Вы не вошли`.
     */
    @Test
    fun d_registered_with_a_matching_session_opens_the_account_card() {
        IdentityStore.markRegistered(context, account)
        auth.session = account
        auth.accountName = "Денис"
        auth.accountEmail = "name@example.com"

        withMainActivity { scenario ->
            scenario.openProfile()
            on { assertEquals(R.id.profile_authenticated, it.currentDestinationId()) }

            scenario.await("the session check") {
                it.text(R.id.profile_account_name) == "Денис"
            }
            on { activity ->
                assertEquals("name@example.com", activity.text(R.id.profile_account_email))
                assertEquals("Д", activity.text(R.id.profile_account_avatar_initial))
                assertEquals(
                    "the frame has no bottom bar",
                    View.GONE,
                    activity.findViewById<View>(R.id.bottomNavView).visibility,
                )
            }
        }
    }

    // ============ session truth at the routing boundary ============

    /**
     * **B.** `REGISTERED(X)` with no session must never reach the account card.
     *
     * The state on disk says this install is an account; the plugin holds no token
     * for it. That happens for ordinary reasons - revoked elsewhere, deleted, a
     * logout that died before its commit, an install that has not restored - and in
     * every one of them an account card would be asserting something this device
     * cannot prove.
     *
     * Asserted as *never*, not as *not eventually*: the destination is decided before
     * the navigation, so the authenticated screen is not entered and left, it is not
     * entered.
     */
    @Test
    fun b_registered_without_a_session_never_reaches_the_account_card() {
        IdentityStore.markRegistered(context, account)
        auth.session = null

        val seen = mutableSetOf<Int?>()
        withMainActivity { scenario ->
            // Tapped directly rather than through `openProfile`, which returns as soon
            // as either profile is showing. Sampling from the tap itself is what makes
            // a destination that was entered and then left visible here - and that is
            // exactly the behaviour being ruled out.
            //
            // The sampling starts at the *profile row*, not at the header control:
            // since G1 the control opens settings, and sampling from there would spend
            // most of the window recording R.id.settings while the interesting
            // transition had not been asked for yet.
            scenario.tap(R.id.settings_entry)
            scenario.await("the settings shell") { it.currentDestinationId() == R.id.settings }
            scenario.tap(R.id.settings_row_profile)
            repeat(60) {
                runCatching { on { seen += it.currentDestinationId() } }
                Thread.sleep(25)
            }
            on { assertEquals(R.id.profile, it.currentDestinationId()) }
        }

        assertFalse(
            "the authenticated profile must never be entered, not even briefly",
            seen.contains(R.id.profile_authenticated),
        )
        assertEquals(0, identity.calls)
    }

    /**
     * **C and F.** `REGISTERED(X)` while the session belongs to `Y`.
     *
     * X's card must never appear. What happens instead is the existing G-A4b2 rule,
     * not a new one: the session is what RLS will actually enforce, so reconciliation
     * adopts `Y` and the routing decision is taken again against the settled state.
     * Whatever is shown is `Y`'s, and none of `X` leaks into it.
     */
    @Test
    fun c_and_f_a_session_for_another_uid_never_shows_the_first_account() {
        val other = "33333333-3333-4333-8333-333333333333"
        IdentityStore.markRegistered(context, account)
        auth.session = other
        auth.accountName = "Другой"
        auth.accountEmail = "other@example.com"

        withMainActivity { scenario ->
            scenario.openProfile()
            scenario.await("the route to settle") { it.currentDestinationId() != null }

            on { activity ->
                if (activity.currentDestinationId() == R.id.profile_authenticated) {
                    // Reconciliation adopted the live session, which is the contract.
                    // Nothing of the stale identity may be on the card.
                    val name = activity.text(R.id.profile_account_name)
                    val email = activity.text(R.id.profile_account_email)
                    assertFalse("the stale uid leaked into the card", name.contains(account))
                    assertFalse("the stale uid leaked into the card", email.contains(account))
                    assertEquals("Другой", name)
                    assertEquals("other@example.com", email)
                }
            }
        }

        // Whichever way it resolved, the local state agrees with the live session -
        // it is never left claiming X while holding Y.
        assertEquals(IdentityState.Registered(other), IdentityStore.state(context))
        assertEquals("no identity may be minted deciding this", 0, identity.calls)
    }

    /**
     * **E.** Reconciliation, not the persisted state, decides where a tap lands.
     *
     * The clearest case: a logout that died between clearing the session and
     * committing `SIGNED_OUT`. The disk still says `REGISTERED`; the marker says what
     * was intended. Opening the profile must finish that logout and show the guest
     * screen, rather than the account card the stale state would have produced.
     */
    @Test
    fun e_reconciliation_decides_the_route() {
        IdentityStore.markRegistered(context, account)
        IdentityStore.markAuthAttempt(context, AuthAttempt.SIGN_OUT)
        auth.session = null

        withMainActivity { scenario ->
            scenario.openProfile()
            scenario.await("the guest profile") { it.currentDestinationId() == R.id.profile }
        }

        assertEquals(IdentityState.SignedOut(account), IdentityStore.state(context))
        assertEquals(null, IdentityStore.authAttempt(context))
        assertEquals(0, identity.calls)
    }

    /** **D.** None of the three cases above may create an identity. */
    @Test
    fun d_deciding_the_route_never_mints_an_anonymous_identity() {
        val other = "33333333-3333-4333-8333-333333333333"

        for (session in listOf(account, null, other)) {
            IdentityStore.clearForTest(context)
            IdentityStore.markRegistered(context, account)
            auth.session = session

            withMainActivity { scenario ->
                scenario.openProfile()
                scenario.await("a decision") { it.currentDestinationId() != null }
            }
        }

        assertEquals(
            "the minting boundary is the only thing that can create an identity, " +
                "and nothing on this path may reach it",
            0,
            identity.calls,
        )
    }

    // ==================== E: nothing here mints ====================

    @Test
    fun e_opening_either_profile_never_mints_an_anonymous_identity() {
        // From NONE, the state that could mint if anything asked the identity boundary.
        withMainActivity { scenario ->
            scenario.openProfile()
            scenario.tap(R.id.profile_back)
        }
        assertEquals(IdentityState.None, IdentityStore.state(context))

        IdentityStore.markRegistered(context, account)
        auth.session = account
        withMainActivity { scenario ->
            scenario.openProfile()
            scenario.await("the session check") { it.currentDestinationId() != null }
        }
        assertEquals(IdentityState.Registered(account), IdentityStore.state(context))
        assertEquals("the minting boundary must never be reached", 0, identity.calls)
    }

    // ==================== F-H: arriving from the auth screens ====================

    @Test
    fun f_a_successful_sign_in_lands_on_the_account_card() = arriveByAuth(R.id.profile_sign_in) {
        it.type(R.id.auth_email, "listener@example.com")
        it.type(R.id.auth_password, "s3cret!!")
    }

    @Test
    fun g_a_successful_registration_lands_on_the_account_card() =
        arriveByAuth(R.id.profile_create_account) {
            it.type(R.id.auth_name, "Денис")
            it.type(R.id.auth_email, "listener@example.com")
            it.type(R.id.auth_password, "s3cret!!")
        }

    /**
     * **H.** Back from the account card must reach whatever opened the profile - not
     * the guest card that is now false, and not the form that was just satisfied.
     *
     * Since G1 "whatever opened the profile" is the settings shell, and the screen
     * that opened *that* is HOME. The claim is unchanged and the walk is one step
     * longer: what must not appear on the way is an auth screen or the guest profile,
     * and both Backs are asserted for that rather than only the first.
     */
    @Test
    fun h_no_auth_screen_is_left_in_the_back_stack() {
        withMainActivity { scenario ->
            scenario.openProfile()
            scenario.tap(R.id.profile_sign_in)
            scenario.type(R.id.auth_email, "listener@example.com")
            scenario.type(R.id.auth_password, "s3cret!!")
            scenario.tap(R.id.auth_submit)
            scenario.await("the account card") {
                it.currentDestinationId() == R.id.profile_authenticated
            }

            // One Back, and we are at the settings shell the profile row was tapped
            // on - not at an auth screen, and not at the guest card.
            scenario.tap(R.id.profile_back)
            on { activity ->
                assertEquals(
                    "Back must not reach the auth screens or the guest profile",
                    R.id.settings,
                    activity.currentDestinationId(),
                )
                // Settings has no bottom bar either, so it is still hidden here.
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.bottomNavView).visibility,
                )
            }

            // And one more reaches HOME, with the bar back.
            scenario.tap(R.id.settings_back)
            on { activity ->
                assertEquals(R.id.home, activity.currentDestinationId())
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.bottomNavView).visibility,
                )
            }
        }
    }

    // ==================== I-J: the fallbacks, on the real screen ====================

    @Test
    fun i_a_session_with_no_display_name_shows_the_neutral_fallback() {
        IdentityStore.markRegistered(context, account)
        auth.session = account
        auth.accountName = null
        auth.accountEmail = "name@example.com"

        openAccountCard { activity ->
            assertEquals("Пользователь", activity.text(R.id.profile_account_name))
            assertEquals("П", activity.text(R.id.profile_account_avatar_initial))
            // Never a uid: it is a database key, not a name.
            assertFalse(activity.text(R.id.profile_account_name).contains(account))
        }
    }

    @Test
    fun j_a_session_with_no_address_says_so_rather_than_showing_a_uid() {
        IdentityStore.markRegistered(context, account)
        auth.session = account
        auth.accountEmail = null

        openAccountCard { activity ->
            assertEquals("Email недоступен", activity.text(R.id.profile_account_email))
            assertFalse(activity.text(R.id.profile_account_email).contains(account))
        }
    }

    // ==================== K-L: the corrected avatar centring ====================

    /**
     * The one approved deviation from the frames.
     *
     * 2517:2680 puts the initial's 32dp line box at y=18 inside a 64dp circle, where
     * centred is 16 - two pixels low, which the owner spotted. The correction is
     * `gravity="center"` on a TextView filling the circle, so the glyph is placed by
     * its own metrics rather than by an offset that happened to suit `Д`.
     *
     * Asserted as *the ink is centred*, not as a coordinate: a hard-coded offset
     * would pass a coordinate check for one letter and fail for the next.
     */
    @Test
    fun k_and_l_the_avatar_initial_is_centred_for_any_letter() {
        for ((name, expected) in listOf("Денис" to "Д", null to "П")) {
            IdentityStore.clearForTest(context)
            IdentityStore.markRegistered(context, account)
            auth.session = account
            auth.accountName = name

            openAccountCard { activity ->
                val circle = activity.findViewById<View>(R.id.profile_account_avatar)
                val avatar = activity.findViewById<TextView>(R.id.profile_account_avatar_initial)
                assertEquals(expected, avatar.text.toString())

                // The circle is where the frame puts it, and stays there.
                val density = activity.resources.displayMetrics.density
                assertEquals(
                    "the avatar circle must not move or resize",
                    (64 * density).toInt(), circle.width,
                )

                val layout = avatar.layout ?: run {
                    fail("the initial was not laid out")
                    return@openAccountCard
                }

                // The glyph's **ink**, not its line box. A line box is centred by
                // construction the moment gravity is center, which would make this
                // assertion agree with itself; the ink is what somebody looking at
                // the circle actually sees, and it is what the frame gets wrong.
                val ink = android.graphics.Rect()
                val text = avatar.text.toString()
                avatar.paint.getTextBounds(text, 0, text.length, ink)

                // Where TextView will draw that line: the layout is centred in the
                // view's inner box, so the baseline lands here.
                val inner = avatar.height - avatar.paddingTop - avatar.paddingBottom
                val layoutTop = avatar.paddingTop + (inner - layout.height) / 2f
                val baseline = layoutTop + layout.getLineBaseline(0)

                val inkTop = baseline + ink.top + avatar.translationY
                val inkBottom = baseline + ink.bottom + avatar.translationY
                val above = inkTop
                val below = avatar.height - inkBottom

                assertTrue(
                    "$expected sits ${"%.2f".format((above - below) / density / 2)}dp off centre " +
                        "vertically (${"%.1f".format(above)}px above, ${"%.1f".format(below)}px below)",
                    kotlin.math.abs(above - below) <= 1.5f,
                )

                val lineLeft = layout.getLineLeft(0)
                val lineRight = layout.getLineRight(0)
                val left = lineLeft + ink.left + avatar.translationX
                val right = avatar.width - (lineLeft + ink.right + avatar.translationX)
                assertTrue(
                    "$expected sits ${"%.2f".format((left - right) / density / 2)}dp off centre " +
                        "horizontally (${"%.1f".format(left)}px left, ${"%.1f".format(right)}px right)",
                    kotlin.math.abs(left - right) <= 1.5f,
                )
                assertTrue("the line box must span the circle", lineRight > lineLeft)
            }
        }
    }

    // ==================== M: the session is not taken on trust ====================

    /**
     * **M.** `REGISTERED` on disk with no session is not an account.
     *
     * A token revoked elsewhere, or a logout that died before its commit. The screen
     * asks, gets nothing, and steps aside to the guest presentation rather than
     * drawing a card it cannot fill - and it fabricates neither a session nor a name.
     */
    @Test
    fun m_registered_without_a_session_falls_back_to_the_guest_profile() {
        IdentityStore.markRegistered(context, account)
        auth.session = null

        withMainActivity { scenario ->
            scenario.openProfile()
            scenario.await("the guest fallback") { it.currentDestinationId() == R.id.profile }

            on { activity ->
                assertNotNull(
                    "the guest card must be showing",
                    activity.findViewById<View>(R.id.profile_guest_card),
                )
            }
        }

        assertEquals("nothing may be minted by finding out", 0, identity.calls)
    }

    // ==================== N-O: the last-sync row ====================

    /**
     * **G.** A restore alone is a synchronisation, and the row now says so.
     *
     * This is the case that used to read `Ещё не синхронизировалось` on a device that
     * had just pulled somebody's whole Collection down - the last place this screen
     * still claimed something untrue. G-A7c gave the restore its own timestamp; the
     * row reports the more recent of the two.
     */
    @Test
    fun a_restore_alone_is_reported_as_a_synchronisation() {
        IdentityStore.markRegistered(context, account)
        auth.session = account
        LastSyncStore.recordPullSuccess(context, account, System.currentTimeMillis() - 3 * 60_000L)

        openAccountCard { activity ->
            assertEquals("3 мин назад", activity.text(R.id.profile_row_last_sync_value))
        }
    }

    /** **H.** Both recorded, the upload newer: the upload is what is shown. */
    @Test
    fun the_newer_of_the_two_is_shown_when_it_is_the_upload() {
        IdentityStore.markRegistered(context, account)
        auth.session = account
        val now = System.currentTimeMillis()
        LastSyncStore.recordPullSuccess(context, account, now - 40 * 60_000L)
        LastSyncStore.recordForTest(context, account, now - 5 * 60_000L)

        openAccountCard { activity ->
            assertEquals("5 мин назад", activity.text(R.id.profile_row_last_sync_value))
        }
    }

    /** **I.** Both recorded, the restore newer: the restore is what is shown. */
    @Test
    fun the_newer_of_the_two_is_shown_when_it_is_the_restore() {
        IdentityStore.markRegistered(context, account)
        auth.session = account
        val now = System.currentTimeMillis()
        LastSyncStore.recordForTest(context, account, now - 40 * 60_000L)
        LastSyncStore.recordPullSuccess(context, account, now - 5 * 60_000L)

        openAccountCard { activity ->
            assertEquals("5 мин назад", activity.text(R.id.profile_row_last_sync_value))
        }
    }

    @Test
    fun n_a_recorded_sync_renders_as_a_relative_time() {
        IdentityStore.markRegistered(context, account)
        auth.session = account
        LastSyncStore.recordForTest(context, account, System.currentTimeMillis() - 2 * 60_000L)

        openAccountCard { activity ->
            assertEquals("2 мин назад", activity.text(R.id.profile_row_last_sync_value))
        }
    }

    @Test
    fun o_an_install_that_never_synced_says_so() {
        IdentityStore.markRegistered(context, account)
        auth.session = account

        openAccountCard { activity ->
            assertEquals(
                "Ещё не синхронизировалось",
                activity.text(R.id.profile_row_last_sync_value),
            )
        }
    }

    /**
     * **C and D.** The row is about the account on the screen, not about the phone.
     *
     * An install that signed out of one account and into another has synchronised
     * nothing as the new one. Showing the previous account's time would be answering
     * a question nobody asked - and it is the specific case that made these
     * timestamps user-scoped rather than global.
     *
     * Both directions are seeded for the stranger, so neither `lastUploadAt` nor
     * `lastPullAt` can leak; and X's own history is asserted still on disk afterwards,
     * because the fix is scoping, not erasure. What was true of X stays true of X.
     */
    @Test
    fun c_and_d_another_accounts_sync_history_is_never_shown_as_this_ones() {
        val other = "33333333-3333-4333-8333-333333333333"
        val now = System.currentTimeMillis()
        LastSyncStore.recordForTest(context, other, now - 2 * 60_000L)
        LastSyncStore.recordPullSuccess(context, other, now - 60_000L)

        IdentityStore.markRegistered(context, account)
        auth.session = account

        openAccountCard { activity ->
            assertEquals(
                "Ещё не синхронизировалось",
                activity.text(R.id.profile_row_last_sync_value),
            )
        }

        assertEquals(
            "and the other account keeps its own history - scoped, not cleared",
            now - 60_000L,
            LastSyncStore.lastSyncAt(context, other),
        )
    }

    @Test
    fun opening_the_profile_does_not_record_a_sync() {
        IdentityStore.markRegistered(context, account)
        auth.session = account

        openAccountCard { }

        // A row that updated the timestamp it displays would always read "только что".
        assertEquals(null, LastSyncStore.lastUploadAt(context, account))
    }

    // ==================== P-Q: the two rows that lead nowhere ====================

    @Test
    fun p_and_q_the_avatar_and_change_password_rows_cannot_navigate() {
        IdentityStore.markRegistered(context, account)
        auth.session = account

        openAccountCard { activity ->
            for (id in listOf(R.id.profile_row_avatar, R.id.profile_row_change_password)) {
                val row = activity.findViewById<View>(id)
                assertEquals(View.VISIBLE, row.visibility)
                assertFalse("${activity.resources.getResourceEntryName(id)} must be inert", row.isClickable)
                row.performClick()
            }
            // Both chevrons are drawn, because the frame draws them.
            assertEquals(View.VISIBLE, activity.visibilityOf(R.id.profile_row_avatar_chevron))
            assertEquals(View.VISIBLE, activity.visibilityOf(R.id.profile_row_change_password_chevron))
        }

        // And nothing was requested by tapping them.
        assertTrue(auth.resetRequests.isEmpty())
        assertTrue(auth.passwordUpdates.isEmpty())
    }

    // ==================== R-T: logout ====================

    @Test
    fun r_s_t_logout_pauses_the_account_and_leaves_everything_local_alone() {
        IdentityStore.markRegistered(context, account)
        auth.session = account
        runBlocking {
            db.reactionDao().like(depeche, "Depeche Mode", "Enjoy the Silence", "myata", 1_000L, 1_000L)
        }

        withMainActivity { scenario ->
            scenario.openProfile()
            scenario.await("the account card") { it.text(R.id.profile_account_name).isNotEmpty() }

            scenario.tap(R.id.profile_row_sign_out)
            scenario.await("the guest profile") { it.currentDestinationId() == R.id.profile }

            // Back must not reach the account card that is no longer true. Since G1
            // it lands on the settings shell - the screen the profile row was tapped
            // on - and the account card is not on the stack behind it.
            scenario.tap(R.id.profile_back)
            on {
                assertEquals(
                    "the authenticated profile must be gone from the stack",
                    R.id.settings,
                    it.currentDestinationId(),
                )
            }
            scenario.tap(R.id.settings_back)
            on {
                assertEquals(
                    "and the step above settings is the screen that opened it",
                    R.id.home,
                    it.currentDestinationId(),
                )
            }
        }

        assertEquals(IdentityState.SignedOut(account), IdentityStore.state(context))
        assertEquals("the session must be cleared on this device", 1, auth.localSignOuts)
        assertEquals("the Collection is untouched", 1, runBlocking { db.reactionDao().allReactions().size })
        assertEquals("signing out is not a route to a new identity", 0, identity.calls)
        assertEquals(null, IdentityStore.authAttempt(context))
    }

    // ==================== U: a logout a crash interrupted ====================

    /**
     * **U.** The ambiguous crash point, and the reason logout has a durable marker.
     *
     * `REGISTERED(uid)` on disk with no session is exactly what an ordinary offline
     * install looks like. Promoting every one of those to `SIGNED_OUT` would sign
     * people out for losing wifi; leaving them all alone would strand a half-finished
     * logout forever. The marker written before the first step is the only thing that
     * separates them, and reconciliation finishes what the crash did not.
     */
    @Test
    fun u_an_interrupted_logout_is_completed_by_reconciliation() {
        IdentityStore.markRegistered(context, account)
        IdentityStore.markAuthAttempt(context, AuthAttempt.SIGN_OUT)
        auth.session = null   // the remote sign-out happened; the commit did not

        runBlocking {
            com.example.musicplayerapp.data.supabase.IdentityReconciler
                .reconcile(context, sessionUid = null)
        }

        assertEquals(IdentityState.SignedOut(account), IdentityStore.state(context))
        assertEquals("the marker is spent", null, IdentityStore.authAttempt(context))
    }

    @Test
    fun an_offline_registered_install_is_not_mistaken_for_an_interrupted_logout() {
        // The same two facts on disk, without the marker. This listener is on a train,
        // not signed out, and must still be registered when they land.
        IdentityStore.markRegistered(context, account)
        auth.session = null

        runBlocking {
            com.example.musicplayerapp.data.supabase.IdentityReconciler
                .reconcile(context, sessionUid = null)
        }

        assertEquals(IdentityState.Registered(account), IdentityStore.state(context))
    }

    @Test
    fun a_logout_that_died_after_its_commit_only_clears_the_marker() {
        IdentityStore.adoptAnonymous(context, account)
        IdentityStore.signOut(context)
        IdentityStore.markAuthAttempt(context, AuthAttempt.SIGN_OUT)

        runBlocking {
            com.example.musicplayerapp.data.supabase.IdentityReconciler
                .reconcile(context, sessionUid = null)
        }

        assertEquals(IdentityState.SignedOut(account), IdentityStore.state(context))
        assertEquals(null, IdentityStore.authAttempt(context))
    }

    // ==================== W: the owner's own vectors ====================

    @Test
    fun w_the_rows_carry_the_exact_supplied_drawables() {
        IdentityStore.markRegistered(context, account)
        auth.session = account

        openAccountCard { activity ->
            // Resolving each one proves the drawable exists and inflates; the paths
            // themselves are the owner's export, converted verbatim.
            for (id in listOf(
                R.drawable.ic_profile_clock, R.drawable.ic_profile_person,
                R.drawable.ic_profile_document, R.drawable.ic_profile_logout,
                R.drawable.ic_profile_chevron, R.drawable.ic_collection_sync,
                R.drawable.ic_profile_back,
            )) {
                assertNotNull(
                    activity.resources.getResourceEntryName(id),
                    androidx.core.content.ContextCompat.getDrawable(activity, id),
                )
            }
        }
    }

    // ==================== helpers ====================

    private fun assertProfileFor(state: IdentityState, expected: Int) {
        assertEquals(state, IdentityStore.state(context))
        assertProfileIs(expected)
    }

    private fun assertProfileIs(expected: Int) {
        withMainActivity { scenario ->
            scenario.openProfile()
            on { assertEquals(expected, it.currentDestinationId()) }
        }
    }

    private fun openAccountCard(check: (MainActivity) -> Unit) {
        withMainActivity { scenario ->
            scenario.openProfile()
            scenario.await("the account card") {
                it.currentDestinationId() == R.id.profile_authenticated &&
                    it.text(R.id.profile_account_name).isNotEmpty()
            }
            on(check)
        }
    }

    private fun arriveByAuth(cta: Int, fill: (ActivityScenario<MainActivity>) -> Unit) {
        withMainActivity { scenario ->
            scenario.openProfile()
            scenario.tap(cta)
            fill(scenario)
            scenario.tap(R.id.auth_submit)

            scenario.await("the account card") {
                it.currentDestinationId() == R.id.profile_authenticated
            }
        }
        assertEquals(IdentityState.Registered(account), IdentityStore.state(context))
    }

    /** See [openProfileAndSettle]: the route resolves off the tap, so this waits. */
    private fun ActivityScenario<MainActivity>.openProfile() = openProfileAndSettle()

    private fun ActivityScenario<MainActivity>.type(id: Int, value: String) {
        on { it.findViewById<android.widget.EditText>(id).setText(value) }
        sync()
    }

    private fun ActivityScenario<MainActivity>.tap(id: Int) {
        on { it.findViewById<View>(id).performClick() }
        sync()
    }

    private fun ActivityScenario<MainActivity>.await(
        what: String,
        timeoutMs: Long = 15_000,
        check: (MainActivity) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var satisfied = false
            runCatching { on { satisfied = check(it) } }
            if (satisfied) return
            Thread.sleep(25)
        }
        fail("timed out after ${timeoutMs}ms waiting for $what")
    }

    /** See `AuthFormTest`: `onActivity` waits for an idle looper, and screens animate. */
    private fun on(block: (MainActivity) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val current = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull() ?: error("no resumed MainActivity")
            block(current)
        }
    }

    private fun sync() = InstrumentationRegistry.getInstrumentation().runOnMainSync { }

    private fun MainActivity.text(id: Int): String =
        findViewById<TextView>(id)?.text?.toString().orEmpty()

    private fun MainActivity.visibilityOf(id: Int): Int = findViewById<View>(id).visibility

    private fun MainActivity.currentDestinationId(): Int? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment)
            as androidx.navigation.fragment.NavHostFragment
        return host.navController.currentDestination?.id
    }
}
