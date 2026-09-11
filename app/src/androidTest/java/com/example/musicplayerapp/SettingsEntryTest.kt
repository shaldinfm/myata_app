package com.example.musicplayerapp

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How Settings is reached, and the control the frozen band actually draws.
 *
 * ## The decision this file holds
 *
 * The frozen `Header - TopAppBar` (2393:1665 / 2444:10389) draws **one** 40x40 on
 * the trailing edge, and decoding its glyph settles what it is: a circle r4 at
 * (8,4) over a shoulders shape - the person. There is no gear anywhere in the
 * file, and no prototype link to say where the person leads.
 *
 * The owner's G4a decision: **the one drawn control opens `settings`**, and the
 * profile is reached from inside it.
 *
 * That is not a demotion of the profile, it is where the frozen file puts it.
 * `settings` 2517:2758 opens on `Row / Профиль` under a `Section / Аккаунт`
 * heading, with a chevron and a live value; neither `profile-guest` 2517:2644 nor
 * `profile-authenticated` 2517:2671 carries a Settings row. Settings is the
 * parent. So the tree the design draws is HOME > settings > profile, and
 * `the_profile_is_still_reachable_through_settings` walks exactly that.
 *
 * Three earlier attempts are ruled out here as much as the chosen one is
 * asserted:
 *
 *  - **G1** retargeted the profile control to Settings, and this file then held
 *    that it must not. G4a reverses that on the evidence above.
 *  - **G1b** added a *second* 40x40 gear beside it, in the 133dp the band leaves
 *    empty. `home_carries_one_forty_dp_control_on_the_trailing_edge` now holds
 *    that the band is back to one control and the space is empty again.
 *  - **G1a** hung `Настройки` on the PLAYER and COLLECTION overflows. Those menus
 *    are for their own screens' actions - `Menu / Плеер` is Найти трек, Таймер
 *    сна, Сообщить о проблеме, История эфира; `Menu / Коллекция` is the two
 *    exports - and neither frozen menu has a Настройки row.
 *    `the_collection_overflow_is_still_only_its_own_actions` holds that, and
 *    `PlayerLayoutTest` holds that the player's slot is reserved rather than
 *    drawn.
 */
@RunWith(AndroidJUnit4::class)
class SettingsEntryTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    /** 40dp, as Figma draws the one control, in this device's pixels. */
    private val fortyPx: Float get() = 40 * context.resources.displayMetrics.density

    @Before
    fun freshInstall() {
        if (Build.VERSION.SDK_INT >= 33) {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
                ),
            ).use { it.readBytes() }
        }
        IdentityStore.clearForTest(context)
    }

    @After
    fun tidy() = IdentityStore.clearForTest(context)

    // ==================== the control ====================

    /**
     * The band is back to the frozen one control, and it is the drawn one.
     *
     * `childCount` is the assertion that carries the weight. The gear G1b added is
     * no longer declared by any layout, so `R.id.settings_entry` does not exist
     * and a test that looked it up would not compile - which is a stronger
     * guarantee than any runtime absence check, but says nothing about a *second*
     * control arriving under some other id. Counting the header's children does.
     */
    @Test
    fun home_carries_one_forty_dp_control_on_the_trailing_edge() {
        withActivity {
            awaitHome()
            onMain { activity ->
                val header = activity.findViewById<android.view.ViewGroup>(R.id.home_header)
                val profile = activity.findViewById<View>(R.id.profile_entry)
                val greeting = activity.findViewById<View>(R.id.home_greeting)
                val d = context.resources.displayMetrics.density

                assertNotNull("HOME must carry the profile control", profile)
                assertEquals(View.VISIBLE, profile.visibility)
                assertEquals(
                    "the frozen band holds the greeting and one control, nothing else",
                    2,
                    header.childCount,
                )
                assertTrue(
                    "the control is ${profile.width}x${profile.height}, expected ~${fortyPx.toInt()}",
                    abs(profile.width - fortyPx) <= 2 && abs(profile.height - fortyPx) <= 2,
                )

                val trailing = header.width - (profile.left + profile.width)
                assertTrue(
                    "the control lost its frozen trailing anchor: gap ${trailing}px",
                    abs(trailing - 16 * d) <= 2,
                )
                assertTrue(
                    "the greeting lost its frozen leading anchor: left ${greeting.left}px",
                    abs(greeting.left - 16 * d) <= 2,
                )
                assertTrue(
                    "the header band changed height: ${header.height}px",
                    abs(header.height - 64 * d) <= 2,
                )
            }
        }
    }

    @Test
    fun the_bottom_bar_still_has_exactly_its_four_destinations() {
        withActivity {
            awaitHome()
            onMain { activity ->
                for (id in listOf(
                    R.id.nav_item_home, R.id.nav_item_player,
                    R.id.nav_item_favorites, R.id.nav_item_info,
                )) {
                    assertNotNull("a bottom-bar item went missing", activity.findViewById<View>(id))
                }
                val bar = activity.findViewById<android.view.ViewGroup>(R.id.bottomNavView)
                assertEquals("Settings is not a fifth destination", 4, bar.childCount)
            }
        }
    }

    // ============ what it opens, and what it did not take over ============

    @Test
    fun the_one_control_opens_settings_and_hides_the_bottom_bar() {
        withActivity {
            awaitHome()
            assertEquals(View.VISIBLE, barVisibility())

            tap(R.id.profile_entry)
            await("settings") { it.destination() == R.id.settings }

            assertNotNull(
                "the settings shell must be showing",
                onMain { it.findViewById<View>(R.id.settings_row_theme) },
            )
            assertEquals("settings has no bottom bar", View.GONE, barVisibility())
        }
    }

    /**
     * The profile is still reachable, by the route the frozen file draws.
     *
     * Two taps now rather than one, and that is the change: `settings` is the
     * parent frame and `Row / Профиль` is its first row. What must not change is
     * that the row is live and lands on a real profile - an inert row here would
     * make the whole re-route a regression rather than a correction.
     */
    @Test
    fun the_profile_is_still_reachable_through_settings() {
        withActivity {
            awaitHome()
            tap(R.id.profile_entry)
            await("settings") { it.destination() == R.id.settings }

            tap(R.id.settings_row_profile)
            await("a profile") {
                it.destination() == R.id.profile || it.destination() == R.id.profile_authenticated
            }
            assertNotNull(
                "profile-guest must be showing",
                onMain { it.findViewById<View>(R.id.profile_guest_card) },
            )
        }
    }

    @Test
    fun back_from_settings_returns_to_home_with_the_bar() {
        withActivity {
            awaitHome()
            tap(R.id.profile_entry)
            await("settings") { it.destination() == R.id.settings }

            tap(R.id.settings_back)
            await("HOME") { it.destination() == R.id.home }
            assertEquals("the bar must come back with HOME", View.VISIBLE, barVisibility())
        }
    }

    @Test
    fun settings_still_reaches_appearance_and_back_again() {
        withActivity {
            awaitHome()
            tap(R.id.profile_entry)
            await("settings") { it.destination() == R.id.settings }

            tap(R.id.settings_row_theme)
            await("appearance") { it.destination() == R.id.settings_appearance }
            assertNotNull(
                "the appearance screen must be showing",
                onMain { it.findViewById<View>(R.id.appearance_row_system) },
            )

            tap(R.id.appearance_back)
            await("settings") { it.destination() == R.id.settings }
            tap(R.id.settings_back)
            await("HOME") { it.destination() == R.id.home }
        }
    }

    /**
     * The profile row inside Settings still routes, so nothing there is inert.
     *
     * And the stack stays bounded: Settings is entered from HOME, never from a
     * profile, so `HOME > settings > profile` is as deep as this goes and Back
     * walks straight back out of it.
     */
    @Test
    fun the_profile_row_inside_settings_still_routes() {
        withActivity {
            awaitHome()
            tap(R.id.profile_entry)
            await("settings") { it.destination() == R.id.settings }

            tap(R.id.settings_row_profile)
            await("a profile") {
                it.destination() == R.id.profile || it.destination() == R.id.profile_authenticated
            }

            tap(R.id.profile_back)
            await("settings") { it.destination() == R.id.settings }
            tap(R.id.settings_back)
            await("HOME") { it.destination() == R.id.home }
        }
    }

    // ============ the contextual menus keep their own meaning ============

    /**
     * The COLLECTION overflow is still only the two frozen export actions.
     *
     * G1a briefly appended `Настройки` to it. `Menu / Коллекция` has exactly two
     * rows, so this inflates the same resource the fragment inflates and counts.
     * The second half asserts the PLAYER overflow menu G1a added is gone
     * entirely - the player's slot is reserved again, which `PlayerLayoutTest`
     * measures.
     */
    @Test
    fun the_collection_overflow_is_still_only_its_own_actions() {
        withActivity {
            awaitHome()
            // G4a replaced the platform PopupMenu with the frozen `Menu / Коллекция`
            // card, so the rows are the clickable children of its layout now.
            val ids = onMain { activity ->
                val root = activity.layoutInflater
                    .inflate(R.layout.menu_collection_overflow, null) as android.view.ViewGroup
                (0 until root.childCount).map { root.getChildAt(it) }.filter { it.isClickable }.map { it.id }
            }
            assertEquals("the collection overflow must have exactly two actions", 2, ids.size)
            assertTrue(ids.contains(R.id.collection_overflow_export_txt))
            assertTrue(ids.contains(R.id.collection_overflow_export_csv))
        }

        // A positive control first. `getIdentifier` answers 0 for *everything*
        // when the package it is handed is not the one the resources were built
        // under - an applicationId suffix is enough to do it - and an absence
        // check that cannot tell "gone" from "asked wrongly" asserts nothing. So
        // resolve a menu that certainly exists through the identical call, and
        // fail loudly if even that comes back 0.
        val known = context.resources.getIdentifier("menu_collection_overflow", "layout", context.packageName)
        assertTrue(
            "getIdentifier resolved nothing under ${context.packageName}, so the " +
                "absence check below would pass without meaning anything",
            known != 0,
        )
        assertEquals(
            "the player_overflow menu G1a added must be gone",
            0,
            context.resources.getIdentifier("player_overflow", "menu", context.packageName),
        )
    }

    // ==================== identity is untouched ====================

    @Test
    fun opening_settings_mints_no_identity() {
        assertEquals(IdentityState.None, IdentityStore.state(context))

        withActivity {
            awaitHome()
            tap(R.id.profile_entry)
            await("settings") { it.destination() == R.id.settings }
            tap(R.id.settings_back)
            await("HOME") { it.destination() == R.id.home }
        }

        assertEquals(
            "opening settings minted an identity",
            IdentityState.None,
            IdentityStore.state(context),
        )
    }

    // ==================== helpers ====================

    /**
     * Launches, runs, and tears down the way this repository's other suites do.
     *
     * **Not `use {}`.** `ActivityScenario.close()` reports a teardown timeout by
     * throwing on the software-rendered API 24 image, long after the activity is
     * finished - `use {}` turns that into a failure outside the test body and
     * leaves the activity RESUMED for whatever runs next.
     */
    private fun withActivity(body: () -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            body()
        } finally {
            try {
                scenario.close()
            } catch (e: Throwable) {
                android.util.Log.w("ENTRYQA", "activity close timed out", e)
            }
        }
    }

    private fun awaitHome() = await("HOME") { it.destination() == R.id.home }

    private fun barVisibility(): Int =
        onMain { it.findViewById<View>(R.id.bottomNavView).visibility }

    private fun tap(id: Int) {
        onMain { it.findViewById<View>(id).performClick() }
        instrumentation.runOnMainSync { }
    }

    private fun MainActivity.destination(): Int? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment)
            as? androidx.navigation.fragment.NavHostFragment
        return host?.navController?.currentDestination?.id
    }

    private fun await(what: String, timeoutMs: Long = 20_000, check: (MainActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            runCatching { instrumentation.runOnMainSync { ok = check(resumedMainActivity()) } }
            if (ok) return
            Thread.sleep(25)
        }
        error("timed out after ${timeoutMs}ms waiting for $what")
    }

    /** See `AuthTestDoubles.resumedMainActivity`: `onActivity` waits for an idle looper. */
    private fun <T> onMain(block: (MainActivity) -> T): T {
        var out: T? = null
        instrumentation.runOnMainSync { out = block(resumedMainActivity()) }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }
}
