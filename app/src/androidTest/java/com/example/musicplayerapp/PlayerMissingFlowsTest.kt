package com.example.musicplayerapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.HistoryRepository
import com.example.musicplayerapp.data.HistoryResult
import com.example.musicplayerapp.data.HistoryTrack
import com.example.musicplayerapp.data.PlayerState
import com.example.musicplayerapp.data.report.ReportConfig
import com.example.musicplayerapp.fragments.BroadcastHistoryFragment
import com.example.musicplayerapp.fragments.CollectionTrackSheet
import com.example.musicplayerapp.fragments.FindTrackSheet
import com.example.musicplayerapp.fragments.PlayerFragment
import com.example.musicplayerapp.ui.NavScreen
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G4b end to end on the running app: PLAYER > `Найти трек`, PLAYER > `История эфира`,
 * the full-screen history's four states, and a history row's action.
 *
 * The network is replaced where it has to be and nowhere else:
 *
 *  - [HistoryRepository.sourceOverrideForTest] answers the history request, because
 *    loading, empty, error and retry are states of the network and waiting for the
 *    real station to produce each one would be a test of the station;
 *  - the now-playing pair is written into the ViewModel **in the same main-thread
 *    block that taps the menu**, so the 15s metadata poll cannot land in between and
 *    make the assertion about somebody else's track;
 *  - a streaming-service tap is caught by an [Instrumentation.ActivityMonitor], so
 *    the URL the helper built is asserted and no browser opens.
 *
 * Every wait is a poll over `runOnMainSync`, never an idle wait - see the
 * instrumentation-idle-wait note in AuthTestDoubles.
 */
@RunWith(AndroidJUnit4::class)
class PlayerMissingFlowsTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val configured = "https://example.invalid/report"

    private val history = listOf(
        HistoryTrack("TWENTY ONE PILOTS", "CITY WALLS", 3L, "10:36"),
        HistoryTrack("FRANC MOODY", "A LITTLE SOMETHING FOR THE WEEKEND AND A LITTLE MORE", 2L, "10:27"),
        HistoryTrack("FOALS", "what went down", 1L, "10:22"),
    )

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
        ReportConfig.endpointOverrideForTest = configured
        answer(HistoryResult.Loaded(history))
    }

    @After
    fun tearDown() {
        ReportConfig.endpointOverrideForTest = null
        HistoryRepository.sourceOverrideForTest = null
    }

    // ==================== PLAYER > История эфира ====================

    @Test
    fun the_history_row_opens_the_full_screen_and_back_returns_to_the_player() {
        withMainActivity {
            openHistoryFromTheMenu()

            on { activity ->
                assertEquals(R.id.broadcast_history, activity.currentDestinationIdOrNull())
                assertEquals("История эфира", activity.findViewById<TextView>(R.id.history_title).text.toString())
                assertEquals("no bottom bar over a pushed frame", View.GONE,
                    activity.findViewById<View>(R.id.bottomNavView).visibility)
                // The NavController-derived key (G4a): pushed, so no Mini Player.
                assertEquals(NavScreen.PUSHED, activity.viewModel.currentFragmentLiveData.value)
                assertFalse("the Mini Player must not be over the history",
                    activity.binding.miniPlayer.root.visibility == View.VISIBLE)
            }
            awaitRows(history.size)

            tap(R.id.history_back)
            await("back to the player") { it.currentDestinationIdOrNull() == R.id.player }
            on { activity ->
                assertEquals(NavScreen.PLAYER, activity.viewModel.currentFragmentLiveData.value)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.bottomNavView).visibility)
            }
        }
    }

    /**
     * Two taps that both land - the second on a row whose popup has already closed -
     * push one history, not two. Asserted by behaviour: one Back returns to the
     * player, which it would not if a second copy were stacked.
     */
    @Test
    fun repeated_taps_do_not_stack_a_second_history() {
        withMainActivity {
            openPlayerAndSettle()
            on { it.findViewById<View>(R.id.player_header_action).performClick() }
            val row = awaitMenuRow(R.id.player_overflow_history)
            instrumentation.runOnMainSync {
                row.performClick()
                row.performClick()
            }
            await("history") { it.currentDestinationIdOrNull() == R.id.broadcast_history }
            instrumentation.runOnMainSync { resumedMainActivity().onBackPressedDispatcher.onBackPressed() }
            await("one Back returns to the player") { it.currentDestinationIdOrNull() == R.id.player }
        }
    }

    // ==================== the four states ====================

    @Test
    fun loading_is_the_static_skeleton_then_the_content() {
        val gate = CompletableDeferred<HistoryResult>()
        HistoryRepository.sourceOverrideForTest = { _, _ -> gate.await() }
        try {
            withMainActivity {
                openHistoryFromTheMenu()
                awaitVisible(R.id.history_skeleton)
                on { activity ->
                    assertEquals(8, activity.findViewById<ViewGroup>(R.id.history_skeleton).childCount)
                    for (id in listOf(R.id.history_list, R.id.history_empty, R.id.history_error)) {
                        assertEquals(View.GONE, activity.findViewById<View>(id).visibility)
                    }
                    val screen = activity.findViewById<View>(R.id.history_root)
                    assertFalse("no platform spinner - it becomes a refresh arrow with animations off",
                        descendants(screen).any { it is ProgressBar })
                }
                gate.complete(HistoryResult.Loaded(history))
                awaitRows(history.size)
                on { assertEquals(View.GONE, it.findViewById<View>(R.id.history_skeleton).visibility) }
            }
        } finally {
            gate.complete(HistoryResult.Loaded(emptyList()))
        }
    }

    @Test
    fun an_empty_answer_is_history_empty_and_refresh_asks_again() {
        answer(HistoryResult.Loaded(emptyList()))
        withMainActivity {
            openHistoryFromTheMenu()
            awaitVisible(R.id.history_empty)
            on { activity ->
                assertEquals("Пока нет истории", activity.string(R.id.history_empty_title))
                assertEquals("Треки появятся здесь, как только\nначнётся эфир.", activity.string(R.id.history_empty_body))
                assertEquals("Обновить", activity.string(R.id.history_empty_action))
                assertEquals(View.GONE, activity.findViewById<View>(R.id.history_error).visibility)
            }
            answer(HistoryResult.Loaded(history))
            tap(R.id.history_empty_action)
            awaitRows(history.size)
        }
    }

    @Test
    fun a_failure_is_history_error_and_retry_recovers() {
        answer(HistoryResult.Failed("test"))
        withMainActivity {
            openHistoryFromTheMenu()
            awaitVisible(R.id.history_error)
            on { activity ->
                assertEquals("Не удалось загрузить", activity.string(R.id.history_error_title))
                assertEquals("Проверьте подключение\nи попробуйте ещё раз.", activity.string(R.id.history_error_body))
                assertEquals("Повторить", activity.string(R.id.history_error_action))
                assertEquals(View.GONE, activity.findViewById<View>(R.id.history_empty).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.history_list).visibility)
            }
            answer(HistoryResult.Loaded(history))
            tap(R.id.history_error_action)
            awaitRows(history.size)
            on { assertEquals(View.GONE, it.findViewById<View>(R.id.history_error).visibility) }
        }
    }

    /** history-error's note: "Retry re-requests; it does not clear a cached list." */
    @Test
    fun a_failed_refresh_keeps_the_rows_already_up() {
        withMainActivity {
            openHistoryFromTheMenu()
            awaitRows(history.size)
            answer(HistoryResult.Failed("test"))
            on { it.viewModel.loadHistory() }
            await("the refresh to finish") { it.viewModel.historyLoading.value == false && it.viewModel.historyFailed.value == true }
            on { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.history_list).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.history_error).visibility)
                assertEquals(history.size, activity.viewModel.historyTracks.value?.size)
            }
        }
    }

    // ==================== a history row's action ====================

    @Test
    fun a_history_row_action_opens_find_track_for_that_row() {
        withMainActivity {
            openHistoryFromTheMenu()
            awaitRows(history.size)

            on { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.history_list)
                list.getChildAt(1).findViewById<View>(R.id.btn_row_action).performClick()
            }
            val sheet = awaitSheet { it.historyFragment()?.childFragmentManager }
            on {
                val view = sheet.requireView()
                assertEquals("A LITTLE SOMETHING FOR THE WEEKEND AND A LITTLE MORE",
                    view.findViewById<TextView>(R.id.sheet_title).text.toString())
                assertEquals("FRANC MOODY", view.findViewById<TextView>(R.id.sheet_subtitle).text.toString())
                assertFourServicesAndNoDeleteRow(view)
            }
        }
    }

    // ==================== PLAYER > Найти трек ====================

    @Test
    fun find_track_opens_the_sheet_for_the_current_track() {
        withMainActivity {
            openPlayerAndSettle()
            // Written and tapped in one main-thread block: the query is taken at the
            // tap, so no metadata poll can land between the two.
            on { activity ->
                setNowPlaying(activity, artist = "MUSE", song = "CRYOGEN")
                activity.findViewById<View>(R.id.player_header_action).performClick()
                val menu = activity.playerFragment()!!.overflowContentForTest()!!
                val row = menu.findViewById<View>(R.id.player_overflow_find_track)
                assertTrue("a known track makes the row available", row.isEnabled)
                assertEquals(1f, row.alpha)
                row.performClick()
            }
            val sheet = awaitSheet { it.playerFragment()?.childFragmentManager }
            on { activity ->
                assertNull("the Collection sheet must not open from the player",
                    activity.playerFragment()!!.childFragmentManager.findFragmentByTag(CollectionTrackSheet.TAG))
                val view = sheet.requireView()
                assertEquals("CRYOGEN", view.findViewById<TextView>(R.id.sheet_title).text.toString())
                assertEquals("MUSE", view.findViewById<TextView>(R.id.sheet_subtitle).text.toString())
                assertFourServicesAndNoDeleteRow(view)
            }

            // The sheet is a snapshot (owner decision): the stream moves on while
            // it is open, and neither the heading nor the searches follow it.
            on { setNowPlaying(it, artist = "FOALS", song = "NORTHERN LINE") }
            Thread.sleep(400)
            on {
                val view = sheet.requireView()
                assertEquals("CRYOGEN", view.findViewById<TextView>(R.id.sheet_title).text.toString())
                assertEquals("MUSE", view.findViewById<TextView>(R.id.sheet_subtitle).text.toString())
            }

            // A service tap goes out through MusicSearchHelper, unchanged: the same
            // https search, which an installed app or the browser answers. The
            // filter IS the assertion - scheme, host and the decoded path of
            // `https://open.spotify.com/search/MUSE%20-%20CRYOGEN` - because a
            // blocking monitor records a hit but not the intent, and the hook that
            // would hand the intent over does not exist on API 24.
            val monitor = instrumentation.addMonitor(
                IntentFilter(Intent.ACTION_VIEW).apply {
                    addDataScheme("https")
                    addDataAuthority("open.spotify.com", null)
                    addDataPath("/search/MUSE - CRYOGEN", android.os.PatternMatcher.PATTERN_LITERAL)
                },
                Instrumentation.ActivityResult(Activity.RESULT_OK, null),
                true,
            )
            try {
                on { sheet.requireView().findViewById<View>(R.id.row_spotify).performClick() }
                val deadline = System.currentTimeMillis() + 5_000
                while (monitor.hits == 0 && System.currentTimeMillis() < deadline) Thread.sleep(25)
                assertEquals(
                    "the Spotify row must hand off exactly MusicSearchHelper's search for the sheet's " +
                        "track - the one it showed, not the one playing since",
                    1, monitor.hits,
                )
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            await("the sheet to close after a choice") {
                it.playerFragment()?.childFragmentManager?.findFragmentByTag(FindTrackSheet.TAG) == null
            }
        }
    }

    /**
     * «YouTube Music» opens YouTube Music (owner decision): a search on
     * `music.youtube.com`, the host the YouTube Music app claims - not youtube.com.
     */
    @Test
    fun the_youtube_music_row_searches_youtube_music() {
        withMainActivity {
            openPlayerAndSettle()
            on { activity ->
                setNowPlaying(activity, artist = "MUSE", song = "CRYOGEN")
                activity.findViewById<View>(R.id.player_header_action).performClick()
                activity.playerFragment()!!.overflowContentForTest()!!
                    .findViewById<View>(R.id.player_overflow_find_track).performClick()
            }
            val sheet = awaitSheet { it.playerFragment()?.childFragmentManager }
            val monitor = instrumentation.addMonitor(
                IntentFilter(Intent.ACTION_VIEW).apply {
                    addDataScheme("https")
                    addDataAuthority("music.youtube.com", null)
                    addDataPath("/search", android.os.PatternMatcher.PATTERN_LITERAL)
                },
                Instrumentation.ActivityResult(Activity.RESULT_OK, null),
                true,
            )
            try {
                on { sheet.requireView().findViewById<View>(R.id.row_youtube).performClick() }
                val deadline = System.currentTimeMillis() + 5_000
                while (monitor.hits == 0 && System.currentTimeMillis() < deadline) Thread.sleep(25)
                assertEquals("the YouTube Music row must search music.youtube.com", 1, monitor.hits)
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }
    }

    /** "Metadata unavailable": the placeholder pair is not a track. */
    @Test
    fun find_track_is_unavailable_while_the_placeholder_is_up() {
        withMainActivity {
            openPlayerAndSettle()
            on { activity ->
                setNowPlaying(
                    activity,
                    artist = activity.getString(R.string.slogan_placeholder),
                    song = activity.getString(R.string.brand_name),
                )
                activity.findViewById<View>(R.id.player_header_action).performClick()
                val menu = activity.playerFragment()!!.overflowContentForTest()!!
                val row = menu.findViewById<View>(R.id.player_overflow_find_track)
                // Still drawn, in its frozen place - four rows either way.
                assertEquals(4, (menu as ViewGroup).childCount)
                assertEquals(View.VISIBLE, row.visibility)
                assertFalse("nothing to search - the row must not be available", row.isEnabled)
                assertFalse("a disabled row must not be clickable", row.isClickable)
                assertFalse("a disabled row must carry no listener", row.hasOnClickListeners())
                assertFalse("a disabled row must not take focus", row.isFocusable)
                assertTrue(row.alpha < 1f)
                // A forced click has nothing to call.
                row.performClick()
            }
            Thread.sleep(500)
            on { activity ->
                assertNull(activity.playerFragment()!!.childFragmentManager.findFragmentByTag(FindTrackSheet.TAG))
            }
        }
    }

    // ==================== harness ====================

    private fun answer(result: HistoryResult) {
        HistoryRepository.sourceOverrideForTest = { _, _ -> result }
    }

    private fun setNowPlaying(activity: MainActivity, artist: String, song: String) {
        val vm = activity.viewModel
        for (state in listOf(vm.currentMyataState, vm.currentGoldState, vm.currentXtraState)) {
            state.value = PlayerState(artist = artist, song = song, img = null)
        }
    }

    private fun assertFourServicesAndNoDeleteRow(view: View) {
        assertEquals(
            listOf("Spotify", "Apple Music", "YouTube Music", "Яндекс Музыка"),
            listOf(R.id.row_spotify, R.id.row_apple_music, R.id.row_youtube, R.id.row_yandex).map { id ->
                val row = view.findViewById<ViewGroup>(id)
                assertNotNull(row)
                (0 until row.childCount).mapNotNull { row.getChildAt(it) as? TextView }.first().text.toString()
            },
        )
        assertNull("no `Удалить из коллекции` outside Collection", view.findViewById<View>(R.id.row_remove))
        assertNull("no divider outside Collection", view.findViewById<View>(R.id.sheet_divider))
    }

    private fun openHistoryFromTheMenu() {
        openPlayerAndSettle()
        on { it.findViewById<View>(R.id.player_header_action).performClick() }
        val row = awaitMenuRow(R.id.player_overflow_history)
        instrumentation.runOnMainSync { row.performClick() }
        await("history") { it.currentDestinationIdOrNull() == R.id.broadcast_history }
    }

    private fun openPlayerAndSettle() {
        await("HOME") { it.currentDestinationIdOrNull() == R.id.home }
        on { it.findViewById<View>(R.id.nav_item_player).performClick() }
        await("PLAYER") { it.currentDestinationIdOrNull() == R.id.player }
    }

    private fun awaitMenuRow(id: Int): View {
        var found: View? = null
        await("the overflow row") { activity ->
            found = activity.playerFragment()?.overflowContentForTest()?.findViewById(id)
            (found?.width ?: 0) > 0
        }
        return found!!
    }

    private fun awaitVisible(id: Int) =
        await("view ${instrumentation.targetContext.resources.getResourceEntryName(id)}") {
            it.findViewById<View>(id)?.visibility == View.VISIBLE
        }

    private fun awaitRows(n: Int) = await("$n history rows") { activity ->
        val list = activity.findViewById<RecyclerView>(R.id.history_list)
        list != null && list.visibility == View.VISIBLE &&
            (0 until list.childCount).count { list.getChildAt(it).findViewById<View>(R.id.btn_row_action) != null } >= n
    }

    private fun awaitSheet(fm: (MainActivity) -> androidx.fragment.app.FragmentManager?): FindTrackSheet {
        var sheet: FindTrackSheet? = null
        await("the find-track sheet") { activity ->
            sheet = fm(activity)?.findFragmentByTag(FindTrackSheet.TAG) as? FindTrackSheet
            (sheet?.view?.findViewById<View>(R.id.row_yandex)?.width ?: 0) > 0
        }
        return sheet!!
    }

    private fun MainActivity.navHostChildren(): List<Fragment> =
        (supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment)
            ?.childFragmentManager?.fragments.orEmpty()

    private fun MainActivity.playerFragment(): PlayerFragment? =
        navHostChildren().filterIsInstance<PlayerFragment>().firstOrNull()

    private fun MainActivity.historyFragment(): BroadcastHistoryFragment? =
        navHostChildren().filterIsInstance<BroadcastHistoryFragment>().firstOrNull()

    private fun MainActivity.string(id: Int) = findViewById<TextView>(id).text.toString()

    private fun descendants(v: View): List<View> =
        listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { descendants(g.getChildAt(it)) } } ?: emptyList())

    private fun tap(id: Int) {
        on { it.findViewById<View>(id).performClick() }
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
