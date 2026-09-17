package com.example.musicplayerapp

import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.navigation.fragment.NavHostFragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.adapters.PlayerHistoryAdapter
import com.example.musicplayerapp.data.HistoryRepository
import com.example.musicplayerapp.data.HistoryResult
import com.example.musicplayerapp.data.HistoryTrack
import com.example.musicplayerapp.data.report.ReportConfig
import com.example.musicplayerapp.fragments.MyataStreamFragment
import com.example.musicplayerapp.fragments.PlayerFragment
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

/**
 * The PLAYER's inline История эфира does not show «Показать ещё» before it has
 * rows to show it under.
 *
 * The rows reach the list through ListAdapter's diff, which runs off the main
 * thread and commits later. The section used to show the list and the button as
 * soon as the ViewModel published, so on first load the button stood under an
 * empty section for most of a second. The diff is held here with
 * [PlayerHistoryAdapter.diffExecutorForTest], so "before the commit" is a state
 * the test can sit in rather than a race it has to win.
 */
@RunWith(AndroidJUnit4::class)
class PlayerHistoryCommitTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** More than the three rows shown first, so there is something to reveal. */
    private val history = (5 downTo 1).map {
        HistoryTrack("COMMIT TEST ARTIST $it", "COMMIT TEST TITLE $it", it.toLong(), "10:0$it")
    }

    private val diffs = HeldExecutor()

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
        PlayerHistoryAdapter.diffExecutorForTest = diffs
    }

    @After
    fun tearDown() {
        diffs.release()
        PlayerHistoryAdapter.diffExecutorForTest = null
        HistoryRepository.sourceOverrideForTest = null
        ReportConfig.endpointOverrideForTest = null
    }

    @Test
    fun show_more_stays_hidden_until_the_rows_are_committed() {
        val gate = CompletableDeferred<HistoryResult>()
        HistoryRepository.sourceOverrideForTest = { _, _ -> gate.await() }
        try {
            withMainActivity {
                openPlayer()
                await("the inline section loading") { page(it)?.binding?.historyLoading?.isVisible() == true }

                gate.complete(HistoryResult.Loaded(history))
                await("the history published") { published(it, history.size) }
                await("the rows' diff held") { diffs.pending > 0 }

                // Published, not committed: nothing of the populated state is up.
                repeat(2) {
                    on { activity ->
                        val b = page(activity)!!.binding
                        assertEquals("the list holds no rows yet", 0, b.historyList.adapter!!.itemCount)
                        assertEquals("«Показать ещё» before any row is committed",
                            View.GONE, b.historyShowMore.visibility)
                        assertTrue("the list is not shown empty", !b.historyList.isVisible())
                        assertTrue("loading stays up until the rows land", b.historyLoading.isVisible())
                    }
                    Thread.sleep(300)
                }

                diffs.release()
                await("«Показать ещё» after the commit") { page(it)?.binding?.historyShowMore?.isVisible() == true }
                on { activity ->
                    val b = page(activity)!!.binding
                    assertEquals(3, b.historyList.adapter!!.itemCount)
                    assertTrue(b.historyList.isVisible())
                    assertTrue(!b.historyLoading.isVisible())
                }
            }
        } finally {
            gate.complete(HistoryResult.Loaded(emptyList()))
        }
    }

    /**
     * A commit from a render the page has moved past must not bring its state
     * back. Here the page's view is recreated in between: the populated render's
     * diff is still held when the PLAYER is left, the history is empty by the time
     * it comes back, and only then does the old diff land.
     */
    @Test
    fun an_obsolete_commit_does_not_reshow_rows_over_a_newer_state() {
        // Gated like the test above, so the page is up and loading - with an
        // adapter that has committed once already - before the rows arrive.
        val gate = CompletableDeferred<HistoryResult>()
        HistoryRepository.sourceOverrideForTest = { _, _ -> gate.await() }
        withMainActivity {
            openPlayer()
            await("the inline section loading") { page(it)?.binding?.historyLoading?.isVisible() == true }
            HistoryRepository.sourceOverrideForTest = { _, _ -> HistoryResult.Loaded(history) }
            gate.complete(HistoryResult.Loaded(history))
            await("the history published") { published(it, history.size) }
            await("the rows' diff held") { diffs.pending > 0 }
            var pageBefore: MyataStreamFragment? = null
            on {
                pageBefore = page(it)
                assertEquals(View.GONE, pageBefore!!.binding.historyShowMore.visibility)
            }

            // The history screen's own request is held too. The PLAYER's view only
            // goes once the push has finished animating, and an answer that lands
            // before then is rendered by the still-live page - a newer submit that
            // supersedes the held diff, and a test that proves nothing.
            val emptied = CompletableDeferred<HistoryResult>()
            HistoryRepository.sourceOverrideForTest = { _, _ -> emptied.await() }
            on { activity ->
                activity.findViewById<View>(R.id.player_header_action).performClick()
                playerFragment(activity)!!.overflowContentForTest()!!
                    .findViewById<View>(R.id.player_overflow_history).performClick()
            }
            await("history") { it.currentDestinationIdOrNull() == R.id.broadcast_history }
            await("the player's page view to be destroyed") { pageBefore!!.view == null }

            HistoryRepository.sourceOverrideForTest = { _, _ -> HistoryResult.Loaded(emptyList()) }
            emptied.complete(HistoryResult.Loaded(emptyList()))
            await("the history emptied") { published(it, 0) }

            on { it.onBackPressedDispatcher.onBackPressed() }
            await("back to the player") { it.currentDestinationIdOrNull() == R.id.player }
            await("the returned page empty") { activity ->
                page(activity)?.takeIf { it.view != null }?.binding?.historyEmpty?.isVisible() == true
            }

            on { assertTrue("the same page, with a new view", page(it) === pageBefore) }
            assertTrue("the populated render's diff is still held", diffs.pending > 0)

            diffs.release()
            // Let the released diffs post their commits and a frame or two pass.
            Thread.sleep(600)
            on { activity ->
                val b = page(activity)!!.binding
                assertEquals("an obsolete commit re-showed «Показать ещё»",
                    View.GONE, b.historyShowMore.visibility)
                assertTrue("an obsolete commit re-showed the list", !b.historyList.isVisible())
                assertTrue("the newer empty state is still up", b.historyEmpty.isVisible())
            }
        }
    }

    // ==================== harness ====================

    /** Queues diff work until [release]; after that it runs it straight away. */
    private class HeldExecutor : Executor {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        @Volatile private var held = true

        val pending: Int get() = queue.size

        override fun execute(command: Runnable) {
            if (held) queue.add(command) else command.run()
        }

        fun release() {
            held = false
            while (true) (queue.poll() ?: break).run()
        }
    }

    private fun View.isVisible() = visibility == View.VISIBLE

    private fun published(activity: MainActivity, size: Int): Boolean {
        val vm = activity.viewModel
        return vm.historyLoading.value == false && vm.historyTracks.value?.size == size
    }

    private fun openPlayer() {
        await("HOME") { it.currentDestinationIdOrNull() == R.id.home }
        on { it.findViewById<View>(R.id.nav_item_player).performClick() }
        await("PLAYER") { it.currentDestinationIdOrNull() == R.id.player }
    }

    private fun playerFragment(activity: MainActivity): PlayerFragment? =
        (activity.supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment)
            ?.childFragmentManager?.fragments?.filterIsInstance<PlayerFragment>()?.firstOrNull()

    /** The pager page showing the current stream. */
    private fun page(activity: MainActivity): MyataStreamFragment? {
        val stream = activity.viewModel.currentStreamLive.value ?: "myata"
        return playerFragment(activity)?.childFragmentManager?.fragments
            ?.filterIsInstance<MyataStreamFragment>()
            ?.firstOrNull { it.stream == stream && it.view != null }
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
