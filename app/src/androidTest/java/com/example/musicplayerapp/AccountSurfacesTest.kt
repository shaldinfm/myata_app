package com.example.musicplayerapp

import android.content.Context
import com.example.musicplayerapp.fragments.NEUTRAL_TAG
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.supabase.AuthFailure
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * G6a account surfaces outside the picker: the Settings row names the account, HOME's
 * profile control shows its avatar, and a change made on another device reaches this one
 * when an account surface opens - online - without anybody signing out.
 *
 * Fake auth throughout. "Another device" is [FakeEmailAuthApi.serverAvatar]: the server
 * holds a newer avatar than this device's stored session until a refresh copies it across.
 * Nothing here reaches Supabase.
 */
@RunWith(AndroidJUnit4::class)
class AccountSurfacesTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var auth: FakeEmailAuthApi
    private val account = "22222222-2222-4222-8222-222222222222"

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
        auth = FakeEmailAuthApi().also {
            it.uid = account
            it.accountName = "Денис"
            it.accountEmail = "name@example.com"
        }
        EmailAuthBackend.overrideForInstrumentation { auth }
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

    private fun signedIn(avatar: String? = null, name: String? = "Денис") {
        IdentityStore.markRegistered(context, account)
        auth.session = account
        auth.accountName = name
        auth.accountAvatar = avatar
    }

    // ==================== Settings: the name, not the address ====================

    @Test
    fun a_the_settings_row_names_the_account_not_its_address() {
        signedIn()
        withMainActivity {
            openSettingsAndSettle()
            await("the Settings account row") { it.text(R.id.settings_row_profile_value) == "Денис" }
            on { activity ->
                assertNotEquals("name@example.com", activity.text(R.id.settings_row_profile_value))
            }
        }
    }

    @Test
    fun b_an_account_with_no_name_gets_the_account_models_fallback() {
        signedIn(name = "   ")
        withMainActivity {
            openSettingsAndSettle()
            await("Пользователь") {
                it.text(R.id.settings_row_profile_value) == context.getString(R.string.profile_account_name_fallback)
            }
        }
    }

    @Test
    fun c_a_guest_row_still_says_not_signed_in() {
        withMainActivity {
            openSettingsAndSettle()
            await("Не вошли") {
                it.text(R.id.settings_row_profile_value) == context.getString(R.string.settings_profile_signed_out)
            }
        }
    }

    // ==================== HOME's profile control ====================

    @Test
    fun d_a_guest_keeps_the_generic_control() {
        withMainActivity {
            awaitHome()
            Thread.sleep(500)
            on { assertGeneric(it) }
        }
    }

    @Test
    fun e_signed_in_without_an_avatar_keeps_the_generic_control() {
        signedIn(avatar = null)
        withMainActivity {
            await("the named greeting") { it.text(R.id.home_greeting).contains("Денис") }
            on { assertGeneric(it) }
        }
    }

    @Test
    fun f_an_unknown_avatar_id_keeps_the_generic_control() {
        signedIn(avatar = "myata-99")
        withMainActivity {
            await("the named greeting") { it.text(R.id.home_greeting).contains("Денис") }
            Thread.sleep(300)
            on { assertGeneric(it) }
        }
    }

    @Test
    fun g_a_valid_avatar_shows_on_home_and_a_relaunch_restores_it() {
        signedIn(avatar = "myata-07")
        withMainActivity {
            awaitEntry("myata-07")
            on { activity ->
                val entry = activity.findViewById<ImageView>(R.id.profile_entry)
                assertEquals(
                    "the entry draws the avatar's own artwork",
                    activity.getDrawable(R.drawable.avatar_myata_07)!!.constantState,
                    entry.drawable.constantState,
                )
                assertNull("no disc behind the artwork", entry.background)
                assertEquals(context.resources.getDimensionPixelSize(R.dimen.profile_entry_size), entry.width)
                assertTrue("still the Settings entry", entry.hasOnClickListeners())
            }
        }
        withMainActivity { awaitEntry("myata-07") }
    }

    @Test
    fun h_saving_a_new_avatar_updates_home_on_return() {
        signedIn(avatar = "myata-07")
        withMainActivity {
            awaitEntry("myata-07")
            openProfileAndSettle()
            await("the account card") { it.currentDestinationIdOrNull() == R.id.profile_authenticated }
            tap(R.id.profile_row_avatar)
            await("the picker") { it.findViewById<android.view.ViewGroup>(R.id.avatar_grid)?.childCount == 24 && it.findViewById<View>(R.id.avatar_save)?.isEnabled == true }
            on { activity ->
                val grid = activity.findViewById<android.view.ViewGroup>(R.id.avatar_grid)
                (0 until grid.childCount).map { grid.getChildAt(it) }.first { it.tag == "myata-09" }.performClick()
            }
            tap(R.id.avatar_save)
            await("the profile after Save") { it.currentDestinationIdOrNull() == R.id.profile_authenticated }
            back()
            await("Settings") { it.currentDestinationIdOrNull() == R.id.settings }
            back()
            awaitEntry("myata-09")
        }
        assertEquals(listOf("myata-09"), auth.avatarUpdates)
    }

    // ==================== another device changed it ====================

    @Test
    fun i_home_opened_online_picks_up_another_devices_avatar_without_signing_in_again() {
        signedIn(avatar = "myata-02")
        auth.serverAvatar = "myata-11"
        withMainActivity { awaitEntry("myata-11") }
        assertTrue(auth.refreshCalls >= 1)
        assertEquals(IdentityState.Registered(account), IdentityStore.state(context))
        assertTrue("nobody signed in again", auth.signIns.isEmpty())
    }

    @Test
    fun j_offline_home_keeps_the_cached_avatar_and_stays_signed_in() {
        signedIn(avatar = "myata-02")
        auth.serverAvatar = "myata-11"
        auth.refreshFailure = AuthFailure.NetworkFailure("offline")
        withMainActivity {
            awaitEntry("myata-02")
            await("the refresh attempt") { auth.refreshCalls >= 1 }
            Thread.sleep(500)
            on { activity -> assertEquals("myata-02", activity.findViewById<View>(R.id.profile_entry).tag) }
        }
        assertEquals(IdentityState.Registered(account), IdentityStore.state(context))
        assertEquals("no sign-out on an offline refresh", 0, auth.localSignOuts)
    }

    // ==================== cold start: the stored session restores after HOME ====================
    //
    // [FakeEmailAuthApi.restoreGate] is the Auth plugin's cold-start window: the session is
    // on disk, and nothing reads it until the restore completes. Each test holds that window
    // open past HOME's first frame, so none of them depends on timing.

    @Test
    fun k_cold_start_offline_with_a_cached_session_shows_its_name_and_avatar() {
        signedIn(avatar = "myata-07")
        auth.refreshFailure = AuthFailure.NetworkFailure("offline")
        val restore = kotlinx.coroutines.CompletableDeferred<Unit>().also { auth.restoreGate = it }
        withMainActivity {
            awaitHome()
            awaitHeaderDecision()
            restore.complete(Unit)
            awaitEntry("myata-07")
            await("the named greeting") { it.text(R.id.home_greeting).contains("Денис") }
            await("the offline refresh attempt") { auth.refreshCalls >= 1 }
            Thread.sleep(500)
            on { activity ->
                assertEquals("myata-07", activity.findViewById<View>(R.id.profile_entry).tag)
                assertTrue(activity.text(R.id.home_greeting).contains("Денис"))
            }
        }
        assertEquals(IdentityState.Registered(account), IdentityStore.state(context))
        assertEquals("no sign-out offline", 0, auth.localSignOuts)
        assertTrue("nobody signed in again", auth.signIns.isEmpty())
    }

    @Test
    fun l_cold_start_with_no_session_is_the_guest_header_and_never_waits() {
        // A restore that never completes: a guest must not be waiting on it.
        auth.restoreGate = kotlinx.coroutines.CompletableDeferred()
        withMainActivity {
            awaitHome()
            Thread.sleep(800)
            on { activity ->
                assertEquals(context.getString(R.string.home_greeting), activity.text(R.id.home_greeting))
                assertGeneric(activity)
            }
        }
        assertEquals("a guest's HOME does not wait for a session", 0, auth.restoreWaits)
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun l2_registered_on_disk_but_no_stored_session_is_the_guest_header_and_no_sign_out() {
        IdentityStore.markRegistered(context, account)
        auth.session = null
        val restore = kotlinx.coroutines.CompletableDeferred<Unit>().also { auth.restoreGate = it }
        withMainActivity {
            awaitHome()
            awaitHeaderDecision()
            restore.complete(Unit)
            Thread.sleep(800)
            on { activity ->
                assertEquals(context.getString(R.string.home_greeting), activity.text(R.id.home_greeting))
                assertGeneric(activity)
            }
        }
        assertEquals(IdentityState.Registered(account), IdentityStore.state(context))
        assertEquals(0, auth.localSignOuts)
    }

    @Test
    fun m_a_restore_that_completes_after_home_was_drawn_updates_home_in_place() {
        signedIn(avatar = "myata-07")
        val restore = kotlinx.coroutines.CompletableDeferred<Unit>().also { auth.restoreGate = it }
        withMainActivity {
            awaitHome()
            awaitHeaderDecision()
            Thread.sleep(500)
            on { activity ->
                assertTrue(
                    "no name before the session is restored",
                    !activity.text(R.id.home_greeting).contains("Денис"),
                )
                assertEquals("a neutral control before the session is restored", NEUTRAL_TAG, activity.findViewById<View>(R.id.profile_entry).tag)
                assertEquals("no greeting before the session is restored", View.INVISIBLE, activity.findViewById<View>(R.id.home_greeting).visibility)
            }
            restore.complete(Unit)
            // No navigation: HOME itself must pick the restored session up.
            awaitEntry("myata-07")
            await("the named greeting") { it.text(R.id.home_greeting) == "Привет, Денис!" }
            on { assertEquals(R.id.home, it.currentDestinationIdOrNull()) }
        }
    }

    @Test
    fun n_a_refresh_that_fails_after_the_restore_keeps_the_cached_name_and_avatar() {
        signedIn(avatar = "myata-07")
        auth.refreshFailure = AuthFailure.NetworkFailure("offline")
        val refresh = kotlinx.coroutines.CompletableDeferred<Unit>().also { auth.refreshGate = it }
        val restore = kotlinx.coroutines.CompletableDeferred<Unit>().also { auth.restoreGate = it }
        withMainActivity {
            awaitHome()
            awaitHeaderDecision()
            restore.complete(Unit)
            awaitEntry("myata-07")
            await("the refresh, held open") { auth.refreshCalls >= 1 }
            auth.refreshGate = null
            refresh.complete(Unit) // ...and it fails
            Thread.sleep(800)
            on { activity ->
                assertEquals("myata-07", activity.findViewById<View>(R.id.profile_entry).tag)
                assertEquals("Привет, Денис!", activity.text(R.id.home_greeting))
            }
        }
        assertEquals(IdentityState.Registered(account), IdentityStore.state(context))
        assertEquals(0, auth.localSignOuts)
    }

    @Test
    fun o_an_unknown_avatar_after_the_restore_is_the_generic_icon_with_the_name() {
        signedIn(avatar = "myata-99")
        val restore = kotlinx.coroutines.CompletableDeferred<Unit>().also { auth.restoreGate = it }
        withMainActivity {
            awaitHome()
            awaitHeaderDecision()
            restore.complete(Unit)
            await("the named greeting") { it.text(R.id.home_greeting) == "Привет, Денис!" }
            Thread.sleep(300)
            on { assertGeneric(it) }
        }
    }

    // ==================== no guest frame for a known account ====================
    //
    // [FakeEmailAuthApi.accountGate] holds every session read open, so whatever a recreated
    // screen shows is what it drew before any read came back - the frame that used to be the
    // layout's guest default.

    @Test
    fun p_back_to_home_shows_the_known_account_before_any_read_returns() {
        signedIn(avatar = "myata-07")
        withMainActivity {
            awaitEntry("myata-07")
            openSettingsAndSettle()
            await("Settings' name") { it.text(R.id.settings_row_profile_value) == "Денис" }
            val reads = auth.currentAccountCalls
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>().also { auth.accountGate = it }
            back()
            await("HOME recreated") { it.currentDestinationIdOrNull() == R.id.home && it.findViewById<View>(R.id.home_greeting) != null }
            on { activity ->
                assertEquals("Привет, Денис!", activity.text(R.id.home_greeting))
                assertEquals("myata-07", activity.findViewById<View>(R.id.profile_entry).tag)
            }
            await("HOME's read, held open") { auth.currentAccountCalls > reads }
            on { activity -> assertEquals("Привет, Денис!", activity.text(R.id.home_greeting)) }
            auth.accountGate = null
            gate.complete(Unit)
            Thread.sleep(500)
            on { activity ->
                assertEquals("Привет, Денис!", activity.text(R.id.home_greeting))
                assertEquals("myata-07", activity.findViewById<View>(R.id.profile_entry).tag)
            }
        }
    }

    @Test
    fun q_settings_opens_with_the_known_name_before_any_read_returns() {
        signedIn(avatar = "myata-07")
        withMainActivity {
            awaitEntry("myata-07")
            auth.accountGate = kotlinx.coroutines.CompletableDeferred()
            on { it.findViewById<View>(R.id.profile_entry).performClick() }
            await("Settings") { it.currentDestinationIdOrNull() == R.id.settings && it.findViewById<View>(R.id.settings_row_profile_value) != null }
            on { activity ->
                assertEquals("the known name, not the layout's «Не вошли»", "Денис", activity.text(R.id.settings_row_profile_value))
            }
            Thread.sleep(500)
            on { activity -> assertEquals("Денис", activity.text(R.id.settings_row_profile_value)) }
        }
    }

    @Test
    fun r_the_card_opens_showing_the_known_account_and_a_refresh_does_not_blank_it() {
        signedIn(avatar = "myata-07")
        withMainActivity {
            awaitEntry("myata-07")
            openSettingsAndSettle()
            await("Settings' name") { it.text(R.id.settings_row_profile_value) == "Денис" }
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>().also { auth.accountGate = it }
            val refresh = kotlinx.coroutines.CompletableDeferred<Unit>().also { auth.refreshGate = it }
            auth.refreshFailure = AuthFailure.NetworkFailure("offline")
            on { it.findViewById<View>(R.id.settings_row_profile).performClick() }
            await("the card") { it.currentDestinationIdOrNull() == R.id.profile_authenticated && it.findViewById<View>(R.id.profile_account_card) != null }
            on { activity -> assertCard(activity) }
            // The session read comes back; the refresh is then held open, and fails.
            auth.accountGate = null
            gate.complete(Unit)
            val refreshes = auth.refreshCalls
            await("the card's refresh, held open") { auth.refreshCalls > refreshes }
            on { activity -> assertCard(activity) }
            auth.refreshGate = null
            refresh.complete(Unit)
            Thread.sleep(500)
            on { activity -> assertCard(activity) }
        }
        assertEquals(0, auth.localSignOuts)
    }

    private fun assertCard(activity: MainActivity) {
        assertEquals("card visible", View.VISIBLE, activity.findViewById<View>(R.id.profile_account_card).visibility)
        assertEquals("Денис", activity.text(R.id.profile_account_name))
        assertEquals("Ваш аватар: Аватар 7 из 24", activity.findViewById<View>(R.id.profile_account_avatar).contentDescription)
    }

    // ==================== helpers ====================

    private fun assertGeneric(activity: MainActivity) {
        val entry = activity.findViewById<ImageView>(R.id.profile_entry)
        assertNotNull(entry)
        assertNull("no avatar key on the generic control", entry.tag)
        assertNotNull("the generic control keeps its disc", entry.background)
        // Vector drawables do not share state between inflations, so identity cannot be
        // compared; the glyph is a vector and every avatar is a bitmap.
        assertTrue(
            "the generic glyph, not avatar artwork",
            entry.drawable is android.graphics.drawable.VectorDrawable &&
                entry.drawable !is android.graphics.drawable.BitmapDrawable,
        )
    }

    /**
     * Until HOME has either started waiting for the restore or already read the account -
     * the moment its first header is decided. Releasing the restore after this is what makes
     * a HOME that read too early fail on what it draws, not on a counter.
     */
    private fun awaitHeaderDecision() =
        await("HOME to decide its header") { auth.restoreWaits + auth.currentAccountCalls >= 1 }

    private fun awaitHome() = await("HOME") { it.currentDestinationIdOrNull() == R.id.home }

    private fun awaitEntry(key: String) = await("HOME's control to show $key") { activity ->
        activity.currentDestinationIdOrNull() == R.id.home &&
            activity.findViewById<View>(R.id.profile_entry)?.tag == key
    }

    private fun tap(id: Int) {
        on { it.findViewById<View>(id).performClick() }
        sync()
    }

    private fun back() {
        on { it.onBackPressedDispatcher.onBackPressed() }
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
        fail("timed out after ${timeoutMs}ms waiting for $what; refreshCalls=${auth.refreshCalls} avatar=${auth.accountAvatar}")
    }

    private fun on(block: (MainActivity) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { block(resumedMainActivity()) }
    }

    private fun sync() = InstrumentationRegistry.getInstrumentation().runOnMainSync { }

    private fun MainActivity.text(id: Int): String = findViewById<TextView>(id)?.text?.toString().orEmpty()
}
