package com.example.musicplayerapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.ui.HistoryRowTypography
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a Broadcast History row does when a track name is too long for one line.
 *
 * The reported symptom was line spacing that reads as excessive on a wrapped
 * title. The cause is that the `17/28` typography token is applied to a view with
 * no `maxLines`: 28 is the height of the frozen one-line slot, and used as a
 * leading it adds 6 to every line after the first. See [HistoryRowTypography].
 *
 * These measure the thing that was wrong - **the distance between two lines of a
 * wrapped title** - rather than the row height, which a `minHeight` can make
 * right while the spacing inside it is still wrong.
 *
 * Both lists are covered. `item_player_history_track` is the PLAYER's embedded
 * card, which had the correction privately since Phase C; `item_history_track` is
 * the History bottom sheet's row, which did not. That second list is not
 * reachable in the shipping app - nothing constructs `HistoryBottomSheet` - and
 * is covered anyway, because the layout is live code that a later phase is
 * expected to bring back.
 */
@RunWith(AndroidJUnit4::class)
class HistoryRowTypographyTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /**
     * Grant POST_NOTIFICATIONS before launching anything.
     *
     * MainActivity asks for it in `onCreate` on API 33+, and the system dialog
     * takes focus - which leaves the activity PAUSED, not RESUMED, so
     * `resumedMainActivity` finds nothing and the suite fails on the harness
     * instead of on what it measures. Same guard `SettingsEntryTest` opens with,
     * and for the same reason.
     */
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

    /** Long enough to wrap at any phone width, in the alphabet the station plays. */
    private val longTitle =
        "КРАСНОЗНАМЁННАЯ ДИВИЗИЯ ИМЕНИ МОЕЙ БАБУШКИ"

    /** The PLAYER's rows are on the owner's G4b "B": a 20 title line (16sp). */
    @Test
    fun player_row_wrapped_title_is_set_on_the_history_line_not_the_token_line() {
        assertLineSpacing(
            R.layout.item_player_history_track, "PLAYER row",
            HistoryRowTypography::applyPlayer, R.dimen.history_player_title_line_height,
        )
    }

    /** The full-screen История эфира row shares the PLAYER's scale. */
    @Test
    fun screen_row_wrapped_title_is_set_on_the_history_line_not_the_token_line() {
        assertLineSpacing(
            R.layout.item_broadcast_history_track, "History screen row",
            HistoryRowTypography::applyPlayer, R.dimen.history_player_title_line_height,
        )
    }

    /** The unreachable History sheet keeps its own 22 line - G4b did not touch it. */
    @Test
    fun sheet_row_wrapped_title_is_set_on_the_history_line_not_the_token_line() {
        assertLineSpacing(
            R.layout.item_history_track, "History sheet row",
            HistoryRowTypography::apply, R.dimen.history_row_title_line_height,
        )
    }

    /**
     * The one-line row still measures the frozen block.
     *
     * Correcting the leading must not shrink the ordinary row: the 21 + 19 floors
     * (22 + 18 before G4b's typography "B") are what make the PLAYER's row the
     * frozen 74 with its 17dp padding, and
     * `minHeight` rather than leading is what holds it there.
     */
    @Test
    fun one_line_row_still_measures_the_frozen_forty() {
        withInflater { inflater, density ->
            val row = inflate(inflater, R.layout.item_player_history_track, density, HistoryRowTypography::applyPlayer) {
                it.findViewById<TextView>(R.id.tv_title).text = "CRYOGEN"
                it.findViewById<TextView>(R.id.tv_artist).text = "MUSE"
            }
            val title = row.findViewById<TextView>(R.id.tv_title)
            val artist = row.findViewById<TextView>(R.id.tv_artist)
            assertEquals("the title should not have wrapped", 1, title.lineCount)

            val block = (artist.bottom - title.top) / density
            assertTrue(
                "the one-line block is ${block}dp, frozen design says 40",
                abs(block - 40f) <= 1.5f,
            )
        }
    }

    private fun assertLineSpacing(
        layout: Int,
        where: String,
        typography: (TextView, TextView) -> Unit,
        lineDimen: Int,
    ) {
        withInflater { inflater, density ->
            val row = inflate(inflater, layout, density, typography) {
                it.findViewById<TextView>(R.id.tv_title).text = longTitle
                it.findViewById<TextView>(R.id.tv_artist).text = "MUSE"
            }
            val title = row.findViewById<TextView>(R.id.tv_title)
            assertTrue(
                "$where: the title did not wrap, so this measures nothing - " +
                    "lineCount=${title.lineCount}",
                title.lineCount >= 2,
            )

            val layoutOf = title.layout!!
            val spacingPx = layoutOf.getLineTop(1) - layoutOf.getLineTop(0)
            val spacingSp = spacingPx / title.resources.displayMetrics.scaledDensity
            val expected = title.resources
                .getDimensionPixelSize(lineDimen) /
                title.resources.displayMetrics.scaledDensity

            assertTrue(
                "$where: wrapped title lines are ${spacingSp}sp apart; the history " +
                    "line is ${expected}sp. The 17/28 token's 28 is a one-line slot " +
                    "height, not a leading - see HistoryRowTypography.",
                abs(spacingSp - expected) <= 1.5f,
            )
        }
    }

    /**
     * Inflates a row and applies the metrics its adapter applies.
     *
     * The correction lives in the adapter's holder rather than in the layout - it
     * cannot live in the token, which carries the 28 every other surface wants -
     * so a test that only inflated the XML would measure a row no user ever sees.
     * This calls the same shared function both adapters call.
     */
    private fun inflate(
        inflater: LayoutInflater,
        layout: Int,
        density: Float,
        typography: (TextView, TextView) -> Unit,
        prepare: (ViewGroup) -> Unit,
    ): ViewGroup {
        val root = inflater.inflate(layout, null) as ViewGroup
        typography(
            root.findViewById(R.id.tv_title),
            root.findViewById(R.id.tv_artist),
        )
        prepare(root)
        val widthPx = (390 * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    /**
     * `resumedMainActivity` rather than `onActivity`, and the body on the main
     * thread: `onActivity` waits for an idle looper, which the PLAYER does not
     * reliably give on the API 24 image while it is connecting. Same reason every
     * other suite here reaches for it.
     */
    private fun withInflater(body: (LayoutInflater, Float) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            // Wait for RESUMED before asking for it. `launch` returns before the
            // activity is resumed, and `resumedMainActivity` throws rather than
            // waiting - so without this the suite fails on the harness rather
            // than on anything it measures.
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                var ready = false
                runCatching { instrumentation.runOnMainSync { resumedMainActivity(); ready = true } }
                if (ready) break
                Thread.sleep(25)
            }
            instrumentation.runOnMainSync {
                val a = resumedMainActivity()
                body(a.layoutInflater, a.resources.displayMetrics.density)
            }
        } finally {
            runCatching { scenario.close() }
        }
    }
}
