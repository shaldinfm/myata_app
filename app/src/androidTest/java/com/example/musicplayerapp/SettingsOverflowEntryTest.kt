package com.example.musicplayerapp

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How Settings is reached, and from where (G1a).
 *
 * ## What changed, and why this file exists
 *
 * G1 put the Settings entry on the 40x40 header control that HOME, ABOUT US and
 * the empty COLLECTION share, and moved the profile behind it. The owner's
 * clarification reverses that: the header control is the **profile** entry again,
 * exactly as it was before G1, and Settings is reached from the three-dot
 * overflow the frozen PLAYER and COLLECTION headers already draw.
 *
 * `ProfileEntryTest` holds the first half - that the header control opens a
 * profile. This holds the second: that the overflows reach Settings, that Back
 * returns to the screen that opened it, and that the bottom bar is right at every
 * step.
 *
 * ## The empty COLLECTION has no overflow, on purpose
 *
 * The frozen `COLLECTION pusto` frame carries the profile control where the
 * populated frame carries the overflow, and never both - so on an empty
 * collection there is no ellipsis and therefore no Settings entry there. That is
 * asserted below rather than worked around: making the overflow appear on the
 * empty frame would be redrawing a frozen screen. The PLAYER overflow is the one
 * that is always available, because the bottom bar always offers PLAYER.
 */
@RunWith(AndroidJUnit4::class)
class SettingsOverflowEntryTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private lateinit var db: AppDatabase

    @Before
    fun open() {
        if (Build.VERSION.SDK_INT >= 33) {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
                ),
            ).use { it.readBytes() }
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        AppDatabase.overrideForInstrumentation(db)
        IdentityStore.clearForTest(context)
    }

    @After
    fun close() {
        IdentityStore.clearForTest(context)
        AppDatabase.overrideForInstrumentation(null)
        db.close()
    }

    // ==================== PLAYER ====================

    /**
     * The frozen overflow box is now a control rather than reserved space.
     *
     * It was a `Space` until G1a - the box held open so the header label stayed
     * centred where the frame centres it, with nothing drawn in it because nothing
     * behind it existed. `PlayerLayoutTest` asserted it was *not* clickable for
     * exactly that reason; it has an action now, so both this and that assertion
     * changed together.
     */
    @Test
    fun the_player_header_carries_a_live_overflow_control() {
        withActivity {
            openPlayer()

            onMain { activity ->
                val action = activity.findViewById<View>(R.id.player_header_action)
                assertNotNull("PLAYER must carry the overflow control", action)
                assertEquals(View.VISIBLE, action.visibility)
                assertTrue("the overflow must be clickable now", action.hasOnClickListeners())

                // The frozen `Button:margin` is 32.02 wide at the trailing edge of
                // the 358 header row.
                val expected = 32 * context.resources.displayMetrics.density
                assertTrue(
                    "expected ~${expected.toInt()}px wide, was ${action.width}",
                    abs(action.width - expected) <= 2,
                )

                val header = activity.findViewById<View>(R.id.player_header)
                val rightGap = header.width - (action.left + action.width)
                assertTrue(
                    "the overflow must sit on the header's trailing edge, gap was $rightGap",
                    abs(rightGap) <= 2,
                )
            }
        }
    }

    @Test
    fun the_player_overflow_opens_settings_and_hides_the_bottom_bar() {
        assumePopupIsReadable()
        withActivity {
            openPlayer()
            assertEquals(View.VISIBLE, barVisibility())

            tap(R.id.player_header_action)
            clickPopupItem(R.string.settings_title)
            await("settings") { it.destination() == R.id.settings }

            onMain { activity ->
                assertNotNull(
                    "the settings shell must be showing",
                    activity.findViewById<View>(R.id.settings_row_theme),
                )
            }
            assertEquals("settings has no bottom bar", View.GONE, barVisibility())
        }
    }

    @Test
    fun back_from_settings_returns_to_the_player() {
        assumePopupIsReadable()
        withActivity {
            openPlayer()
            tap(R.id.player_header_action)
            clickPopupItem(R.string.settings_title)
            await("settings") { it.destination() == R.id.settings }

            tap(R.id.settings_back)
            await("the player") { it.destination() == R.id.player }
            assertEquals(
                "the bar must come back with the caller",
                View.VISIBLE,
                barVisibility(),
            )
        }
    }

    /** Settings still reaches Appearance from this entry, unchanged. */
    @Test
    fun settings_from_the_player_still_reaches_appearance() {
        assumePopupIsReadable()
        withActivity {
            openPlayer()
            tap(R.id.player_header_action)
            clickPopupItem(R.string.settings_title)
            await("settings") { it.destination() == R.id.settings }

            tap(R.id.settings_row_theme)
            await("appearance") { it.destination() == R.id.settings_appearance }
            assertNotNull(
                "the appearance screen must be showing",
                onMain { it.findViewById<View>(R.id.appearance_row_system) },
            )
        }
    }

    /**
     * Reaching Settings creates no identity.
     *
     * The guarantee `ProfileEntryTest` has held since G-A3, asserted against the
     * new entry as well: `SettingsFragment` reads the identity on every resume to
     * label its profile row, and reading must never mint.
     */
    @Test
    fun opening_settings_from_the_player_mints_no_identity() {
        assumePopupIsReadable()
        assertEquals(IdentityState.None, IdentityStore.state(context))

        withActivity {
            openPlayer()
            tap(R.id.player_header_action)
            clickPopupItem(R.string.settings_title)
            await("settings") { it.destination() == R.id.settings }
            tap(R.id.settings_back)
            await("the player") { it.destination() == R.id.player }
        }

        assertEquals(
            "opening settings minted an identity",
            IdentityState.None,
            IdentityStore.state(context),
        )
    }

    // ==================== COLLECTION ====================

    @Test
    fun the_collection_overflow_opens_settings_and_back_returns_to_it() {
        assumePopupIsReadable()
        seedOneLikedTrack()

        withActivity {
            openCollection()
            await("the populated collection") {
                it.findViewById<View>(R.id.collection_overflow).visibility == View.VISIBLE
            }
            assertEquals(View.VISIBLE, barVisibility())

            tap(R.id.collection_overflow)
            clickPopupItem(R.string.settings_title)
            await("settings") { it.destination() == R.id.settings }
            assertEquals(View.GONE, barVisibility())

            tap(R.id.settings_back)
            await("the collection") { it.destination() == R.id.favorites }
            assertEquals(
                "the bar must come back with the caller",
                View.VISIBLE,
                barVisibility(),
            )
        }
    }

    /**
     * The overflow keeps the two actions it already had.
     *
     * G1a adds a row to an existing menu; it must not have replaced one. Both
     * export labels have to still be in the popup alongside Настройки.
     */
    @Test
    fun the_collection_overflow_keeps_its_export_actions() {
        assumePopupIsReadable()
        seedOneLikedTrack()

        withActivity {
            openCollection()
            await("the populated collection") {
                it.findViewById<View>(R.id.collection_overflow).visibility == View.VISIBLE
            }

            tap(R.id.collection_overflow)
            for (label in listOf(
                R.string.collection_export_txt,
                R.string.collection_export_csv,
                R.string.settings_title,
            )) {
                assertPopupShows(label)
            }
            // Leave the menu without acting on it.
            instrumentation.runOnMainSync {
                resumedMainActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    /**
     * An empty COLLECTION has the profile control and no overflow.
     *
     * The documented consequence of putting Settings on the ellipsis: the frozen
     * empty frame does not draw one. Asserted so the trade-off is visible rather
     * than discovered.
     */
    @Test
    fun an_empty_collection_shows_the_profile_control_and_no_overflow() {
        withActivity {
            openCollection()
            await("the empty collection") {
                it.findViewById<View>(R.id.profile_entry).visibility == View.VISIBLE
            }

            onMain { activity ->
                assertEquals(
                    "the empty frame carries the profile control, never both",
                    View.GONE,
                    activity.findViewById<View>(R.id.collection_overflow).visibility,
                )
            }
        }
    }

    // ==================== helpers ====================

    /**
     * Skips a popup-driving case on the API 24 image, and says why.
     *
     * The menu **opens** there - `dumpsys window` shows the `PopupWindow` taking
     * focus, and a screenshot of that moment shows `Настройки` drawn on it - but its
     * contents never appear in the accessibility tree this harness reads, so the row
     * cannot be activated from a test. `findAccessibilityNodeInfosByText` returns
     * only the app window's own match; `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` does not
     * help.
     *
     * That is a limit of the emulator image, not of the app, and it is a limit on
     * *driving* the menu rather than on the menu working. So the cases that have to
     * click a row run on API 26+, and everything that can be checked without the
     * popup - the control exists, is the frozen size, is clickable, and the empty
     * COLLECTION has none - still runs everywhere.
     */
    private fun assumePopupIsReadable() {
        org.junit.Assume.assumeTrue(
            "the API 24 image does not expose PopupMenu contents to the " +
                "accessibility tree; the menu itself opens - see the KDoc",
            Build.VERSION.SDK_INT >= 26,
        )
    }

    /**
     * Launches, runs, and tears down the way this repository's other suites do.
     *
     * **Not `use {}`.** `ActivityScenario.close()` reports a teardown timeout by
     * throwing on the software-rendered API 24 image, long after the activity is
     * finished - `use {}` turns that into a failure outside the test body, and
     * leaves the activity in RESUMED for whatever runs next. A stale RESUMED
     * activity is exactly what made a popup look open with no row in it: the tap
     * landed on a dead screen's view.
     */
    private fun withActivity(body: () -> Unit) {
        awaitNoLingeringActivity()
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

    /**
     * Waits for the previous test's activity to actually be gone.
     *
     * The close above can report a timeout and return while the activity is still
     * on screen. The next test then launches a second one *behind* it, and every
     * popup it opens is behind it too - which is how this suite saw a menu that was
     * open on the live window and invisible to the accessibility tree, reported as
     * "1 view reads Настройки, none of them a menu row" (that one view being the
     * dying screen's own heading).
     *
     * Bounded, and not fatal: if something is still there after the wait the test
     * proceeds and fails on its own terms rather than here.
     */
    private fun awaitNoLingeringActivity(timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var clear = false
            instrumentation.runOnMainSync {
                clear = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .isEmpty()
            }
            if (clear) return
            Thread.sleep(50)
        }
        android.util.Log.w("ENTRYQA", "a previous MainActivity is still resumed")
    }

    private fun seedOneLikedTrack() = runBlocking {
        val key = TrackKey.of("Depeche Mode", "Enjoy the Silence")!!
        db.reactionDao().like(key, "Depeche Mode", "Enjoy the Silence", "myata", 1_000L, 1_000L)
    }

    private fun openPlayer() {
        tap(R.id.nav_item_player)
        await("the player") { it.destination() == R.id.player }
    }

    private fun openCollection() {
        tap(R.id.nav_item_favorites)
        await("the collection") { it.destination() == R.id.favorites }
    }

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
