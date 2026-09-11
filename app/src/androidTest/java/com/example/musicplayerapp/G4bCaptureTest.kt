package com.example.musicplayerapp

import android.view.View
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.HistoryRepository
import com.example.musicplayerapp.data.HistoryResult
import com.example.musicplayerapp.data.HistoryTrack
import com.example.musicplayerapp.data.PlayerState
import com.example.musicplayerapp.data.ThemeStore
import com.example.musicplayerapp.data.report.ReportConfig
import com.example.musicplayerapp.fragments.BroadcastHistoryFragment
import com.example.musicplayerapp.fragments.FindTrackSheet
import com.example.musicplayerapp.fragments.PlayerFragment
import com.example.musicplayerapp.ui.settings.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screenshots of every G4b surface, in both themes, for the owner's visual review.
 *
 * ## Why a test
 *
 * Three of the four History frames - loading, empty, error - are states of the
 * network, and the live station produces none of them on demand. The suite already
 * has the seam that does ([HistoryRepository.sourceOverrideForTest]); a debug-only
 * switch in production source to make a picture easier is what
 * ProfileAuthenticatedCaptureTest already declined to add, for the same reason.
 *
 * The content frame is shot twice: once on the frozen frame's own eight rows (two of
 * them wrapping, as the frame wraps them) for a like-for-like comparison, and once on
 * whatever the live endpoint answers.
 *
 * ## Opt-in
 *
 * ```
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.captureG4b=true \
 *   "-Pandroid.testInstrumentationRunnerArguments.class=com.example.musicplayerapp.G4bCaptureTest"
 * adb pull /sdcard/Pictures/g4b
 * ```
 *
 * PNGs go to shared storage by `screencap`, which runs as shell: the app's own files
 * go with the package when the run uninstalls it.
 */
@RunWith(AndroidJUnit4::class)
class G4bCaptureTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val dir = "/sdcard/Pictures/g4b"

    /** history-content 2523:23, row for row. */
    private val frozenRows = listOf(
        HistoryTrack("MUSE", "CRYOGEN", 8L, "10:45"),
        HistoryTrack("КРАСНОЗНАМЁННАЯ ДИВИЗИЯ ИМЕНИ МОЕЙ БАБУШКИ", "ФАК Ю", 7L, "10:41"),
        HistoryTrack("TWENTY ONE PILOTS", "CITY WALLS", 6L, "10:36"),
        HistoryTrack("TWO DOOR CINEMA CLUB", "WHAT YOU KNOW", 5L, "10:31"),
        HistoryTrack("FRANC MOODY", "A LITTLE SOMETHING FOR THE WEEKEND", 4L, "10:27"),
        HistoryTrack("FOALS", "what went down", 3L, "10:22"),
        HistoryTrack("ALT-J", "breezeblocks", 2L, "10:18"),
        HistoryTrack("KAYTRANADA", "SLOW BURN", 1L, "10:13"),
    )

    @Before
    fun open() {
        assumeTrue(
            "opt in with -Pandroid.testInstrumentationRunnerArguments.captureG4b=true",
            InstrumentationRegistry.getArguments().getString("captureG4b")
                ?.trim().equals("true", ignoreCase = true),
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        }
        shell("mkdir -p $dir")
        ReportConfig.endpointOverrideForTest = "https://example.invalid/report"
    }

    @After
    fun close() {
        ThemeStore.clearForTest(context)
        HistoryRepository.sourceOverrideForTest = null
        ReportConfig.endpointOverrideForTest = null
    }

    @Test
    fun capturesEveryG4bSurfaceInBothThemes() {
        val api = android.os.Build.VERSION.SDK_INT
        for ((mode, theme) in listOf(ThemeMode.LIGHT to "light", ThemeMode.DARK to "dark")) {
            ThemeStore.write(context, mode)
            val tag = "api$api-$theme"

            // ---- the menu, Find Track from the player, the content frame ----
            HistoryRepository.sourceOverrideForTest = { _, _ -> HistoryResult.Loaded(frozenRows) }
            session {
                openPlayer()
                on { a ->
                    nowPlaying(a, "MUSE", "CRYOGEN")
                    a.findViewById<View>(R.id.player_header_action).performClick()
                }
                awaitMenu()
                shot("$tag-01-menu")

                on { a -> a.player()!!.overflowContentForTest()!!.findViewById<View>(R.id.player_overflow_find_track).performClick() }
                await("player sheet") { it.player()?.childFragmentManager?.findFragmentByTag(FindTrackSheet.TAG)?.view?.findViewById<View>(R.id.row_yandex)?.width ?: 0 > 0 }
                shot("$tag-02-find-track-player")
                on { a -> (a.player()!!.childFragmentManager.findFragmentByTag(FindTrackSheet.TAG) as FindTrackSheet).dismiss() }
                Thread.sleep(600)

                // Something not in the list is playing, so the projection keeps
                // CRYOGEN at the head where the frame has it.
                on { a ->
                    nowPlaying(a, "MUSE", "UPRISING")
                    a.findViewById<View>(R.id.player_header_action).performClick()
                }
                awaitMenu()
                on { a -> a.player()!!.overflowContentForTest()!!.findViewById<View>(R.id.player_overflow_history).performClick() }
                await("history rows") { rows(it) >= 6 }
                shot("$tag-03-history-content")

                on { a -> a.findViewById<RecyclerView>(R.id.history_list).scrollToPosition(frozenRows.size) }
                Thread.sleep(600)
                shot("$tag-04-history-content-end")
                on { a -> a.findViewById<RecyclerView>(R.id.history_list).scrollToPosition(0) }
                Thread.sleep(600)

                on { a ->
                    val list = a.findViewById<RecyclerView>(R.id.history_list)
                    list.getChildAt(1).findViewById<View>(R.id.btn_row_action).performClick()
                }
                await("history sheet") { it.history()?.childFragmentManager?.findFragmentByTag(FindTrackSheet.TAG)?.view?.findViewById<View>(R.id.row_yandex)?.width ?: 0 > 0 }
                shot("$tag-05-find-track-history")
            }

            // ---- loading: held open on a gate ----
            val gate = CompletableDeferred<HistoryResult>()
            HistoryRepository.sourceOverrideForTest = { _, _ -> gate.await() }
            try {
                session {
                    openHistory()
                    await("skeleton") { it.findViewById<View>(R.id.history_skeleton)?.visibility == View.VISIBLE }
                    shot("$tag-06-history-loading")
                }
            } finally {
                gate.complete(HistoryResult.Loaded(emptyList()))
            }

            // ---- empty and error: each in a fresh activity, so nothing is cached ----
            HistoryRepository.sourceOverrideForTest = { _, _ -> HistoryResult.Loaded(emptyList()) }
            session {
                openHistory()
                await("empty") { it.findViewById<View>(R.id.history_empty)?.visibility == View.VISIBLE }
                shot("$tag-07-history-empty")
            }
            HistoryRepository.sourceOverrideForTest = { _, _ -> HistoryResult.Failed("capture") }
            session {
                openHistory()
                await("error") { it.findViewById<View>(R.id.history_error)?.visibility == View.VISIBLE }
                shot("$tag-08-history-error")
            }

            // ---- Найти трек on a deliberately long artist: two lines, then … ----
            session {
                openPlayer()
                on { a ->
                    nowPlaying(
                        a,
                        "КРАСНОЗНАМЁННАЯ ДИВИЗИЯ ИМЕНИ МОЕЙ БАБУШКИ FEAT. НАУТИЛУС ПОМПИЛИУС, " +
                            "АУКЦЫОН И ЕЩЁ НЕСКОЛЬКО ПРИГЛАШЁННЫХ ИСПОЛНИТЕЛЕЙ",
                        "ФАК Ю",
                    )
                    a.findViewById<View>(R.id.player_header_action).performClick()
                }
                awaitMenu()
                on { a -> a.player()!!.overflowContentForTest()!!.findViewById<View>(R.id.player_overflow_find_track).performClick() }
                await("long-artist sheet") { it.player()?.childFragmentManager?.findFragmentByTag(FindTrackSheet.TAG)?.view?.findViewById<View>(R.id.row_yandex)?.width ?: 0 > 0 }
                shot("$tag-11-find-track-long-artist")
            }

            // ---- the placeholder: Найти трек drawn but unavailable ----
            session {
                openPlayer()
                on { a ->
                    nowPlaying(a, a.getString(R.string.slogan_placeholder), a.getString(R.string.brand_name))
                    a.findViewById<View>(R.id.player_header_action).performClick()
                }
                awaitMenu()
                shot("$tag-09-menu-no-track")
            }

            // ---- the live endpoint, whatever it answers ----
            HistoryRepository.sourceOverrideForTest = null
            session {
                openHistory()
                await("a live answer", timeoutMs = 20_000) { a ->
                    a.viewModel.historyLoading.value == false &&
                        (rows(a) > 0 || a.findViewById<View>(R.id.history_error)?.visibility == View.VISIBLE ||
                            a.findViewById<View>(R.id.history_empty)?.visibility == View.VISIBLE)
                }
                Thread.sleep(2_500) // let the covers resolve
                shot("$tag-10-history-live")
            }
        }
        android.util.Log.i("G4BQA", "screenshots written to $dir")
    }

    // ---- harness ----

    private fun session(body: () -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            body()
        } finally {
            try { scenario.close() } catch (e: Throwable) {
                android.util.Log.w("G4BQA", "activity close timed out", e)
            }
        }
    }

    private fun openPlayer() {
        await("HOME") { it.destination() == R.id.home }
        on { it.findViewById<View>(R.id.nav_item_player).performClick() }
        await("PLAYER") { it.destination() == R.id.player }
        Thread.sleep(600)
    }

    private fun openHistory() {
        openPlayer()
        on { it.findViewById<View>(R.id.player_header_action).performClick() }
        awaitMenu()
        on { a -> a.player()!!.overflowContentForTest()!!.findViewById<View>(R.id.player_overflow_history).performClick() }
        await("history") { it.destination() == R.id.broadcast_history }
    }

    private fun awaitMenu() {
        await("menu") { (it.player()?.overflowContentForTest()?.findViewById<View>(R.id.player_overflow_history)?.width ?: 0) > 0 }
    }

    private fun rows(a: MainActivity): Int {
        val list = a.findViewById<RecyclerView>(R.id.history_list) ?: return 0
        if (list.visibility != View.VISIBLE) return 0
        return (0 until list.childCount).count { list.getChildAt(it).findViewById<View>(R.id.btn_row_action) != null }
    }

    private fun nowPlaying(a: MainActivity, artist: String, song: String) {
        for (s in listOf(a.viewModel.currentMyataState, a.viewModel.currentGoldState, a.viewModel.currentXtraState)) {
            s.value = PlayerState(artist = artist, song = song, img = null)
        }
    }

    /** Fade and sheet animations out of the way, then the shot. */
    private fun shot(name: String) {
        Thread.sleep(900)
        shell("screencap -p $dir/$name.png")
    }

    private fun shell(cmd: String) {
        instrumentation.uiAutomation.executeShellCommand(cmd)
            .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it) }
            .use { it.readBytes() }
    }

    private fun MainActivity.host() = supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment
    private fun MainActivity.destination() = host().navController.currentDestination?.id
    private fun MainActivity.player() = host().childFragmentManager.fragments.filterIsInstance<PlayerFragment>().firstOrNull()
    private fun MainActivity.history() = host().childFragmentManager.fragments.filterIsInstance<BroadcastHistoryFragment>().firstOrNull()

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
        error("timed out waiting for $what")
    }
}
