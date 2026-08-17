package com.example.musicplayerapp

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.musicplayerapp.fragments.MainFragment
import com.example.musicplayerapp.fragments.SplashFragment
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The launch sequence: what may be on screen, and when.
 *
 * The defect this pins down was visible on every cold launch, on API 24 and API
 * 36 alike - the migrated bottom navigation bar drawn on top of the un-migrated
 * splash artwork for ~0.5-0.7s. MainFragment.onResume runs when the transaction
 * commits, which is the *start* of the splash's 250ms exit fade, so the bar
 * appeared while the splash was still at full opacity.
 *
 * The check is hung off the exact lifecycle moment the defect happened -
 * MainFragment being resumed - rather than sampled. The first version of this
 * test polled, and was wrong: with the playlist response already in OkHttp's
 * cache the splash can come and go between ActivityScenario.launch returning and
 * the first sample, so the run reported that the splash was never on screen. A
 * transient cannot be caught by looking at it later, only by being told about it.
 *
 * Registering through ActivityLifecycleMonitorRegistry at Stage.CREATED is what
 * makes that possible: it runs after MainActivity.onCreate but before the
 * NavHost's children reach onCreateView, so the splash's view is always seen.
 *
 * Test-only. Compiled into the androidTest APK and never shipped.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class LaunchSequenceTest {

    private var lifecycleCallback: ActivityLifecycleCallback? = null

    @After
    fun tearDown() {
        lifecycleCallback?.let {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(it)
        }
        lifecycleCallback = null
    }

    private class Recorder : FragmentManager.FragmentLifecycleCallbacks() {
        val splashViewCreated = CountDownLatch(1)
        val homeResumed = CountDownLatch(1)

        @Volatile var splashHasView = false
        /** Non-null only if the invariant was actually broken. */
        @Volatile var violation: String? = null

        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, s: Bundle?
        ) {
            if (f is SplashFragment) {
                splashHasView = true
                splashViewCreated.countDown()
            }
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f is SplashFragment) splashHasView = false
        }

        /**
         * The moment of the defect. MainFragment.onResume is what asked for the
         * bottom bar; if the splash still has a view here, the bar must not have
         * been granted it.
         */
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (f !is MainFragment) return
            val activity = f.activity as? MainActivity
            if (splashHasView && activity?.binding?.bottomNavView?.visibility == View.VISIBLE) {
                violation = "the bottom navigation bar was already visible when HOME " +
                        "resumed, while the splash still had a view on screen"
            }
            homeResumed.countDown()
        }
    }

    @Test
    fun bottomNavIsNotGrantedToHomeWhileTheSplashIsStillOnScreen() {
        val recorder = Recorder()

        val callback = ActivityLifecycleCallback { activity: Activity, stage: Stage ->
            if (stage == Stage.CREATED && activity is MainActivity) {
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.navHostFragment) as? NavHostFragment
                navHost?.childFragmentManager
                    ?.registerFragmentLifecycleCallbacks(recorder, false)
            }
        }
        lifecycleCallback = callback
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)

        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "the splash destination never created a view - this launch did not " +
                        "exercise the sequence under test",
                recorder.splashViewCreated.await(LAUNCH_BUDGET_SECONDS, TimeUnit.SECONDS)
            )
            // An assumption, not an assertion, and deliberately so. The splash
            // exits on a real playlist fetch, so a device that is offline - or
            // simply still busy from RandomWindowBackgroundTest's twenty launches,
            // which is what made this fail in a combined run and pass on its own -
            // cannot exercise the sequence at all. That is an environment that
            // cannot answer the question, not an answer of "no": it shows up as a
            // skipped test rather than a red one, and never as a silent pass.
            org.junit.Assume.assumeTrue(
                "HOME never resumed within ${LAUNCH_BUDGET_SECONDS}s - the playlist " +
                        "load did not finish, so the splash never handed over",
                recorder.homeResumed.await(LAUNCH_BUDGET_SECONDS, TimeUnit.SECONDS)
            )
        }

        recorder.violation?.let { org.junit.Assert.fail(it) }
    }

    /**
     * The launch theme must not carry windowDisablePreview.
     *
     * This is the flag that produced the measured ~4.7s of untouched launcher on a
     * cold start: with it set there is no starting window on 24-30 and no platform
     * splash on 31+. It is checked on the *live* activity theme, so it covers the
     * composition of the launch theme, AppTheme and the random AppTheme0..9 rather
     * than any one of them in isolation.
     */
    @Test
    fun theLiveThemeDoesNotSuppressTheStartingWindow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val tv = android.util.TypedValue()
                val set = activity.theme.resolveAttribute(
                    android.R.attr.windowDisablePreview, tv, true
                ) && tv.data != 0
                assertFalse(
                    "windowDisablePreview is set on the running activity's theme - " +
                            "the starting window and the platform splash are suppressed again",
                    set
                )
            }
        }
    }

    private companion object {
        /** A ceiling for "something is wrong", not a performance assertion. */
        const val LAUNCH_BUDGET_SECONDS = 60L
    }
}
