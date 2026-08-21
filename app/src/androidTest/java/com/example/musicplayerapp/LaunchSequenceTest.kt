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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The launch sequence: what may be on screen, and when.
 *
 * The sequence is now two steps - the platform's starting window, then HOME. The
 * artwork SplashFragment that used to sit between them is gone, and with it the
 * defect this file was originally written for: the bottom navigation bar drawn on
 * top of the un-migrated splash artwork for ~0.5-0.7s of every cold launch.
 *
 * That invariant cannot be restated, because the thing it protected against no
 * longer exists. What replaces it is stronger and simpler: **HOME is the first
 * application screen, and its first presented frame already carries the bar.**
 * There is no hidden-to-visible step to catch, because the bar is visible from
 * `onCreate` and nothing on this path hides it.
 *
 * The first-frame check is still hung off an
 * [android.view.ViewTreeObserver.OnPreDrawListener] rather than sampled. That was
 * a lesson learned the hard way here: a polled version of this test reported that
 * the splash was never on screen, because with the playlist response already in
 * OkHttp's cache the whole transient could happen between `launch` returning and
 * the first sample. A transient cannot be caught by looking at it later.
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
        val homeViewCreated = CountDownLatch(1)
        val homeFirstFrame = CountDownLatch(1)

        /** Any destination that reached the screen before HOME did. */
        @Volatile var firstFragment: String? = null

        /** Non-null only if HOME's first presented frame had no bottom bar. */
        @Volatile var firstFrameViolation: String? = null

        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, s: Bundle?
        ) {
            if (firstFragment == null) firstFragment = f.javaClass.simpleName
            if (f !is MainFragment) return
            homeViewCreated.countDown()

            val root = v
            val observer = root.viewTreeObserver
            observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (root.width == 0 || root.height == 0 || root.alpha < 1f) return true
                    val activity = f.activity as? MainActivity
                    if (activity != null &&
                        activity.binding.bottomNavView.visibility != View.VISIBLE
                    ) {
                        firstFrameViolation = "HOME's first presented frame had no bottom " +
                                "navigation bar (alpha=${root.alpha}, ${root.width}x${root.height})"
                    }
                    homeFirstFrame.countDown()
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                    return true
                }
            })
        }
    }

    @Test
    fun homeIsTheFirstScreenAndItsFirstFrameCarriesTheBottomNav() {
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
            // No network in this assertion, and that is the headline: HOME is
            // reached without waiting for anything. The old sequence could not
            // make this claim - it waited on a real playlist fetch, which is why
            // the equivalent assertion had to be an assumption instead.
            assertTrue(
                "HOME never created a view within ${LAUNCH_BUDGET_SECONDS}s",
                recorder.homeViewCreated.await(LAUNCH_BUDGET_SECONDS, TimeUnit.SECONDS)
            )
            assertTrue(
                "HOME never presented a frame within ${LAUNCH_BUDGET_SECONDS}s",
                recorder.homeFirstFrame.await(LAUNCH_BUDGET_SECONDS, TimeUnit.SECONDS)
            )
        }

        assertEquals(
            "the first destination on screen should be HOME itself",
            "MainFragment", recorder.firstFragment
        )
        recorder.firstFrameViolation?.let { org.junit.Assert.fail(it) }
    }

    /**
     * HOME is the navigation graph's start destination.
     *
     * Asserted on the graph rather than by observing a launch, so it fails for the
     * right reason if someone reintroduces an intermediate destination: the launch
     * would still end at HOME, just later.
     */
    @Test
    fun theGraphStartsAtHome() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.navHostFragment) as NavHostFragment
                assertEquals(
                    "the mobile graph must start at HOME",
                    R.id.home,
                    navHost.navController.graph.startDestinationId,
                )
            }
        }
    }

    /**
     * The launch theme must not carry windowDisablePreview.
     *
     * This is the flag that produced the measured ~4.7s of untouched launcher on a
     * cold start: with it set there is no starting window on 24-30 and no platform
     * splash on 31+. It is checked on the *live* activity theme, so it covers the
     * composition of the launch theme and AppTheme rather than either in isolation.
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
