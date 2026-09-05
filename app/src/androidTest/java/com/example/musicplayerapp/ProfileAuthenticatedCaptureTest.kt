package com.example.musicplayerapp

import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.ThemeStore
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import com.example.musicplayerapp.ui.settings.ThemeMode
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screenshots of profile-authenticated, for the owner's visual review.
 *
 * ## Why this is a test and not an adb script
 *
 * The screen only opens for a `REGISTERED` install with a live session, and there are
 * only three ways to arrange that. Registering for real spends an `auth.users` row
 * that nothing in this repository can delete. Writing the preferences from `adb` gets
 * the identity but not the session, so the screen honestly steps aside to the guest
 * profile a second later - which is the behaviour working, and useless for a picture.
 * The third way is the seam the suite already has: `EmailAuthBackend` with a fake that
 * reports an account.
 *
 * The alternative considered and rejected was a debug-only preference the app would
 * read to fake a session. That is a backdoor into the identity model, in production
 * source, to make a screenshot easier - and the identity model is the part of this app
 * with the least room for one.
 *
 * ## Opt-in
 *
 * Runs only under `captureProfile=true`, because it writes files and changes
 * this install's persisted appearance:
 *
 * ```
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.captureProfile=true \
 *   "-Pandroid.testInstrumentationRunnerArguments.class=com.example.musicplayerapp.ProfileAuthenticatedCaptureTest"
 * ```
 *
 * The PNGs are written by `screencap` to shared storage - not the app's own files
 * directory, which goes with the package when the run uninstalls it - and pulled with
 * `adb pull`. Nothing here reaches a network.
 */
@RunWith(AndroidJUnit4::class)
class ProfileAuthenticatedCaptureTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private lateinit var db: AppDatabase
    private lateinit var auth: FakeEmailAuthApi

    private val account = "22222222-2222-4222-8222-222222222222"

    /** Shared storage: it outlives the uninstall the instrumentation run ends with. */
    private val SHARED = "/sdcard/Pictures"

    @Before
    fun open() {
        assumeTrue(
            "opt in with -Pandroid.testInstrumentationRunnerArguments.captureProfile=true",
            InstrumentationRegistry.getArguments().getString("captureProfile")
                ?.trim().equals("true", ignoreCase = true),
        )

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand(
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
        IdentityStore.markRegistered(context, account)
        // A real recorded sync, two minutes old, so the row shows what it will show
        // in life rather than the never-synced fallback.
        LastSyncStore.recordForTest(context, account, System.currentTimeMillis() - 2 * 60_000L)
    }

    @After
    fun close() {
        if (!::db.isInitialized) return
        // Back to an install that has made no appearance choice, which is the state
        // every other suite expects to find.
        ThemeStore.clearForTest(context)
        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
        AppDatabase.overrideForInstrumentation(null)
        TestIsolation.restoreBackends()
        db.close()
    }

    @Test
    fun capturesBothThemes() {

        // The appearance is chosen the way the app chooses it, by writing
        // ThemeStore before the activity is launched. `setDefaultNightMode` used to
        // do this and no longer can: MainActivity sets `localNightMode` on its own
        // delegate in onCreate (G1), and an activity-local mode wins over the
        // process default - so the dark shot would have come back light.
        for ((mode, theme) in listOf(
            ThemeMode.LIGHT to "light",
            ThemeMode.DARK to "dark",
        )) {
            ThemeStore.write(context, mode)

            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                // Two taps since G1: the header control opens settings, and the
                // profile row inside it is what routes to the account card.
                tap(R.id.settings_entry)
                await("the settings shell") { it.currentDestinationId() == R.id.settings }
                tap(R.id.settings_row_profile)
                await("the account card") {
                    it.currentDestinationId() == R.id.profile_authenticated &&
                        it.findViewById<android.widget.TextView>(R.id.profile_account_name)
                            .text.toString() == "Денис"
                }
                // The card is painted; let the ink correction settle before the shot.
                Thread.sleep(400)

                // `screencap` rather than `takeScreenshot()` + a file write, because
                // the app's own files directory goes with the package when the
                // instrumentation run uninstalls it - taking the screenshots with it.
                // screencap runs as shell and writes somewhere that survives.
                screencap("$SHARED/profile-authenticated-$theme.png")
            } finally {
                try {
                    scenario.close()
                } catch (e: Throwable) {
                    android.util.Log.w("PROFILEQA", "activity close timed out", e)
                }
            }
        }

        android.util.Log.i("PROFILEQA", "screenshots written to $SHARED")
    }

    /**
     * Runs a shell command and waits for it, which is what makes the file exist.
     *
     * `executeShellCommand` is asynchronous and hands back stdout; draining that
     * descriptor to EOF is the only signal the command has finished, and a screenshot
     * pulled before it has is a truncated file.
     */
    private fun screencap(path: String) {
        instrumentation.uiAutomation
            .executeShellCommand("screencap -p $path")
            .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it) }
            .use { it.readBytes() }
    }

    private fun tap(id: Int) {
        on { it.findViewById<android.view.View>(id).performClick() }
        instrumentation.runOnMainSync { }
    }

    private fun await(what: String, timeoutMs: Long = 15_000, check: (MainActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            runCatching { on { ok = check(it) } }
            if (ok) return
            Thread.sleep(25)
        }
        error("timed out waiting for $what")
    }

    /** See `AuthFormTest`: `onActivity` waits for an idle looper. */
    private fun on(block: (MainActivity) -> Unit) {
        instrumentation.runOnMainSync {
            val current = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull() ?: error("no resumed MainActivity")
            block(current)
        }
    }

    private fun MainActivity.currentDestinationId(): Int? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment)
            as androidx.navigation.fragment.NavHostFragment
        return host.navController.currentDestination?.id
    }
}
