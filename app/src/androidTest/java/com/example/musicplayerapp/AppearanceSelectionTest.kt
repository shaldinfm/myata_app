package com.example.musicplayerapp

import android.content.res.Configuration
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.appcompat.app.AppCompatActivity
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

    // ==================== what SYSTEM actually installs ====================

    /**
     * Stored `system` installs **no local override** on the live delegate.
     *
     * The unit test asserts the mapping; this asserts the activity. They are
     * different claims: the mapping could be right and the assignment could be
     * skipped, overwritten by AppCompat, or applied to the wrong delegate, and only
     * reading `MainActivity.delegate.localNightMode` on a running activity rules
     * that out.
     */
    @Test
    fun stored_system_leaves_the_activity_with_no_local_override() {
        ThemeStore.write(context, ThemeMode.SYSTEM)

        withActivity {
            await("HOME") { it.destination() == R.id.home }
            assertEquals(
                "stored `system` must install no local override",
                AppCompatDelegate.MODE_NIGHT_UNSPECIFIED,
                localNightMode(),
            )
            assertEquals(nightNow(), systemIsNight())
        }
    }

    /**
     * A fresh install is indistinguishable from an explicit `system`.
     *
     * Same delegate value, same appearance, and - the half a behaviour check cannot
     * see - **nothing on disk**. That is the whole migration story for the existing
     * installs, so it is asserted rather than described.
     */
    @Test
    fun a_fresh_install_is_identical_to_an_explicit_system_choice() {
        // Fresh: no key at all.
        assertNull("a fresh install must store nothing", ThemeStore.rawForTest(context))
        var fresh: Int? = null
        withActivity {
            await("HOME") { it.destination() == R.id.home }
            fresh = localNightMode()
        }

        // Explicit, written by hand.
        ThemeStore.write(context, ThemeMode.SYSTEM)
        assertEquals(ThemeMode.STORED_SYSTEM, ThemeStore.rawForTest(context))
        var explicit: Int? = null
        withActivity {
            await("HOME") { it.destination() == R.id.home }
            explicit = localNightMode()
        }

        assertEquals(
            "an install that has chosen nothing must behave exactly like one that " +
                "chose Системная",
            explicit,
            fresh,
        )
        assertEquals(AppCompatDelegate.MODE_NIGHT_UNSPECIFIED, fresh)
    }

    /** Cold start on a stored `system` costs one activity creation, not two. */
    @Test
    fun a_cold_start_on_stored_system_does_not_recreate() {
        ThemeStore.write(context, ThemeMode.SYSTEM)
        assertEquals(1, creationsDuring { await("HOME") { it.destination() == R.id.home } })
    }

    /** And so does a cold start on an explicit Тёмная - the override is applied once. */
    @Test
    fun a_cold_start_on_stored_dark_does_not_recreate() {
        ThemeStore.write(context, ThemeMode.DARK)
        assertEquals(
            1,
            creationsDuring {
                await("a dark activity on HOME") {
                    it.isNight() && it.destination() == R.id.home
                }
            },
        )
    }

    /**
     * A value this build cannot parse resolves to Системная and is **left alone**.
     *
     * The second half is the point. Rewriting the key to `system` would be a silent
     * migration - the app deciding on the listener's behalf that their unreadable
     * preference is now this one - and a downgrade that understood the original
     * value would find it gone. Reading it as the default costs nothing and
     * destroys nothing.
     */
    @Test
    fun a_corrupt_value_resolves_to_system_and_is_not_rewritten() {
        for (junk in listOf("amoled", "SYSTEM", "", "true")) {
            ThemeStore.writeRawForTest(context, junk)
            assertEquals(ThemeMode.SYSTEM, ThemeStore.read(context))

            withActivity {
                await("HOME") { it.destination() == R.id.home }
                assertEquals(
                    "a corrupt value must install no override",
                    AppCompatDelegate.MODE_NIGHT_UNSPECIFIED,
                    localNightMode(),
                )
                assertEquals(nightNow(), systemIsNight())
            }

            assertEquals(
                "the unparseable value [$junk] was rewritten; resolving a default " +
                    "must not migrate anybody's preference",
                junk,
                ThemeStore.rawForTest(context),
            )
        }
    }

    /**
     * Dark, then Системная, leaves the activity with no explicit override.
     *
     * Clearing an override is a different AppCompat path from setting one, so
     * ending on the right *appearance* is not enough - the delegate has to be back
     * at `MODE_NIGHT_UNSPECIFIED`, or the next cold start is applying a forced mode
     * that nobody chose.
     */
    @Test
    fun dark_then_system_clears_the_local_override() {
        ThemeStore.write(context, ThemeMode.DARK)

        withActivity {
            openAppearance()
            await("a dark activity") { it.isNight() }
            assertEquals(AppCompatDelegate.MODE_NIGHT_YES, localNightMode())

            tap(R.id.appearance_row_system)
            await("an activity matching the device") { it.isNight() == systemIsNight() }

            assertEquals(
                "choosing Системная must remove the override, not replace it with " +
                    "an explicit follow-the-system value",
                AppCompatDelegate.MODE_NIGHT_UNSPECIFIED,
                localNightMode(),
            )
            assertEquals(ThemeMode.SYSTEM, ThemeStore.read(context))
        }
    }

    /** The live delegate's local night mode, read off the activity on screen now. */
    private fun localNightMode(): Int = onMain { (it as AppCompatActivity).delegate.localNightMode }

    /** Whether the activity on screen right now is in night mode. */
    private fun nightNow(): Boolean = onMain { it.isNight() }

    /** How many MainActivity instances are created while [body] runs. */
    private fun creationsDuring(body: () -> Unit): Int {
        val created = Collections.newSetFromMap(IdentityHashMap<MainActivity, Boolean>())
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && stage == Stage.CREATED) {
                synchronized(created) { created += activity }
            }
        }
        monitor.addLifecycleCallback(callback)
        try {
            withActivity { body() }
        } finally {
            monitor.removeLifecycleCallback(callback)
        }
        return synchronized(created) { created.size }
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
     * `MainActivity` assigns `localNightMode` on every launch. For an install that
     * has chosen nothing that assignment is `MODE_NIGHT_UNSPECIFIED`, which is
     * AppCompat's own unset value - so it is not a change and costs nothing.
     *
     * The first implementation assigned the explicit `MODE_NIGHT_FOLLOW_SYSTEM`
     * instead. The two follow the system identically once applied, but that one *is*
     * a change to the delegate: this test caught it recreating the activity on every
     * cold start, a startup regression paid by everybody to no effect. The mapping
     * moved to `MODE_NIGHT_UNSPECIFIED` because of this test.
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
                        "than once - the local night mode assigned for Системная is " +
                        "costing a recreation on every cold start",
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
