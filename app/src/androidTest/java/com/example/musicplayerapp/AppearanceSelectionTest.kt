package com.example.musicplayerapp

import android.content.res.Configuration
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.musicplayerapp.data.ThemeStore
import com.example.musicplayerapp.ui.settings.ThemeMode
import java.util.Collections
import java.util.IdentityHashMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Choosing an appearance: what it writes, what it repaints, and what it must not touch.
 *
 * ## The API 24 harness rules this file follows
 *
 * `runOnMainSync` and a sleep loop, never `ActivityScenario.onActivity` or
 * `waitForIdleSync`, for the reason recorded in `AuthFormTest` and
 * `AuthTestDoubles.resumedMainActivity`: on the software-rendered API 24 image an
 * activity that is animating - and one that is being recreated always is - can fail
 * to hand out an idle looper, and a helper that waits for one hangs for the length
 * of the whole suite rather than failing.
 *
 * The activity object is looked up fresh from the lifecycle monitor every time,
 * because a theme change replaces it. Holding one across a choice is holding a
 * destroyed activity.
 */
@RunWith(AndroidJUnit4::class)
class AppearanceSelectionTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun freshInstall() {
        if (Build.VERSION.SDK_INT >= 33) {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
                ),
            ).use { it.readBytes() }
        }
        ThemeStore.clearForTest(context)
    }

    @After
    fun tidy() = ThemeStore.clearForTest(context)

    // ==================== the default, and the absence of a migration ====================

    /**
     * An install that has never opened this screen has nothing on disk.
     *
     * This is the whole of the migration story for the existing installs: no key is
     * written at first launch, at upgrade, or by opening the screen and leaving it,
     * so everybody arrives at SYSTEM by the absence of a value rather than by a
     * backfill somebody has to run.
     */
    @Test
    fun a_fresh_install_stores_nothing_and_resolves_to_system() {
        assertEquals(ThemeMode.SYSTEM, ThemeStore.read(context))

        withActivity {
            openAppearance()
            // Looking at the screen is not choosing.
            back()
            back()
        }

        assertEquals(
            "opening the appearance screen wrote a preference nobody chose",
            ThemeMode.SYSTEM,
            ThemeStore.read(context),
        )
        assertNull("a key was written for the default", ThemeStore.rawForTest(context))
    }

    /** The row shows what is stored, and the settings shell shows it too. */
    @Test
    fun the_chosen_mode_is_shown_on_both_screens() {
        ThemeStore.write(context, ThemeMode.DARK)

        withActivity {
            openSettingsAndSettle()
            assertEquals(
                context.getString(R.string.settings_theme_dark),
                textOf(R.id.settings_row_theme_value),
            )

            tap(R.id.settings_row_theme)
            await("the appearance screen") { it.destination() == R.id.settings_appearance }

            assertEquals(View.VISIBLE, visibilityOf(R.id.appearance_check_dark))
            assertEquals(View.INVISIBLE, visibilityOf(R.id.appearance_check_system))
            assertEquals(View.INVISIBLE, visibilityOf(R.id.appearance_check_light))
        }
    }

    // ==================== choosing ====================

    @Test
    fun choosing_dark_writes_it_and_repaints_without_leaving_the_screen() {
        withActivity {
            openAppearance()
            tap(R.id.appearance_row_dark)

            // The activity is recreated by the choice. Wait for a resumed one that
            // is in night mode and still on this destination.
            await("a dark activity on the appearance screen") {
                it.isNight() && it.destination() == R.id.settings_appearance
            }

            assertEquals(ThemeMode.DARK, ThemeStore.read(context))
            assertEquals(View.VISIBLE, visibilityOf(R.id.appearance_check_dark))
            assertEquals(View.INVISIBLE, visibilityOf(R.id.appearance_check_system))
        }
    }

    @Test
    fun choosing_light_leaves_night_mode_even_from_dark() {
        ThemeStore.write(context, ThemeMode.DARK)

        withActivity {
            openAppearance()
            await("a dark activity") { it.isNight() }

            tap(R.id.appearance_row_light)
            await("a light activity on the appearance screen") {
                !it.isNight() && it.destination() == R.id.settings_appearance
            }

            assertEquals(ThemeMode.LIGHT, ThemeStore.read(context))
            assertEquals(View.VISIBLE, visibilityOf(R.id.appearance_check_light))
        }
    }

    /**
     * Dark back to Системная really does let go.
     *
     * Системная is expressed as `MODE_NIGHT_UNSPECIFIED` - *no local override* -
     * rather than as `MODE_NIGHT_FOLLOW_SYSTEM`, because the explicit constant costs
     * a recreation on every cold start. Clearing an override is a different code path
     * in AppCompat from setting one, so it gets its own test: from Тёмная, choosing
     * Системная has to come back to whatever the device itself is.
     *
     * The expected answer is read from the **application** context, which has no
     * delegate and therefore no override - so this asserts "the app agrees with the
     * device" without hard-coding which way round the emulator happens to be.
     */
    @Test
    fun choosing_system_from_dark_returns_to_the_device_appearance() {
        ThemeStore.write(context, ThemeMode.DARK)

        withActivity {
            openAppearance()
            await("a dark activity") { it.isNight() }

            tap(R.id.appearance_row_system)
            await("an activity matching the device") {
                it.isNight() == systemIsNight() &&
                    it.destination() == R.id.settings_appearance
            }

            assertEquals(ThemeMode.SYSTEM, ThemeStore.read(context))
            assertEquals(View.VISIBLE, visibilityOf(R.id.appearance_check_system))
            assertEquals(View.INVISIBLE, visibilityOf(R.id.appearance_check_dark))
        }
    }

    /** What the device itself is, read where no delegate can have overridden it. */
    private fun systemIsNight(): Boolean =
        (context.applicationContext.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * Tapping the row that is already chosen is not a change.
     *
     * AppCompat would no-op the night mode anyway; what this rules out is the
     * recreation. On this screen a recreation is visible, and a listener tapping
     * the row they are already on has asked for nothing to happen.
     */
    @Test
    fun re_choosing_the_current_mode_does_not_recreate() {
        ThemeStore.write(context, ThemeMode.LIGHT)

        withActivity {
            openAppearance()
            val before = activity()

            tap(R.id.appearance_row_light)
            Thread.sleep(1_000)

            assertSameActivity(
                "re-choosing the current appearance recreated the activity",
                before,
            )
            assertEquals(ThemeMode.LIGHT, ThemeStore.read(context))
        }
    }

    // ==================== it survives a relaunch ====================

    /**
     * A new activity, launched cold, comes up in the stored appearance.
     *
     * The process is not killed between the two launches - an instrumentation run
     * cannot kill its own process and survive - so what this proves is that the
     * appearance is read from disk in `onCreate` and applied before the first frame,
     * rather than being held in memory by the activity that chose it. The other half,
     * that the disk value itself is durable, is `SharedPreferences` and is covered by
     * `ThemeModeTest` on the parsing side.
     */
    @Test
    fun a_relaunched_activity_comes_up_in_the_stored_appearance() {
        withActivity {
            openAppearance()
            tap(R.id.appearance_row_dark)
            await("a dark activity") { it.isNight() }
        }

        assertEquals(ThemeMode.DARK, ThemeStore.read(context))

        // A fresh activity, with nothing carried over but the preference file.
        withActivity {
            await("a dark activity on a fresh launch") { it.isNight() }
        }
    }

    // ==================== what it must not touch ====================

    /**
     * The process-wide night mode is never forced. This is the Android TV isolation.
     *
     * `TvMainActivity` is an `AppCompatActivity` in this same process and the
     * `<application>` theme it sits under is a DayNight tree, so
     * `AppCompatDelegate.setDefaultNightMode` from a phone screen would reach a TV
     * surface that cannot open that screen. `MainActivity` uses
     * `delegate.localNightMode` instead, which is scoped to one activity - and this
     * is the assertion that keeps it that way.
     *
     * Not asserted as one specific value: an AppCompat nobody has called
     * `setDefaultNightMode` on reports `MODE_NIGHT_UNSPECIFIED`, not
     * `MODE_NIGHT_FOLLOW_SYSTEM`. What matters is that it is never `YES` or `NO`,
     * the two values that would impose an appearance on the whole process. See
     * `TvThemeIsolationTest` for the same check and the fuller note.
     */
    @Test
    fun the_process_wide_night_mode_is_never_forced() {
        assertNotForced()

        withActivity {
            openAppearance()
            tap(R.id.appearance_row_dark)
            await("a dark activity") { it.isNight() }
            assertNotForced()

            tap(R.id.appearance_row_light)
            await("a light activity") { !it.isNight() }
            assertNotForced()
        }

        assertNotForced()
    }

    private fun assertNotForced() {
        val actual = AppCompatDelegate.getDefaultNightMode()
        assertTrue(
            "something called AppCompatDelegate.setDefaultNightMode($actual); that " +
                "is process-wide and reaches TvMainActivity",
            actual != AppCompatDelegate.MODE_NIGHT_YES &&
                actual != AppCompatDelegate.MODE_NIGHT_NO,
        )
    }

    /**
     * The default path costs no extra recreation.
     *
     * `MainActivity` now assigns `localNightMode` on every launch, and for an install
     * that has chosen nothing that assignment is `MODE_NIGHT_FOLLOW_SYSTEM` where
     * previously nothing was assigned at all. AppCompat's own unset default is
     * `MODE_NIGHT_UNSPECIFIED`, so the two are not the same value even though they
     * follow the system identically - and if AppCompat treated the difference as a
     * change it would recreate the activity on every cold start, a startup regression
     * paid by everybody to no effect.
     *
     * ## Counting creations, not destructions
     *
     * The first version of this counted activities in `Stage.DESTROYED` and compared
     * a delta. That does not work: the lifecycle monitor holds **weak** references, so
     * activities left by earlier tests are collected at unpredictable moments and the
     * delta came back **negative**. What is counted here instead is how many distinct
     * MainActivity instances are *created* while a callback is registered, which is
     * an event rather than a snapshot and cannot be un-counted by a GC.
     *
     * The second half chooses a theme and asserts the count **does** reach two, which
     * is what stops the first half from passing because the counter sees nothing.
     */
    @Test
    fun the_default_appearance_does_not_recreate_the_activity_on_launch() {
        val created = Collections.newSetFromMap(IdentityHashMap<MainActivity, Boolean>())
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && stage == Stage.CREATED) {
                synchronized(created) { created += activity }
            }
        }
        monitor.addLifecycleCallback(callback)

        try {
            withActivity {
                await("HOME") { it.destination() == R.id.home }
                assertEquals(
                    "launching with no stored appearance created MainActivity more " +
                        "than once - assigning MODE_NIGHT_FOLLOW_SYSTEM is costing a " +
                        "recreation on every cold start",
                    1,
                    synchronized(created) { created.size },
                )

                openAppearance()
                tap(R.id.appearance_row_dark)
                await("a dark activity") { it.isNight() }

                assertEquals(
                    "a real theme change did not create a second MainActivity - the " +
                        "counter cannot see a recreation, so the assertion above " +
                        "proves nothing",
                    2,
                    synchronized(created) { created.size },
                )
            }
        } finally {
            monitor.removeLifecycleCallback(callback)
        }
    }

    /**
     * The appearance belongs to the device, not to an account.
     *
     * It is stored in its own preferences file for that reason, and nothing in the
     * identity or deletion cleanup touches it. A sign-out that reset somebody's
     * theme would be a surprising thing for a sign-out to do.
     */
    @Test
    fun the_appearance_is_not_stored_with_the_identity() {
        ThemeStore.write(context, ThemeMode.DARK)
        com.example.musicplayerapp.data.supabase.IdentityStore.clearForTest(context)
        assertEquals(
            "clearing the identity cleared the appearance",
            ThemeMode.DARK,
            ThemeStore.read(context),
        )
    }

    // ==================== helpers ====================

    private fun withActivity(body: () -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            body()
        } finally {
            try {
                scenario.close()
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "activity close timed out; checks already complete", e)
            }
        }
    }

    private fun openAppearance() {
        openSettingsAndSettle()
        tap(R.id.settings_row_theme)
        await("the appearance screen") { it.destination() == R.id.settings_appearance }
    }

    private fun back() {
        val id = onMain { it.currentBackViewId() }
        if (id != null) tap(id)
        Thread.sleep(200)
    }

    private fun MainActivity.currentBackViewId(): Int? = when (destination()) {
        R.id.settings_appearance -> R.id.appearance_back
        R.id.settings -> R.id.settings_back
        else -> null
    }

    private fun tap(id: Int) {
        onMain { it.findViewById<View>(id).performClick() }
        instrumentation.runOnMainSync { }
    }

    private fun textOf(id: Int): String =
        onMain { it.findViewById<android.widget.TextView>(id).text.toString() }

    private fun visibilityOf(id: Int): Int = onMain { it.findViewById<View>(id).visibility }

    private fun activity(): MainActivity = onMain { it }

    private fun assertSameActivity(message: String, before: MainActivity) {
        val now = activity()
        assertTrue(message, before === now)
        assertFalse(message, before.isDestroyed)
    }

    private fun MainActivity.destination(): Int? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment)
            as? androidx.navigation.fragment.NavHostFragment
        return host?.navController?.currentDestination?.id
    }

    private fun MainActivity.isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun await(what: String, timeoutMs: Long = 20_000, check: (MainActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            runCatching { instrumentation.runOnMainSync { ok = check(resumed()) } }
            if (ok) return
            Thread.sleep(25)
        }
        error("timed out after ${timeoutMs}ms waiting for $what")
    }

    private fun <T> onMain(block: (MainActivity) -> T): T {
        var out: T? = null
        instrumentation.runOnMainSync { out = block(resumed()) }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    /** The activity that is on screen *now* - a theme change replaces the object. */
    private fun resumed(): MainActivity =
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>()
            .firstOrNull() ?: error("no resumed MainActivity")

    private fun assertNull(message: String, value: Any?) =
        assertTrue("$message (was $value)", value == null)

    private companion object {
        const val TAG = "APPEARANCEQA"
    }
}
