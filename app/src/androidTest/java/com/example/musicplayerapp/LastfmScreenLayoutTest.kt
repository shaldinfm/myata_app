package com.example.musicplayerapp

import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.core.widget.ImageViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.musicplayerapp.databinding.FragmentSettingsLastfmBinding
import com.example.musicplayerapp.fragments.SettingsLastfmFragment
import com.example.musicplayerapp.ui.lastfm.LastfmCardState
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * settings-lastfm against its frames, in both themes and all four states.
 *
 * Inflated and measured rather than launched, the way `SettingsLayoutTest` does
 * it: the four states are rendered through [SettingsLastfmFragment.render] on a
 * real inflated layout, so no fake account ever reaches a shipped build and the
 * states that P3a cannot produce are still drawn exactly as they will be.
 *
 * The geometry asserted is the chain of boxes and gaps, not absolute offsets -
 * Android rounds each dp to a whole pixel independently, and the drift that
 * accumulates says nothing about the layout.
 */
@RunWith(AndroidJUnit4::class)
class LastfmScreenLayoutTest {

    private val designWidthDp = 390

    @Test
    fun theCardKeepsTheFrozenGeometryInEveryStateAndTheme() {
        val findings = mutableListOf<String>()

        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                val theme = if (night) "dark" else "light"
                val inflater = inflaterFor(activity, night)
                val dm = inflater.context.resources.displayMetrics
                val dp = { v: Number -> v.toFloat() * dm.density }

                for (state in states()) {
                    val name = state::class.simpleName
                    val binding = bind(inflater, dp(designWidthDp).roundToInt())
                    SettingsLastfmFragment.render(binding, state)
                    measure(binding.root as ViewGroup, dp(designWidthDp).roundToInt())

                    val where = "lastfm/$theme/$name"
                    val card = binding.lastfmCard

                    // 358x172 - the card is the one box that must not move between
                    // states, because the button inside it is at a frozen y.
                    check(findings, "$where card width", dp(358), card.width.toFloat(), dm.density)
                    check(findings, "$where card height", dp(172), card.height.toFloat(), dm.density)

                    // The chain inside the card, box by box and gap by gap - not the
                    // button's absolute y. Android rounds every dp to a whole pixel
                    // independently, and at 420dpi 28/12/22dp land on 74/32/58px, so
                    // an absolute offset under six rounded boundaries drifts by 2px
                    // while every link in the chain is exact. The chain is what the
                    // frame specifies: 16 / 28 / 12 / 22 / 22 / 12 / 44 / 16 = 172.
                    val heading = binding.lastfmCardHeading.parent as View
                    val b1 = binding.lastfmBody1
                    val b2 = binding.lastfmBody2
                    val btn = binding.lastfmAction
                    check(findings, "$where top padding", dp(16), heading.top.toFloat(), dm.density)
                    check(findings, "$where heading row", dp(28), heading.height.toFloat(), dm.density)
                    check(findings, "$where heading->body", dp(12), (b1.top - heading.bottom).toFloat(), dm.density)
                    check(findings, "$where body 1", dp(22), b1.height.toFloat(), dm.density)
                    check(findings, "$where body 1->2", 0f, (b2.top - b1.bottom).toFloat(), dm.density)
                    check(findings, "$where body 2", dp(22), b2.height.toFloat(), dm.density)
                    check(findings, "$where body->button", dp(12), (btn.top - b2.bottom).toFloat(), dm.density)
                    check(findings, "$where button height", dp(44), btn.height.toFloat(), dm.density)
                    // The bottom padding is not an independent box: the card is a
                    // fixed 172dp so the button never moves between states, and what
                    // is left under the button is the residue of that fixed height
                    // minus seven independently rounded links. So it is asserted
                    // against the same arithmetic the platform does - which proves the
                    // layout is exactly the frozen chain, and that the only deviation
                    // from 16dp is pixel rounding (at 420dpi: 452 - 412 = 40px).
                    val rpx = { v: Int -> (v * dm.density + 0.5f).toInt() }
                    val residuePx = rpx(172) -
                        listOf(16, 28, 12, 22, 22, 12, 44).sumOf { rpx(it) }
                    check(
                        findings, "$where bottom padding (rounding residue)",
                        residuePx.toFloat(), (card.height - btn.bottom).toFloat(), dm.density,
                    )
                    assertTrue(
                        "$where the residue stays within a pixel per link of 16dp",
                        abs(residuePx - dp(16)) <= 7f,
                    )

                    // The mark is a 24dp slot whatever the state.
                    check(
                        findings, "$where logo box",
                        dp(24), binding.lastfmLogo.width.toFloat(), dm.density,
                    )
                    assertNotNull("$where logo drawable", binding.lastfmLogo.drawable)
                }
            }
        }

        assertTrue(
            "SETTINGS-LASTFM findings:\n" + findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }

    @Test
    fun eachStateDrawsItsOwnWordsAndItsOwnButton() {
        onMainActivity { activity ->
            val inflater = inflaterFor(activity, night = false)
            val ctx = inflater.context

            val disconnected = render(inflater, LastfmCardState.Disconnected)
            assertEquals(
                ctx.getString(R.string.lastfm_card_disconnected_heading),
                disconnected.lastfmCardHeading.text.toString(),
            )
            assertEquals(
                ctx.getString(R.string.lastfm_action_connect),
                disconnected.lastfmAction.text.toString(),
            )
            assertEquals(View.GONE, disconnected.lastfmCheck.visibility)
            assertEquals(View.VISIBLE, disconnected.lastfmBody2.visibility)

            val pending = render(inflater, LastfmCardState.AuthorizationPending)
            assertEquals(
                ctx.getString(R.string.lastfm_card_pending_heading),
                pending.lastfmCardHeading.text.toString(),
            )
            assertEquals(
                ctx.getString(R.string.lastfm_action_open_lastfm),
                pending.lastfmAction.text.toString(),
            )
            assertEquals(View.GONE, pending.lastfmCheck.visibility)
            // INVISIBLE, never GONE: the button must not move up a line.
            assertEquals(
                "the second body line holds its space",
                View.INVISIBLE, pending.lastfmBody2.visibility,
            )

            val connected = render(inflater, LastfmCardState.Connected("f0ul482", 1_248))
            assertEquals(
                ctx.getString(R.string.lastfm_card_connected_heading),
                connected.lastfmCardHeading.text.toString(),
            )
            assertEquals("f0ul482", connected.lastfmBody1.text.toString())
            assertEquals(
                "the count names whose total it is",
                "Всего в Last.fm: 1 248 скробблов",
                connected.lastfmBody2.text.toString(),
            )
            assertEquals(
                ctx.getString(R.string.lastfm_action_disconnect),
                connected.lastfmAction.text.toString(),
            )
            assertEquals("the check is the connected card's", View.VISIBLE, connected.lastfmCheck.visibility)

            val reauth = render(inflater, LastfmCardState.ReauthRequired("f0ul482"))
            assertEquals(
                ctx.getString(R.string.lastfm_card_reauth_heading),
                reauth.lastfmCardHeading.text.toString(),
            )
            assertEquals(
                ctx.getString(R.string.lastfm_card_reauth_body_1),
                reauth.lastfmBody1.text.toString(),
            )
            assertEquals(
                ctx.getString(R.string.lastfm_action_reconnect),
                reauth.lastfmAction.text.toString(),
            )
            assertEquals("a dead session is not a connected one", View.GONE, reauth.lastfmCheck.visibility)
        }
    }

    @Test
    fun aConnectedAccountWithNoCountStillReadsAsConnected() {
        onMainActivity { activity ->
            val inflater = inflaterFor(activity, night = false)
            val b = render(inflater, LastfmCardState.Connected("f0ul482", null))

            assertEquals("f0ul482", b.lastfmBody1.text.toString())
            assertEquals(
                "a count that could not be read is left out, never shown as 0",
                View.INVISIBLE, b.lastfmBody2.visibility,
            )
            assertEquals(View.VISIBLE, b.lastfmCheck.visibility)
        }
    }

    @Test
    fun theMarkIsPrimaryOnlyWhileASessionIsLinked() {
        // The tint is the frozen file's own state indicator. Asserted as the actual
        // colour the view carries, per theme, against the frame's values: primary
        // #1C4771 / #5FD9B4 when connected, text_secondary #42474E / #B3C4D1 otherwise.
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                val inflater = inflaterFor(activity, night)
                val ctx = inflater.context
                val primary = ctx.getColor(R.color.primary)
                val secondary = ctx.getColor(R.color.text_secondary)
                val expectPrimary = if (night) 0xFF5FD9B4.toInt() else 0xFF1C4771.toInt()
                val expectSecondary = if (night) 0xFFB3C4D1.toInt() else 0xFF42474E.toInt()
                // Positive control: the tokens resolve to the frame's own colours in
                // this theme, so a pass below is a pass against the design and not
                // against whatever the tokens happen to be.
                assertEquals("primary token (night=$night)", expectPrimary, primary)
                assertEquals("text_secondary token (night=$night)", expectSecondary, secondary)

                fun tintOf(state: LastfmCardState): Int? =
                    ImageViewCompat.getImageTintList(render(inflater, state).lastfmLogo)
                        ?.defaultColor

                assertEquals(
                    "connected (night=$night)", primary,
                    tintOf(LastfmCardState.Connected("f0ul482", 1)),
                )
                for (other in listOf(
                    LastfmCardState.Disconnected,
                    LastfmCardState.AuthorizationPending,
                    LastfmCardState.ReauthRequired("f0ul482"),
                )) {
                    assertEquals(
                        "${other::class.simpleName} (night=$night)", secondary, tintOf(other),
                    )
                }
            }
        }
    }

    @Test
    fun theLogoDrawableIsTheDecodedMarkInItsOwnProportions() {
        onMainActivity { activity ->
            val d: Drawable = androidx.core.content.ContextCompat.getDrawable(
                activity, R.drawable.ic_lastfm,
            )!!
            val density = activity.resources.displayMetrics.density
            // 24x24dp: the mark is padded into the ordinary icon slot rather than
            // carrying its own 17.676x11 geometry into every layout that uses it.
            assertEquals("intrinsic width", 24f, d.intrinsicWidth / density, 1.5f)
            assertEquals("intrinsic height", 24f, d.intrinsicHeight / density, 1.5f)
        }
    }

    // ==================== harness ====================

    private fun states() = listOf(
        LastfmCardState.Disconnected,
        LastfmCardState.AuthorizationPending,
        LastfmCardState.Connected("f0ul482", 22_258),
        LastfmCardState.ReauthRequired("f0ul482"),
    )

    private fun render(
        inflater: LayoutInflater,
        state: LastfmCardState,
    ): FragmentSettingsLastfmBinding {
        val dm = inflater.context.resources.displayMetrics
        val widthPx = (designWidthDp * dm.density).roundToInt()
        val binding = bind(inflater, widthPx)
        SettingsLastfmFragment.render(binding, state)
        measure(binding.root as ViewGroup, widthPx)
        return binding
    }

    private fun bind(inflater: LayoutInflater, widthPx: Int): FragmentSettingsLastfmBinding {
        val binding = FragmentSettingsLastfmBinding.inflate(inflater, null, false)
        measure(binding.root as ViewGroup, widthPx)
        return binding
    }

    private fun measure(root: ViewGroup, widthPx: Int) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(widthPx * 2, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun check(
        findings: MutableList<String>,
        what: String,
        expectedPx: Float,
        actualPx: Float,
        density: Float,
    ) {
        if (abs(expectedPx - actualPx) > 1.5f) {
            findings += "$what: expected ${expectedPx / density}dp, got ${actualPx / density}dp"
        }
    }

    private fun inflaterFor(activity: MainActivity, night: Boolean): LayoutInflater {
        val cfg = Configuration(activity.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val themed = activity.createConfigurationContext(cfg)
        themed.setTheme(R.style.AppTheme)
        return activity.layoutInflater.cloneInContext(themed)
    }

    private fun onMainActivity(block: (MainActivity) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity(block)
        } finally {
            try {
                scenario.close()
            } catch (e: Throwable) {
                android.util.Log.w("LastfmQA", "activity close timed out; checks complete", e)
            }
        }
    }
}
