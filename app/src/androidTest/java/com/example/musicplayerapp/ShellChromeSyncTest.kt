package com.example.musicplayerapp

import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.fragments.MainFragment
import com.example.musicplayerapp.fragments.SettingsFragment
import com.example.musicplayerapp.ui.NavScreen
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shell's chrome - the screen key the Mini Player and the bar's pill read,
 * and the bottom bar itself - changes with the screen, not a frame before it.
 *
 * Regression test for the Mini Player flashing up before HOME on Back from
 * Settings. The destination listener fires synchronously inside popBackStack(),
 * while the fragment transaction that brings HOME back is only posted. Setting
 * the chrome from the listener made the shell request a layout, and a layout
 * request puts a sync barrier in front of every ordinary message - the posted
 * transaction included - until the next frame is drawn. So that frame was always
 * Settings with HOME's Mini Player and bar over it.
 *
 * Asserted at the only moment that matters: inside the same main-thread turn as
 * the navigation call, before the transaction can have run. Whatever the chrome
 * says there is what the next frame draws.
 */
@RunWith(AndroidJUnit4::class)
class ShellChromeSyncTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun grantNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            val context = instrumentation.targetContext
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
                ),
            ).use { it.readBytes() }
        }
    }

    private class Chrome(val key: String?, val bar: Int, val homeViewExists: Boolean, val settingsViewExists: Boolean)

    @Test
    fun back_from_settings_keeps_settings_chrome_until_home_is_back() {
        withActivity {
            await("HOME") { it.currentDestinationIdOrNull() == R.id.home && hasView<MainFragment>(it) }

            // Forward: the chrome stays HOME's while HOME is still what is drawn.
            val onNavigate = sameTurn { host -> host.navController.navigate(R.id.settings) }
            assertEquals("Settings' view cannot exist yet", false, onNavigate.settingsViewExists)
            assertEquals("the key moved before Settings was on screen", NavScreen.HOME, onNavigate.key)
            assertEquals("the bar went before HOME did", View.VISIBLE, onNavigate.bar)

            await("Settings") { hasView<SettingsFragment>(it) && it.viewModel.currentFragmentLiveData.value == NavScreen.PUSHED }
            read { assertEquals("Settings has no bottom bar", View.GONE, it.findViewById<View>(R.id.bottomNavView).visibility) }

            // Back: the chrome stays Settings' until HOME's view exists.
            val onBack = sameTurn { host -> host.navController.popBackStack() }
            assertEquals("HOME's view cannot exist yet", false, onBack.homeViewExists)
            assertEquals("HOME's key arrived before HOME did", NavScreen.PUSHED, onBack.key)
            assertEquals("HOME's bar arrived over Settings", View.GONE, onBack.bar)

            // And it does arrive, with HOME.
            await("HOME's chrome") {
                hasView<MainFragment>(it) &&
                    it.viewModel.currentFragmentLiveData.value == NavScreen.HOME &&
                    it.findViewById<View>(R.id.bottomNavView).visibility == View.VISIBLE
            }
        }
    }

    /** Runs [navigate] and reads the chrome in the same main-thread turn. */
    private fun sameTurn(navigate: (NavHostFragment) -> Unit): Chrome {
        lateinit var chrome: Chrome
        instrumentation.runOnMainSync {
            val activity = resumedMainActivity()
            navigate(host(activity))
            chrome = Chrome(
                key = activity.viewModel.currentFragmentLiveData.value,
                bar = activity.findViewById<View>(R.id.bottomNavView).visibility,
                homeViewExists = hasView<MainFragment>(activity),
                settingsViewExists = hasView<SettingsFragment>(activity),
            )
        }
        return chrome
    }

    private fun host(activity: MainActivity) =
        activity.supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment

    private inline fun <reified T> hasView(activity: MainActivity): Boolean =
        host(activity).childFragmentManager.fragments.any { it is T && it.view != null && !it.isRemoving }

    private fun read(block: (MainActivity) -> Unit) =
        instrumentation.runOnMainSync { block(resumedMainActivity()) }

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

    /** See `SettingsEntryTest.withActivity` for why this is not `use {}`. */
    private fun withActivity(body: () -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            body()
        } finally {
            runCatching { scenario.close() }
        }
    }
}
