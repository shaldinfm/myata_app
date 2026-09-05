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
 * How Settings is reached, and what did not move to let it be.
 *
 * ## The decision this file holds
 *
 * The frozen design draws no path to `settings` from anywhere - no menu row, no
 * button, no tab, and no prototype link in the whole file. So the entry is an
 * owner-delegated product decision, and it is: **a second 40x40 control on the
 * HOME header, beside the profile control**.
 *
 * Two earlier attempts are ruled out here as much as the chosen one is asserted:
 *
 *  - **G1** retargeted the profile control itself to Settings. The profile must
 *    stay one tap from HOME, so `profile_control_still_opens_the_profile` holds
 *    that.
 *  - **G1a** hung `Настройки` on the PLAYER and COLLECTION overflows. Those menus
 *    are for their own screens' actions - `Menu / Плеер` is Найти трек, Таймер
 *    сна, Сообщить о проблеме, История эфира; `Menu / Коллекция` is the two
 *    exports - and neither frozen menu has a Настройки row.
 *    `the_collection_overflow_is_still_only_its_own_actions` holds that, and
 *    `PlayerLayoutTest` holds that the player's slot is reserved rather than
 *    drawn.
 *
 * ## What the addition costs, asserted
 *
 * The frozen band leaves 133dp empty between the greeting and the profile
 * control, and the new control sits in it. So the claim is not "it fits" but
 * "nothing frozen moved", and that is what
 * `the_new_control_moves_nothing_that_was_already_there` measures.
 */
@RunWith(AndroidJUnit4::class)
class SettingsEntryTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    /** 40dp, as Figma draws both controls, in this device's pixels. */
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

    @Test
    fun home_carries_two_forty_dp_controls_on_the_trailing_edge() {
        withActivity {
            awaitHome()
            onMain { activity ->
                val settings = activity.findViewById<View>(R.id.settings_entry)
                val profile = activity.findViewById<View>(R.id.profile_entry)
                assertNotNull("HOME must carry the Settings control", settings)
                assertNotNull("HOME must still carry the profile control", profile)
                assertEquals(View.VISIBLE, settings.visibility)
                assertEquals(View.VISIBLE, profile.visibility)

                for ((what, v) in listOf("settings" to settings, "profile" to profile)) {
                    assertTrue(
                        "$what control is ${v.width}x${v.height}, expected ~${fortyPx.toInt()}",
                        abs(v.width - fortyPx) <= 2 && abs(v.height - fortyPx) <= 2,
                    )
                }

                // Settings sits before the profile control, not after it: the
                // profile keeps the frozen trailing anchor.
                assertTrue(
                    "the Settings control must sit before the profile control",
                    settings.left < profile.left,
                )
            }
        }
    }

    /**
     * The addition lands in space the frozen band already left empty.
     *
     * The profile control's trailing anchor is the frozen one - its right edge
     * 16dp from the header's - and the greeting still starts at the leading 16.
     * Those two are what would have had to move if the new control needed room,
     * so they are what is measured.
     */
    @Test
    fun the_new_control_moves_nothing_that_was_already_there() {
        withActivity {
            awaitHome()
            onMain { activity ->
                val header = activity.findViewById<View>(R.id.home_header)
                val profile = activity.findViewById<View>(R.id.profile_entry)
                val settings = activity.findViewById<View>(R.id.settings_entry)
                val greeting = activity.findViewById<View>(R.id.home_greeting)
                val d = context.resources.displayMetrics.density

                val trailing = header.width - (profile.left + profile.width)
                assertTrue(
                    "the profile control lost its frozen trailing anchor: gap ${trailing}px",
                    abs(trailing - 16 * d) <= 2,
                )
                assertTrue(
                    "the greeting lost its frozen leading anchor: left ${greeting.left}px",
                    abs(greeting.left - 16 * d) <= 2,
                )
                val gap = profile.left - (settings.left + settings.width)
                assertTrue(
                    "gap between the controls is ${gap}px, expected ~${(8 * d).toInt()}",
                    abs(gap - 8 * d) <= 2,
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
    fun the_settings_control_opens_settings_and_hides_the_bottom_bar() {
        withActivity {
            awaitHome()
            assertEquals(View.VISIBLE, barVisibility())

            tap(R.id.settings_entry)
            await("settings") { it.destination() == R.id.settings }

            assertNotNull(
                "the settings shell must be showing",
                onMain { it.findViewById<View>(R.id.settings_row_theme) },
            )
            assertEquals("settings has no bottom bar", View.GONE, barVisibility())
        }
    }

    /** The profile is still one tap from HOME - the whole point of the correction. */
    @Test
    fun profile_control_still_opens_the_profile() {
        withActivity {
            awaitHome()
            tap(R.id.profile_entry)
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
            tap(R.id.settings_entry)
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
            tap(R.id.settings_entry)
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
            tap(R.id.settings_entry)
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
            val ids = onMain { activity ->
                val menu = androidx.appcompat.widget.PopupMenu(activity, View(activity)).menu
                activity.menuInflater.inflate(R.menu.collection_overflow, menu)
                (0 until menu.size()).map { menu.getItem(it).itemId }
            }
            assertEquals("the collection overflow must have exactly two actions", 2, ids.size)
            assertTrue(ids.contains(R.id.collection_action_export_txt))
            assertTrue(ids.contains(R.id.collection_action_export_csv))
        }

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
            tap(R.id.settings_entry)
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
