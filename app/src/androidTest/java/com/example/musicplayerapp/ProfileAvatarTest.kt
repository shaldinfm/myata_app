package com.example.musicplayerapp

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.supabase.AuthFailure
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * G6a - `Выбор аватара`, driven through the real activity, nav graph and fragments.
 *
 * Fake auth only, like [ProfileAuthenticatedTest]: the account's `user_metadata` lives
 * in [FakeEmailAuthApi.accountAvatar], so "the profile shows what was saved" is proved
 * by the profile reading it back from the account, not from anything the picker left
 * in memory. Nothing here reaches Supabase.
 */
@RunWith(AndroidJUnit4::class)
class ProfileAvatarTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var auth: FakeEmailAuthApi

    private val account = "22222222-2222-4222-8222-222222222222"

    @get:Rule
    val timeout: Timeout = Timeout.builder()
        .withTimeout(120, TimeUnit.SECONDS)
        .withLookingForStuckThread(true)
        .build()

    @Before
    fun open() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
            ).close()
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        AppDatabase.overrideForInstrumentation(db)

        auth = FakeEmailAuthApi().also {
            it.uid = account
            it.session = account
            it.accountName = "Денис"
            it.accountEmail = "name@example.com"
        }
        EmailAuthBackend.overrideForInstrumentation { auth }
        ReactionSyncBackend.overrideForInstrumentation({ RecordingSyncApi() }, CountingIdentity(null).asProvider())

        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
        IdentityStore.markRegistered(context, account)
        com.example.musicplayerapp.data.supabase.AccountRefresh.resetForTest()
    }

    @After
    fun close() {
        auth.release()
        auth.avatarGate?.complete(Unit)
        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
        AppDatabase.overrideForInstrumentation(null)
        TestIsolation.restoreBackends()
        db.close()
    }

    // ==================== default ====================

    @Test
    fun a_no_avatar_is_the_initial_and_a_picker_with_nothing_ringed() {
        withMainActivity {
            openAccountCard()
            on { activity ->
                assertEquals(View.VISIBLE, activity.visibility(R.id.profile_account_avatar_initial))
                assertEquals(View.GONE, activity.visibility(R.id.profile_account_avatar_image))
                assertEquals("Д", activity.text(R.id.profile_account_avatar_initial))
                assertEquals(
                    "Ваш аватар: аватар не выбран",
                    activity.findViewById<View>(R.id.profile_account_avatar).contentDescription,
                )
            }

            openPicker()
            on { activity ->
                assertEquals(
                    "the frame has no bottom bar",
                    View.GONE,
                    activity.visibility(R.id.bottomNavView),
                )
                assertEquals((1..24).map { "myata-%02d".format(it) }, activity.cells().map { it.tag })
                assertEquals(
                    (1..24).map { "Аватар $it из 24" },
                    activity.cells().map { it.contentDescription.toString() },
                )
                assertTrue("nothing ringed", activity.cells().none { it.isSelected })
                assertEquals(View.VISIBLE, activity.visibility(R.id.avatar_current_initial))
                assertEquals(View.GONE, activity.visibility(R.id.avatar_current_image))
                assertEquals(
                    "Текущий аватар: аватар не выбран",
                    activity.findViewById<View>(R.id.avatar_current).contentDescription,
                )
            }
        }
        assertTrue(auth.avatarUpdates.isEmpty())
    }

    @Test
    fun b_an_unknown_stored_key_falls_back_to_the_default() {
        auth.accountAvatar = "myata-99"
        withMainActivity {
            openAccountCard()
            on { activity ->
                assertEquals(View.VISIBLE, activity.visibility(R.id.profile_account_avatar_initial))
                assertEquals(View.GONE, activity.visibility(R.id.profile_account_avatar_image))
            }
            openPicker()
            on { assertTrue(it.cells().none { cell -> cell.isSelected }) }
        }
    }

    // ==================== choosing ====================

    @Test
    fun c_a_tap_rings_the_cell_and_moves_the_preview_at_once_and_writes_nothing() {
        withMainActivity {
            openAccountCard()
            openPicker()

            for (key in listOf("myata-03", "myata-14", "myata-23")) {
                tapCell(key)
                on { activity ->
                    val selected = activity.cells().filter { it.isSelected }.map { it.tag }
                    assertEquals(listOf(key), selected)
                    activity.cells().forEach { cell ->
                        assertEquals(
                            "badge only on the ringed cell",
                            if (cell.tag == key) View.VISIBLE else View.GONE,
                            cell.findViewById<View>(R.id.avatar_cell_badge).visibility,
                        )
                        if (Build.VERSION.SDK_INT >= 30) {
                            assertEquals(
                                if (cell.tag == key) "Выбран" else "Не выбран",
                                ViewCompat.getStateDescription(cell),
                            )
                        }
                    }
                    assertEquals(View.VISIBLE, activity.visibility(R.id.avatar_current_image))
                    assertEquals(View.GONE, activity.visibility(R.id.avatar_current_initial))
                    val number = key.removePrefix("myata-").toInt()
                    assertEquals(
                        "Текущий аватар: Аватар $number из 24",
                        activity.findViewById<View>(R.id.avatar_current).contentDescription,
                    )
                }
            }
        }
        assertTrue("choosing is not saving", auth.avatarUpdates.isEmpty())
        assertEquals(null, auth.accountAvatar)
    }

    // ==================== saving and reading back ====================

    @Test
    fun d_save_writes_once_returns_and_the_profile_reads_it_back_including_after_restart() {
        withMainActivity {
            openAccountCard()
            openPicker()
            tapCell("myata-11")
            tap(R.id.avatar_save)

            await("back on the profile with the avatar") { activity ->
                activity.currentDestinationIdOrNull() == R.id.profile_authenticated &&
                    activity.visibility(R.id.profile_account_avatar_image) == View.VISIBLE
            }
            on { activity ->
                assertEquals(View.GONE, activity.visibility(R.id.profile_account_avatar_initial))
                assertEquals(
                    "Ваш аватар: Аватар 11 из 24",
                    activity.findViewById<View>(R.id.profile_account_avatar).contentDescription,
                )
            }

            // Reopened, the picker rings what the account now holds.
            openPicker()
            on { activity ->
                assertEquals(listOf("myata-11"), activity.cells().filter { it.isSelected }.map { it.tag })
            }
        }
        assertEquals(listOf("myata-11"), auth.avatarUpdates)
        assertEquals("myata-11", auth.accountAvatar)

        // A new activity: nothing of the picker survives in memory, and the card still
        // shows the avatar because it reads the account.
        withMainActivity {
            openAccountCard()
            await("the saved avatar") {
                it.visibility(R.id.profile_account_avatar_image) == View.VISIBLE
            }
            on { activity ->
                assertEquals(
                    "Ваш аватар: Аватар 11 из 24",
                    activity.findViewById<View>(R.id.profile_account_avatar).contentDescription,
                )
            }
        }
    }

    @Test
    fun e_saving_the_avatar_the_account_already_has_just_returns() {
        auth.accountAvatar = "myata-02"
        withMainActivity {
            openAccountCard()
            openPicker()
            tap(R.id.avatar_save)
            await("back on the profile") { it.currentDestinationIdOrNull() == R.id.profile_authenticated }
        }
        assertTrue(auth.avatarUpdates.isEmpty())
    }

    @Test
    fun f_back_without_saving_leaves_the_account_alone() {
        auth.accountAvatar = "myata-02"
        withMainActivity {
            openAccountCard()
            openPicker()
            tapCell("myata-09")
            back()
            await("back on the profile") { activity ->
                activity.currentDestinationIdOrNull() == R.id.profile_authenticated &&
                    activity.visibility(R.id.profile_account_avatar_image) == View.VISIBLE
            }
            on { activity ->
                assertEquals(
                    "Ваш аватар: Аватар 2 из 24",
                    activity.findViewById<View>(R.id.profile_account_avatar).contentDescription,
                )
            }
        }
        assertTrue(auth.avatarUpdates.isEmpty())
        assertEquals("myata-02", auth.accountAvatar)
    }

    @Test
    fun g_a_failed_save_stays_on_the_picker_with_the_choice_kept() {
        auth.avatarFailure = AuthFailure.NetworkFailure("offline")
        withMainActivity {
            openAccountCard()
            openPicker()
            tapCell("myata-07")
            tap(R.id.avatar_save)
            await("the save to settle") { activity ->
                auth.avatarUpdates.isNotEmpty() &&
                    activity.findViewById<View>(R.id.avatar_save)?.isEnabled == true
            }
            on { activity ->
                assertEquals(R.id.profile_avatar, activity.currentDestinationIdOrNull())
                assertEquals(listOf("myata-07"), activity.cells().filter { it.isSelected }.map { it.tag })
            }
        }
        assertEquals(listOf("myata-07"), auth.avatarUpdates)
        assertEquals(null, auth.accountAvatar)
    }

    @Test
    fun h_save_tapped_twice_while_in_flight_is_one_request() {
        auth.avatarGate = CompletableDeferred()
        withMainActivity {
            openAccountCard()
            openPicker()
            tapCell("myata-05")
            on { activity ->
                val save = activity.findViewById<View>(R.id.avatar_save)
                save.performClick()
                save.performClick()
            }
            await("the request to start") { auth.avatarUpdates.isNotEmpty() }
            on { it.findViewById<View>(R.id.avatar_save).performClick() }
            auth.avatarGate?.complete(Unit)
            await("back on the profile") { it.currentDestinationIdOrNull() == R.id.profile_authenticated }
        }
        assertEquals(listOf("myata-05"), auth.avatarUpdates)
    }

    // ==================== 4x6: the last row is reachable ====================

    /**
     * 24 avatars in six rows do not fit above the fold on a phone, and the picker scrolls
     * rather than shrinking. This proves the scroll actually reaches the bottom: after
     * scrolling, the whole of `myata-24` and the whole of `Сохранить` are on screen, and
     * choosing and saving from there works.
     */
    @Test
    fun j_the_last_row_and_save_are_reachable_by_scrolling() {
        withMainActivity {
            openAccountCard()
            openPicker()

            on { activity ->
                activity.findViewById<android.widget.ScrollView>(R.id.avatar_scroll)
                    .fullScroll(View.FOCUS_DOWN)
            }
            await("the scroll to settle at the bottom") { activity ->
                val scroll = activity.findViewById<android.widget.ScrollView>(R.id.avatar_scroll)
                !scroll.canScrollVertically(1)
            }
            on { activity ->
                val last = activity.cells().last()
                assertEquals("myata-24", last.tag)
                assertTrue("myata-24 is wholly on screen", activity.wholeOnScreen(last))
                assertTrue(
                    "Сохранить is wholly on screen",
                    activity.wholeOnScreen(activity.findViewById(R.id.avatar_save)),
                )
            }

            tapCell("myata-24")
            tap(R.id.avatar_save)
            await("the profile with myata-24") { activity ->
                activity.currentDestinationIdOrNull() == R.id.profile_authenticated &&
                    activity.visibility(R.id.profile_account_avatar_image) == View.VISIBLE
            }
            on { activity ->
                assertEquals(
                    "Ваш аватар: Аватар 24 из 24",
                    activity.findViewById<View>(R.id.profile_account_avatar).contentDescription,
                )
            }
        }
        assertEquals(listOf("myata-24"), auth.avatarUpdates)
    }

    /**
     * The card applies what the picker hands back, on the view that is showing.
     *
     * A Save that lands while the Profile -> picker transition is still settling can pop
     * straight back to the *same* profile view, which is never re-created and so never
     * re-reads the account. That was seen once this suite ran right after the capture
     * pass: the account held `myata-11` and the card still said "no avatar". The timing
     * cannot be forced reliably from a test, so this drives the contract that fixes it:
     * with the view left exactly as it is, the handed-back key updates the card, and a
     * "verify again" routes an install that is no longer an account to the guest screen.
     */
    @Test
    fun k_the_card_applies_the_pickers_result_without_being_recreated() {
        withMainActivity {
            openAccountCard()
            var viewBefore: View? = null
            on { activity ->
                viewBefore = activity.findViewById(R.id.profile_account_card)
                assertEquals(
                    "Ваш аватар: аватар не выбран",
                    activity.findViewById<View>(R.id.profile_account_avatar).contentDescription,
                )
                activity.navController().currentBackStackEntry!!.savedStateHandle[
                    com.example.musicplayerapp.fragments.ProfileAvatarFragment.RESULT_SAVED_AVATAR,
                ] = "myata-07"
            }
            await("the card to show myata-07") { activity ->
                activity.findViewById<View>(R.id.profile_account_avatar)?.contentDescription ==
                    "Ваш аватар: Аватар 7 из 24"
            }
            on { activity ->
                assertTrue(
                    "the same view, not a re-created one",
                    viewBefore === activity.findViewById<View>(R.id.profile_account_card),
                )
            }

            // Verify again, on an install whose session has gone: the guest screen.
            auth.session = null
            on { activity ->
                activity.navController().currentBackStackEntry!!.savedStateHandle[
                    com.example.musicplayerapp.fragments.ProfileAvatarFragment.RESULT_VERIFY_AGAIN,
                ] = true
            }
            await("the guest profile") { it.currentDestinationIdOrNull() == R.id.profile }
        }
    }

    // ==================== another device changed it ====================

    /**
     * Device B's Profile, opened online, shows the avatar device A saved - without a
     * sign-out or a new sign-in - and the refreshed value reaches HOME on the way back.
     *
     * HOME and Settings refresh first with nothing new on the "server"; the server's avatar
     * changes only after that, so it is the Profile card's own refresh that is proven.
     */
    @Test
    fun l_profile_opened_online_refreshes_to_another_devices_avatar() {
        auth.accountAvatar = "myata-02"
        withMainActivity {
            await("HOME's refresh") { auth.refreshCalls >= 1 }
            openSettingsAndSettle()
            await("Settings' refresh") { auth.refreshCalls >= 2 }
            val before = auth.refreshCalls
            auth.serverAvatar = "myata-11"
            on { it.findViewById<View>(R.id.settings_row_profile).performClick() }
            await("the card to show the other device's avatar") { activity ->
                activity.currentDestinationIdOrNull() == R.id.profile_authenticated &&
                    activity.findViewById<View>(R.id.profile_account_avatar)?.contentDescription ==
                    "Ваш аватар: Аватар 11 из 24"
            }
            assertTrue("the Profile card refreshed", auth.refreshCalls > before)

            back()
            await("Settings") { it.currentDestinationIdOrNull() == R.id.settings }
            back()
            await("HOME with myata-11") { activity ->
                activity.currentDestinationIdOrNull() == R.id.home &&
                    activity.findViewById<View>(R.id.profile_entry)?.tag == "myata-11"
            }
        }
        assertEquals(com.example.musicplayerapp.data.supabase.IdentityState.Registered(account), IdentityStore.state(context))
        assertTrue("nobody signed in again", auth.signIns.isEmpty())
        assertEquals(0, auth.localSignOuts)
        assertTrue("a refresh is a read, not a write", auth.avatarUpdates.isEmpty())
    }

    /** Offline device B: the refresh fails, the card keeps the cached avatar, nobody is signed out. */
    @Test
    fun m_offline_profile_keeps_the_cached_avatar_and_stays_signed_in() {
        auth.accountAvatar = "myata-02"
        auth.serverAvatar = "myata-11"
        auth.refreshFailure = AuthFailure.NetworkFailure("offline")
        withMainActivity {
            openAccountCard()
            await("the card's refresh attempt") { auth.refreshCalls >= 3 }
            Thread.sleep(500)
            on { activity ->
                assertEquals(R.id.profile_authenticated, activity.currentDestinationIdOrNull())
                assertEquals(
                    "Ваш аватар: Аватар 2 из 24",
                    activity.findViewById<View>(R.id.profile_account_avatar).contentDescription,
                )
            }
        }
        assertEquals(com.example.musicplayerapp.data.supabase.IdentityState.Registered(account), IdentityStore.state(context))
        assertEquals("no sign-out on an offline refresh", 0, auth.localSignOuts)
    }

    // ==================== refresh vs. Save ordering ====================

    /**
     * A refresh that started when the card opened and is still in flight when a Save is
     * handed back must not be drawn over it. Deterministic: the refresh is held open.
     */
    @Test
    fun n_a_refresh_older_than_a_handed_back_save_is_not_drawn_over_it() {
        withMainActivity {
            await("HOME's refresh") { auth.refreshCalls >= 1 }
            openSettingsAndSettle()
            await("Settings' refresh") { auth.refreshCalls >= 2 }
            val gate = CompletableDeferred<Unit>()
            auth.refreshGate = gate
            on { it.findViewById<View>(R.id.settings_row_profile).performClick() }
            await("the card, with its refresh held open") { activity ->
                activity.currentDestinationIdOrNull() == R.id.profile_authenticated &&
                    activity.text(R.id.profile_account_name) == "Денис" && auth.refreshCalls >= 3
            }
            on { activity ->
                activity.navController().currentBackStackEntry!!.savedStateHandle[
                    com.example.musicplayerapp.fragments.ProfileAvatarFragment.RESULT_SAVED_AVATAR,
                ] = "myata-07"
            }
            await("the handed-back avatar") { activity ->
                activity.findViewById<View>(R.id.profile_account_avatar)?.contentDescription == "Ваш аватар: Аватар 7 из 24"
            }
            auth.refreshGate = null
            gate.complete(Unit) // the older refresh now answers with no avatar
            Thread.sleep(800)
            on { activity ->
                assertEquals(
                    "the older refresh must not revert the card",
                    "Ваш аватар: Аватар 7 из 24",
                    activity.findViewById<View>(R.id.profile_account_avatar).contentDescription,
                )
            }
        }
    }

    /**
     * An avatar save waits for a refresh already in flight, so that refresh can never store
     * the pre-save user after the save. The save then lands, and the card shows it.
     */
    @Test
    fun o_a_save_waits_for_an_in_flight_refresh_and_wins() {
        auth.accountAvatar = "myata-02"
        withMainActivity {
            openAccountCard()
            await("the card's refresh") { auth.refreshCalls >= 3 }
            openPicker()
            val gate = CompletableDeferred<Unit>()
            auth.refreshGate = gate
            // A refresh held open inside the lock, as a slow GET /user would be.
            val held = Thread {
                kotlinx.coroutines.runBlocking {
                    com.example.musicplayerapp.data.supabase.AccountRefresh.refresh(context)
                }
            }.also { it.start() }
            await("the held refresh") { auth.refreshCalls >= 4 }
            tapCell("myata-15")
            tap(R.id.avatar_save)
            Thread.sleep(800)
            assertTrue("the save must queue behind the refresh", auth.avatarUpdates.isEmpty())
            auth.refreshGate = null
            gate.complete(Unit)
            await("the profile with myata-15") { activity ->
                activity.currentDestinationIdOrNull() == R.id.profile_authenticated &&
                    activity.findViewById<View>(R.id.profile_account_avatar)?.contentDescription == "Ваш аватар: Аватар 15 из 24"
            }
            held.join(10_000)
        }
        assertEquals(listOf("myata-15"), auth.avatarUpdates)
        assertEquals("the session keeps the saved avatar", "myata-15", auth.accountAvatar)
    }

    // ==================== navigation ====================

    @Test
    fun i_a_double_tap_on_the_row_opens_one_picker_and_back_returns_to_the_profile() {
        withMainActivity {
            openAccountCard()
            on { activity ->
                val row = activity.findViewById<View>(R.id.profile_row_avatar)
                row.performClick()
                row.performClick()
            }
            await("the picker") { it.currentDestinationIdOrNull() == R.id.profile_avatar }
            on { it.findViewById<View>(R.id.profile_row_avatar)?.performClick() }
            sync()

            back()
            await("the profile") { it.currentDestinationIdOrNull() == R.id.profile_authenticated }
            // One more Back must leave the profile, not find a second picker under it.
            Thread.sleep(300)
            back()
            await("settings") { it.currentDestinationIdOrNull() == R.id.settings }
        }
    }

    // ==================== helpers ====================

    private fun openAccountCard() {
        openProfileAndSettle()
        await("the account card") { activity ->
            activity.currentDestinationIdOrNull() == R.id.profile_authenticated &&
                activity.text(R.id.profile_account_name) == "Денис"
        }
    }

    private fun openPicker() {
        tap(R.id.profile_row_avatar)
        await("the picker with the account loaded") { activity ->
            activity.currentDestinationIdOrNull() == R.id.profile_avatar &&
                activity.findViewById<View>(R.id.avatar_current)?.visibility == View.VISIBLE &&
                activity.cells().size == 24
        }
    }

    private fun tapCell(key: String) {
        on { activity ->
            activity.cells().firstOrNull { it.tag == key }?.performClick()
                ?: fail("no cell $key")
        }
        sync()
    }

    private fun back() {
        on { it.onBackPressedDispatcher.onBackPressed() }
        sync()
    }

    private fun tap(id: Int) {
        on { it.findViewById<View>(id).performClick() }
        sync()
    }

    private fun await(what: String, timeoutMs: Long = 15_000, check: (MainActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            runCatching { on { ok = check(it) } }
            if (ok) return
            Thread.sleep(25)
        }
        var state = "no activity"
        runCatching {
            on { a ->
                state = "destination=${a.currentDestinationIdOrNull()?.let { a.resources.getResourceEntryName(it) }} " +
                    "image=${a.visibility(R.id.profile_account_avatar_image)} " +
                    "desc=${a.findViewById<View>(R.id.profile_account_avatar)?.contentDescription}"
            }
        }
        fail("timed out after ${timeoutMs}ms waiting for $what; $state; updates=${auth.avatarUpdates} stored=${auth.accountAvatar}")
    }

    private fun on(block: (MainActivity) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { block(resumedMainActivity()) }
    }

    private fun sync() = InstrumentationRegistry.getInstrumentation().runOnMainSync { }

    private fun MainActivity.cells(): List<View> {
        val grid = findViewById<ViewGroup>(R.id.avatar_grid) ?: return emptyList()
        return (0 until grid.childCount).map { grid.getChildAt(it) }
    }

    private fun MainActivity.text(id: Int): String =
        findViewById<android.widget.TextView>(id)?.text?.toString().orEmpty()

    private fun MainActivity.navController() =
        (supportFragmentManager.findFragmentById(R.id.navHostFragment) as androidx.navigation.fragment.NavHostFragment)
            .navController

    /** True when all of [view] lies inside the visible window, not just some of it. */
    private fun MainActivity.wholeOnScreen(view: View): Boolean {
        val rect = android.graphics.Rect()
        return view.isShown && view.getGlobalVisibleRect(rect) &&
            rect.width() == view.width && rect.height() == view.height
    }

    private fun MainActivity.visibility(id: Int): Int = findViewById<View>(id)?.visibility ?: -1
}
