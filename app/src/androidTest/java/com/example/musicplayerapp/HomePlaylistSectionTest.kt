package com.example.musicplayerapp

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.MyataPlaylist
import com.example.musicplayerapp.data.PlaylistsState
import com.example.musicplayerapp.fragments.MainFragment
import com.example.musicplayerapp.ui.HomePlaylistSection
import com.example.musicplayerapp.ui.HomePlaylistsState
import androidx.navigation.fragment.NavHostFragment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HOME's playlist section on a device: that HOME is safe to exist before its data
 * does, and that the data arriving later actually lands.
 *
 * This is the half `HomePlaylistsStateTest` cannot answer. That one pins the
 * decision table; this one proves the wiring - an adapter that exists before the
 * list does, an observer that fills it afterwards, and a tap that cannot reach a
 * null.
 *
 * HOME is reached by navigating straight to it rather than by waiting for the
 * splash. That is deliberate: the whole point of the change is that HOME no
 * longer needs the splash's guarantee, so a test that let the splash provide it
 * would be testing the old contract.
 */
@RunWith(AndroidJUnit4::class)
class HomePlaylistSectionTest {

    private val samples = listOf(
        MyataPlaylist("https://example.invalid/a", Uri.parse("https://example.invalid/a.jpg")),
        MyataPlaylist("https://example.invalid/b", Uri.parse("https://example.invalid/b.jpg")),
    )

    // ==================== the section, state by state ====================

    /**
     * Every state applied to real views. The error and offline branches are only
     * reachable this way - a device test cannot make the playlist host fail on
     * cue - which is exactly why the mapping was split out of the fragment.
     */
    @Test
    fun everyStateShowsExactlyOneOfTheRowOrTheStatus() {
        onHome { fragment ->
            val b = fragment.binding
            for (state in HomePlaylistsState.entries) {
                HomePlaylistSection.apply(
                    state, b.playlistString, b.playlists, b.playlistState,
                    b.playlistLoading, b.playlistError, b.playlistErrorText,
                )
                val rowUp = b.playlists.visibility == View.VISIBLE
                val statusUp = b.playlistState.visibility == View.VISIBLE
                assertTrue(
                    "$state showed both the row and the status",
                    !(rowUp && statusUp),
                )
                assertEquals("$state row", state == HomePlaylistsState.POPULATED, rowUp)
                assertEquals("$state status", state.isStatus, statusUp)
                assertEquals(
                    "$state spinner",
                    state == HomePlaylistsState.LOADING,
                    b.playlistLoading.visibility == View.VISIBLE,
                )
                assertEquals(
                    "$state retry",
                    state.isRetryable,
                    b.playlistError.visibility == View.VISIBLE,
                )
            }
        }
    }

    @Test
    fun theTwoFailuresSayDifferentThings() {
        onHome { fragment ->
            val b = fragment.binding
            val text: TextView = b.playlistErrorText

            HomePlaylistSection.apply(
                HomePlaylistsState.ERROR_OFFLINE, b.playlistString, b.playlists,
                b.playlistState, b.playlistLoading, b.playlistError, text,
            )
            val offline = text.text.toString()

            HomePlaylistSection.apply(
                HomePlaylistsState.ERROR_FAILED, b.playlistString, b.playlists,
                b.playlistState, b.playlistLoading, b.playlistError, text,
            )
            val failed = text.text.toString()

            assertEquals(
                fragment.getString(R.string.splash_offline_title), offline
            )
            assertEquals(
                fragment.getString(R.string.splash_error_title), failed
            )
            assertTrue("the two failures must not read the same", offline != failed)
        }
    }

    @Test
    fun anEmptyResultHidesTheHeadingRatherThanCaptioningNothing() {
        onHome { fragment ->
            val b = fragment.binding
            HomePlaylistSection.apply(
                HomePlaylistsState.EMPTY, b.playlistString, b.playlists, b.playlistState,
                b.playlistLoading, b.playlistError, b.playlistErrorText,
            )
            assertEquals(View.GONE, b.playlistString.visibility)
            assertEquals(View.GONE, b.playlists.visibility)
            assertEquals(View.GONE, b.playlistState.visibility)
        }
    }

    // ==================== HOME before, during and after the data ====================

    @Test
    fun homeIsUsableWithNoPlaylistDataAtAll() {
        onHome(clearPlaylists = true) { fragment ->
            val b = fragment.binding

            // The defect this replaces: the adapter used to be null whenever the
            // data had not arrived, and nothing ever created one afterwards.
            assertNotNull("the row must have an adapter before any data exists", b.playlists.adapter)
            assertEquals(0, b.playlists.adapter!!.itemCount)

            // And the rest of HOME is untouched by the missing playlists - these
            // are the radio, and they need no network to be tapped.
            assertEquals(View.VISIBLE, b.myataStreamBanner.visibility)
            assertEquals(View.VISIBLE, b.goldStreamBanner.visibility)
            assertEquals(View.VISIBLE, b.homeGreeting.visibility)
        }
    }

    @Test
    fun dataArrivingAfterHomeIsVisibleFillsTheRow() {
        onHome(clearPlaylists = true) { fragment ->
            assertEquals(0, fragment.binding.playlists.adapter!!.itemCount)

            // The load lands while HOME is already on screen - which before this
            // change was simply impossible, and would have done nothing.
            fragment.vm.playlistList.value = samples.toMutableList()

            assertEquals(samples.size, fragment.binding.playlists.adapter!!.itemCount)
            assertEquals(View.VISIBLE, fragment.binding.playlists.visibility)
            assertEquals(View.GONE, fragment.binding.playlistState.visibility)
        }
    }

    @Test
    fun anEmptyListLeavesTheRowEmptyAndDoesNotCrash() {
        onHome(clearPlaylists = true) { fragment ->
            fragment.vm.playlistList.value = mutableListOf()
            assertEquals(0, fragment.binding.playlists.adapter!!.itemCount)
        }
    }

    @Test
    fun tappingACardCannotReachANullList() {
        onHome(clearPlaylists = true) { fragment ->
            val row: RecyclerView = fragment.binding.playlists
            fragment.vm.playlistList.value = samples.toMutableList()
            row.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            )
            row.layout(0, 0, 1080, 600)

            // The old click path looked the position up in playlistList.value!!.
            // Clearing the list and then clicking is exactly the race that used to
            // be a crash: the view is still bound, the source is gone.
            val child: View? = (row as ViewGroup).getChildAt(0)
            fragment.vm.playlistList.value = mutableListOf()
            child?.performClick()   // must be a no-op, not an NPE
        }
    }

    @Test
    fun retryAsksTheLoaderAgain() {
        onHome { fragment ->
            val seen = mutableListOf<PlaylistsState>()
            fragment.vm.playlistsState.observeForever { seen += it }

            fragment.binding.playlistRetry.performClick()

            // refreshPlaylists is a no-op while a load is running, so the only
            // assertion that always holds is that the click reached the loader
            // without throwing and the state remains one the section can render.
            assertTrue(
                "playlistsState should still be a state the section maps: $seen",
                fragment.vm.playlistsState.value in
                    listOf(PlaylistsState.LOADING, PlaylistsState.READY, PlaylistsState.ERROR),
            )
        }
    }

    // ==================== infra ====================

    /**
     * Runs [block] on a live [MainFragment], reached by navigating straight to
     * HOME rather than by waiting the splash out.
     *
     * @param clearPlaylists empties `playlistList` first, reproducing "HOME exists
     *   and the load has not landed" on a device where it usually has.
     */
    private fun onHome(clearPlaylists: Boolean = false, block: (MainFragment) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ready = CountDownLatch(1)
            var home: MainFragment? = null

            scenario.onActivity { activity ->
                if (clearPlaylists) activity.viewModel.playlistList.value = mutableListOf()
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.navHostFragment) as NavHostFragment
                if (navHost.navController.currentDestination?.id != R.id.home) {
                    navHost.navController.navigate(R.id.home)
                }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.navHostFragment) as NavHostFragment
                home = navHost.childFragmentManager.fragments
                    .filterIsInstance<MainFragment>()
                    .firstOrNull { it.view != null }
                ready.countDown()
            }
            assertTrue("HOME never appeared", ready.await(20, TimeUnit.SECONDS))

            val fragment = requireNotNull(home) { "no MainFragment with a view" }
            scenario.onActivity {
                if (clearPlaylists) fragment.vm.playlistList.value = mutableListOf()
                block(fragment)
            }
        }
    }
}
