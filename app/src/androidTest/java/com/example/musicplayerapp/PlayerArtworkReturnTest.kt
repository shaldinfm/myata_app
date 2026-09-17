package com.example.musicplayerapp

import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.HistoryRepository
import com.example.musicplayerapp.data.HistoryResult
import com.example.musicplayerapp.data.PlayerState
import com.example.musicplayerapp.data.report.ReportConfig
import com.example.musicplayerapp.fragments.MyataStreamFragment
import com.example.musicplayerapp.fragments.PlayerFragment
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The PLAYER's cover survives a push and a Back.
 *
 * `Сообщить о проблеме` and `История эфира` are pushed from the PLAYER, so the
 * PLAYER's view is destroyed and its stream pages keep their fragment instances -
 * only their views are recreated, and a recreated view inflates with the plate.
 * The page used to remember the cover URL on the fragment, so the new view was
 * told its cover was already up and kept the plate until the next track.
 *
 * Asserted on the running app, with the cover a local resource so no network is
 * involved. The now-playing state is re-written **in the same main-thread block**
 * as each tap, so the 15s metadata poll cannot land in between and hand the page a
 * different URL - which would load a cover for the wrong reason and pass.
 */
@RunWith(AndroidJUnit4::class)
class PlayerArtworkReturnTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) {
            val context = instrumentation.targetContext
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
                ),
            ).use { it.readBytes() }
        }
        ReportConfig.endpointOverrideForTest = "https://example.invalid/report"
        HistoryRepository.sourceOverrideForTest = { _, _ -> HistoryResult.Loaded(emptyList()) }
    }

    @After
    fun tearDown() {
        ReportConfig.endpointOverrideForTest = null
        HistoryRepository.sourceOverrideForTest = null
    }

    @Test
    fun the_cover_is_back_immediately_after_report_problem_and_back() {
        coverSurvives(R.id.player_overflow_report_problem, R.id.report_problem)
    }

    @Test
    fun the_cover_is_back_immediately_after_history_and_back() {
        coverSurvives(R.id.player_overflow_history, R.id.broadcast_history)
    }

    private fun coverSurvives(menuRow: Int, destination: Int) {
        withMainActivity {
            await("HOME") { it.currentDestinationIdOrNull() == R.id.home }
            on { it.findViewById<View>(R.id.nav_item_player).performClick() }
            await("PLAYER") { it.currentDestinationIdOrNull() == R.id.player }

            on { seed(it) }
            await("the seeded cover on the player") { activity ->
                page(activity)?.takeIf { it.view != null }?.let { showsCover(it.binding.photo) } == true
            }

            var pageBefore: MyataStreamFragment? = null
            var photoBefore: ImageView? = null
            on { activity ->
                seed(activity)
                pageBefore = page(activity)
                photoBefore = pageBefore!!.binding.photo
                assertTrue("precondition: the cover is up before leaving", showsCover(photoBefore!!))

                activity.findViewById<View>(R.id.player_header_action).performClick()
                playerFragment(activity)!!.overflowContentForTest()!!
                    .findViewById<View>(menuRow).performClick()
            }
            await("the pushed screen") { it.currentDestinationIdOrNull() == destination }
            // The destination changes before the fragment transaction runs. Back
            // before it has would cancel the push with the PLAYER's view never
            // destroyed - and never exercise what this test is about.
            await("the player's page view to be destroyed") { pageBefore!!.view == null }

            on { activity ->
                seed(activity)
                activity.onBackPressedDispatcher.onBackPressed()
            }
            await("back to the player") { it.currentDestinationIdOrNull() == R.id.player }

            // Immediately: well inside the time a track takes to change, and a
            // cover already decoded once comes back from memory.
            await("the same cover on the returned player", timeoutMs = 2_000) { activity ->
                val page = page(activity)
                page?.view != null && seeded(activity) && showsCover(page!!.binding.photo)
            }

            on { activity ->
                val pageAfter = page(activity)
                // What made this a regression: the page is the same fragment, and
                // only its view is new.
                assertSame("the stream page is kept across the push", pageBefore, pageAfter)
                assertNotSame("its view is recreated", photoBefore, pageAfter!!.binding.photo)
            }
        }
    }

    // ==================== harness ====================

    private val coverUrl: String
        get() = "android.resource://${instrumentation.targetContext.packageName}/${R.drawable.zaglushka_1_img}"

    private fun seed(activity: MainActivity) {
        val vm = activity.viewModel
        for (state in listOf(vm.currentMyataState, vm.currentGoldState, vm.currentXtraState)) {
            state.value = PlayerState(artist = "MUSE", song = "CRYOGEN", img = coverUrl)
        }
    }

    private fun seeded(activity: MainActivity): Boolean {
        val vm = activity.viewModel
        val state = when (vm.currentStreamLive.value) {
            "gold" -> vm.currentGoldState.value
            "myata_hits" -> vm.currentXtraState.value
            else -> vm.currentMyataState.value
        }
        return state?.img == coverUrl
    }

    /** A drawable that is neither nothing nor the branded plate. */
    private fun showsCover(photo: ImageView): Boolean {
        val drawn = photo.drawable ?: return false
        val plate = ContextCompat.getDrawable(photo.context, R.drawable.zaglushka_logo)?.constantState
        return drawn.constantState != plate
    }

    private fun playerFragment(activity: MainActivity): PlayerFragment? =
        (activity.supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment)
            ?.childFragmentManager?.fragments?.filterIsInstance<PlayerFragment>()?.firstOrNull()

    /** The pager page showing the current stream. */
    private fun page(activity: MainActivity): MyataStreamFragment? {
        val stream = activity.viewModel.currentStreamLive.value ?: "myata"
        return playerFragment(activity)?.childFragmentManager?.fragments
            ?.filterIsInstance<MyataStreamFragment>()
            ?.firstOrNull { it.stream == stream }
    }

    private fun on(block: (MainActivity) -> Unit) {
        instrumentation.runOnMainSync { block(resumedMainActivity()) }
    }

    private fun await(what: String, timeoutMs: Long = 15_000, check: (MainActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            runCatching { instrumentation.runOnMainSync { ok = check(resumedMainActivity()) } }
            if (ok) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out after ${timeoutMs}ms waiting for $what")
    }
}
