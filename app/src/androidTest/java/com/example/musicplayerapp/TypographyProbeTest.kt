package com.example.musicplayerapp

import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.TextViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Decides, on the device it is running on, how the frozen typography tokens must
 * be applied - rather than inferring it from the API level.
 *
 * Three things make this awkward and are the reason the probe exists:
 *
 *  - `android:lineHeight` on TextView is API 28 and minSdk here is 24, so the
 *    framework cannot be the mechanism on its own.
 *  - `android:fontFamily="@font/..."` is API 26 in the framework. Below that only
 *    AppCompat's back-port resolves it, and only for views AppCompat inflated.
 *  - Both of those depend on the *runtime class* of the view, which the layouts
 *    never name: they all say plain `<TextView>`, and what that becomes is decided
 *    by the theme's viewInflater at inflation time.
 *
 * So the probe never constructs a view. It launches the real MainActivity and
 * inflates the app's own layouts through that activity's LayoutInflater - the
 * one carrying AppCompat's factory - which is the production path exactly. The
 * views it measures are the app's real views, and the classes it reports are the
 * classes the app really has.
 *
 * For each surface it records the full runtime picture, then tests the two
 * candidate mechanisms in order:
 *
 *   A. the token alone, applied as a text appearance - relies on MaterialTextView
 *      back-porting `android:lineHeight` out of the appearance;
 *   B. TextViewCompat.setLineHeight, which is compat-safe by construction and
 *      works by adjusting line spacing against measured font metrics.
 *
 * A is preferred if it holds on 24 and 36 both. The results are logged under the
 * TYPO tag and asserted, so a failure names the surface and the API level.
 */
@RunWith(AndroidJUnit4::class)
class TypographyProbeTest {

    /* ------------------------------------------------------------ surfaces -- */

    /**
     * A representative surface: a real view in a real app layout, and the frozen
     * token that surface is a candidate for. The token choice here is only the
     * probe's stress case - the role-to-token mapping is the migration's job, not
     * this test's. What matters is that between them the six cover a tight line
     * height, a loose one, a fractional one, single- and multi-line, and both
     * families at four weights.
     */
    private data class Surface(
        val name: String,
        val layout: Int,
        val viewId: Int,
        val token: Int,
        val lineHeightDimen: Int,
        val expectedFont: Int,
        /** Text forced onto the view, or null to keep the layout's own. */
        val text: String? = null,
        /** Width the surface really gets, for measuring wrap and clipping. */
        val widthDp: Int = 390,
    )

    private val surfaces = listOf(
        // Montserrat heading. Line height equals text size - the tight case, where
        // a mechanism that can only add leading cannot reach the target.
        Surface(
            name = "Montserrat heading (About Us title)",
            layout = R.layout.fragment_info,
            viewId = R.id.title,
            token = R.style.TextAppearance_Myata_Montserrat_Black_24_24,
            lineHeightDimen = R.dimen.line_height_montserrat_black_24_24,
            expectedFont = R.font.montserrat_black,
            text = "ТВОЯ МУЗЫКА.\nТВОЯ СТАНЦИЯ.",
        ),
        // Montserrat CTA. Single line inside a fixed 52dp button - the surface
        // where a line height larger than the box shows up as clipping.
        Surface(
            name = "Montserrat CTA (About Us donate button)",
            layout = R.layout.fragment_info,
            viewId = R.id.donate_cta,
            token = R.style.TextAppearance_Myata_Montserrat_Medium_22_28,
            lineHeightDimen = R.dimen.line_height_montserrat_medium_22_28,
            expectedFont = R.font.montserrat_medium,
            widthDp = 239,
        ),
        // Onest bottom navigation label. Smallest text in the app, single line,
        // and the one view whose height is pinned by the frozen 46dp item.
        Surface(
            name = "Onest BottomNav label",
            layout = R.layout.activity_main,
            viewId = R.id.home_label,
            token = R.style.TextAppearance_Myata_Onest_Medium_12_16,
            lineHeightDimen = R.dimen.line_height_onest_medium_12_16,
            expectedFont = R.font.onest_medium,
            widthDp = 79,
        ),
        // Onest History row, forced to wrap. The multi-line case: line height has
        // to govern the gap between lines, not just the first line's box.
        Surface(
            name = "Onest History row (multi-line track title)",
            layout = R.layout.item_history_track,
            viewId = R.id.tv_title,
            token = R.style.TextAppearance_Myata_Onest_Regular_14_20,
            lineHeightDimen = R.dimen.line_height_onest_regular_14_20,
            expectedFont = R.font.onest_regular,
            text = "Сплин — Мое сердце остановилось, мое сердце замерло (концертная запись)",
            widthDp = 233,
        ),
        // Onest About Us paragraph. Long multi-line body text, the largest
        // leading in the set, and the surface most sensitive to line metrics.
        Surface(
            name = "Onest About Us paragraph",
            layout = R.layout.fragment_info,
            viewId = R.id.description,
            token = R.style.TextAppearance_Myata_Onest_Regular_17_28,
            lineHeightDimen = R.dimen.line_height_onest_regular_17_28,
            expectedFont = R.font.onest_regular,
            widthDp = 310,
        ),
        // Stand-in for the Sleep Timer, which this app does not have - see the note
        // on sleepTimerSurfaceIsAbsent(). Settings itself exists as of G1 and is
        // measured directly by SettingsLayoutTest. Carries the fractional
        // 27.5sp line height, which is the part of that surface worth probing:
        // it is the only token whose value does not land on a whole sp.
        Surface(
            name = "Onest fractional 15/27.5 (stand-in for Settings/Sleep Timer)",
            layout = R.layout.fragment_myata_stream,
            viewId = R.id.main_song,
            token = R.style.TextAppearance_Myata_Onest_Medium_15_27_5,
            lineHeightDimen = R.dimen.line_height_onest_medium_15_27_5,
            expectedFont = R.font.onest_medium,
            text = "Выключить через 30 минут",
            widthDp = 310,
        ),
    )

    /**
     * Every font the app ships, for identifying what a view really got.
     *
     * This is the whole set now that Muller is gone - which is also why a Muller
     * face can no longer appear here even as a wrong answer. A view that somehow
     * resolved to something outside this list reports UNKNOWN rather than being
     * quietly accepted.
     */
    private val fontCandidates = listOf(
        R.font.montserrat_regular, R.font.montserrat_medium,
        R.font.montserrat_bold, R.font.montserrat_black,
        R.font.onest_light, R.font.onest_regular, R.font.onest_medium,
        R.font.onest_bold, R.font.onest_black,
    )

    /* -------------------------------------------------------------- report -- */

    private data class Probe(
        val surface: String,
        val runtimeClass: String,
        val textAppearance: String,
        val textSizePx: Float,
        val fontMatch: String,
        val typefaceWeight: String,
        val requestedLineHeightPx: Int,
        val actualLineHeightPx: Int,
        val includeFontPadding: Boolean,
        val lineCount: Int,
        val measuredHeightPx: Int,
        /**
         * Largest deviation of the gap between consecutive line tops from the
         * requested line height. This, not the block height, is what the frozen
         * design actually specifies, and it is unaffected by the view's padding.
         */
        val worstLineGapDeltaPx: Int,
        /**
         * Room above the first baseline beyond what this string's glyphs really
         * ink. Measured from the text's own bounds rather than the font's
         * worst-case extent, so it answers whether these letters are clipped
         * rather than whether some letter could be. Negative = clipped.
         */
        val ascenderHeadroomPx: Int,
        /**
         * How the leading is split around the text. Android adds all of it below
         * the baseline; CSS and Figma split it half above, half below. Recorded
         * because it is a visible difference the migration inherits, not a defect
         * the migration introduces.
         */
        val leadingAbovePx: Int,
        val leadingBelowPx: Int,
    ) {
        val lineHeightDelta get() = actualLineHeightPx - requestedLineHeightPx
        val lineHeightOk get() = abs(lineHeightDelta) <= 1

        override fun toString() = buildString {
            append("\n  surface            : ").append(surface)
            append("\n  runtime class      : ").append(runtimeClass)
            append("\n  textAppearance     : ").append(textAppearance)
            append("\n  textSize           : ").append(textSizePx).append("px")
            append("\n  typeface           : ").append(fontMatch).append("  weight=").append(typefaceWeight)
            append("\n  lineHeight req/act : ").append(requestedLineHeightPx).append(" / ")
                .append(actualLineHeightPx).append("px  delta=").append(lineHeightDelta)
                .append(if (lineHeightOk) "  OK" else "  MISMATCH")
            append("\n  includeFontPadding : ").append(includeFontPadding)
            append("\n  lines / height     : ").append(lineCount).append(" / ").append(measuredHeightPx).append("px")
            append("\n  line-to-line delta : ").append(worstLineGapDeltaPx).append("px")
                .append(if (worstLineGapDeltaPx == 0) "  OK" else "  UNEVEN")
            append("\n  ascender headroom  : ").append(ascenderHeadroomPx).append("px")
                .append(if (ascenderHeadroomPx < 0) "  CLIPPED" else "")
            append("\n  leading above/below: ").append(leadingAbovePx).append(" / ").append(leadingBelowPx).append("px")
        }
    }

    /* --------------------------------------------------------------- probe -- */

    /**
     * Inflates [surface]'s real layout through [inflater] - which must be an
     * activity's, so AppCompat's factory is installed - applies the token by
     * whichever mechanism [apply] implements, measures, and reports.
     */
    private fun probe(
        inflater: LayoutInflater,
        surface: Surface,
        apply: (TextView, Int) -> Unit,
    ): Probe {
        val res = inflater.context.resources
        val dm = res.displayMetrics
        val root = inflater.inflate(surface.layout, null) as ViewGroup
        val tv = root.findViewById<TextView>(surface.viewId)
        surface.text?.let { tv.text = it }

        val requested = res.getDimensionPixelSize(surface.lineHeightDimen)
        apply(tv, requested)

        val widthPx = (surface.widthDp * dm.density).roundToInt()
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        tv.layout(0, 0, tv.measuredWidth, tv.measuredHeight)

        val layout = tv.layout
        val fm = tv.paint.fontMetricsInt

        // Room above the first baseline, against what this string's letters
        // really ink. getTextBounds is the actual painted extent, so a capital
        // Cyrillic Й or a Latin ascender counts and the font's unused overshoot
        // does not. Once this goes negative the tops of letters are painted above
        // the view and the platform clips them.
        val headroom = if (layout != null && layout.lineCount > 0) {
            val line = tv.text.subSequence(layout.getLineStart(0), layout.getLineEnd(0)).toString()
            val ink = android.graphics.Rect()
            tv.paint.getTextBounds(line, 0, line.length, ink)
            (layout.getLineBaseline(0) - layout.getLineTop(0)) - (-ink.top)
        } else {
            0
        }

        // Whether the gap between successive lines is the requested line height.
        // The block height cannot answer this - the views carry their own padding
        // - but the line tops can, and they are what the reader sees as rhythm.
        val worstGap = if (layout != null && layout.lineCount > 1) {
            (0 until layout.lineCount - 1)
                .map { layout.getLineTop(it + 1) - layout.getLineTop(it) - requested }
                .maxByOrNull { abs(it) } ?: 0
        } else {
            0
        }

        // Where the leading went. -ascent/descent is the font's own box; anything
        // beyond it is leading, and Android puts all of it underneath.
        val above = if (layout != null && layout.lineCount > 0) {
            (layout.getLineBaseline(0) - layout.getLineTop(0)) - (-fm.ascent)
        } else 0
        val below = if (layout != null && layout.lineCount > 0) {
            (layout.getLineBottom(0) - layout.getLineBaseline(0)) - fm.descent
        } else 0

        return Probe(
            surface = surface.name,
            runtimeClass = tv.javaClass.name,
            textAppearance = res.getResourceEntryName(surface.token),
            textSizePx = tv.textSize,
            fontMatch = identifyFont(tv, surface),
            typefaceWeight = describeWeight(tv.typeface),
            requestedLineHeightPx = requested,
            actualLineHeightPx = tv.lineHeight,
            includeFontPadding = tv.includeFontPadding,
            lineCount = tv.lineCount,
            measuredHeightPx = tv.measuredHeight,
            worstLineGapDeltaPx = worstGap,
            ascenderHeadroomPx = headroom,
            leadingAbovePx = above,
            leadingBelowPx = below,
        )
    }

    /**
     * Names the font a view is really rendering with.
     *
     * Typeface.getWeight() is API 28, and identity against ResourcesCompat.getFont
     * only holds while the caches agree - neither is enough on 24. So the view's
     * own paint is copied, the typeface swapped for each candidate in turn, and a
     * Cyrillic-and-Latin probe measured. Two different weights of the same family
     * do not advance identically, so whichever candidate reproduces the view's own
     * measurement to the pixel is the font it has. That is what catches a weight
     * silently collapsing to Regular.
     */
    private fun identifyFont(tv: TextView, surface: Surface): String {
        val probeText = "Мята Radio Медиа Wgt 0123 ШЩЪЫЬЭЮЯ jgpqy"
        val actual = tv.paint.measureText(probeText)
        val ctx = tv.context
        val matches = fontCandidates.filter { id ->
            val paint = TextPaint(tv.paint)
            paint.typeface = ResourcesCompat.getFont(ctx, id)
            abs(paint.measureText(probeText) - actual) < 0.01f
        }
        val expected = ctx.resources.getResourceEntryName(surface.expectedFont)
        val names = matches.map { ctx.resources.getResourceEntryName(it) }
        return when {
            names.isEmpty() -> "UNKNOWN (matches no bundled font; system fallback?) expected=$expected"
            expected in names -> "$expected${if (names.size > 1) "  (indistinguishable from ${names - expected})" else ""}"
            else -> "WRONG: $names, expected=$expected"
        }
    }

    private fun describeWeight(tf: Typeface?): String = when {
        tf == null -> "null"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
            "${tf.weight}${if (tf.isBold) " bold" else ""}"
        // getWeight() does not exist below 28; the measurement match above is the
        // real evidence there, so say so rather than reporting a style as a weight.
        else -> "unavailable below API 28 (style=${tf.style}); see typeface match"
    }

    /* --------------------------------------------------------------- tests -- */

    /**
     * Mechanism A: the token alone - and the reason there is a mechanism C.
     *
     * Nothing is applied but the text appearance. On API 28 and up the framework
     * reads android:lineHeight out of a text appearance itself and this is all
     * that would be needed. Below that it is not: measured on API 24, five of the
     * six surfaces keep the font's own natural line height and only the one whose
     * token happens to equal it looks right, which is the worst possible failure
     * mode - it does not throw, it does not log, it just renders wrong on the
     * oldest devices the app supports.
     *
     * MaterialTextView, which is what these views really are, does back-port
     * exactly this. It does not fire here: the back-port is gated on
     * materialThemeOverlay/textAppearanceLineHeightEnabled resolution that
     * Theme.Myata.Base does not satisfy, so relying on it would be relying on a
     * theme detail rather than on a mechanism.
     *
     * Asserted in both directions so that the day this stops being true - a
     * Material upgrade, a minSdk bump - the suite says so instead of silently
     * carrying a layer nobody needs any more.
     */
    @Test
    fun textAppearanceAloneIsNotEnoughBelowApi28() {
        val results = withRealInflater { inflater ->
            surfaces.map { s -> probe(inflater, s) { tv, _ -> tv.setTextAppearance(s.token) } }
        }
        report("MECHANISM A - text appearance alone", results)

        val wrong = results.filterNot { it.lineHeightOk }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            assertTrue(
                "the framework stopped honouring a text appearance's line height on API " +
                    "${Build.VERSION.SDK_INT}: ${wrong.joinToString { it.surface }}",
                wrong.isEmpty(),
            )
        } else {
            assertTrue(
                "a text appearance alone now carries the line height on API " +
                    "${Build.VERSION.SDK_INT}, which it could not when the compat layer was " +
                    "written - re-check whether MyataTypography is still needed",
                wrong.isNotEmpty(),
            )
        }
    }

    /**
     * Mechanism B: the token, then TextViewCompat.setLineHeight from the dimen.
     *
     * The fallback, and the one that cannot depend on API level - it converts the
     * target into line spacing against the font metrics the view currently has.
     * Which is also its constraint: it has to run after the typeface and text size
     * are final, so a compat layer built on it must apply the appearance first.
     */
    @Test
    fun compatSetLineHeightCarriesLineHeight() {
        val results = withRealInflater { inflater ->
            surfaces.map { s ->
                probe(inflater, s) { tv, requested ->
                    tv.setTextAppearance(s.token)
                    TextViewCompat.setLineHeight(tv, requested)
                }
            }
        }
        report("MECHANISM B - TextViewCompat.setLineHeight", results)

        val bad = results.filterNot { it.lineHeightOk }
        assertTrue(
            "TextViewCompat.setLineHeight did not take on API ${Build.VERSION.SDK_INT} " +
                "for: ${bad.joinToString { "${it.surface} (want ${it.requestedLineHeightPx}, " +
                    "got ${it.actualLineHeightPx})" }}",
            bad.isEmpty(),
        )
    }

    /**
     * The token's includeFontPadding does not survive being applied, on any API.
     *
     * A TextAppearance is not a style: the framework reads a fixed list of
     * attributes out of it and android:includeFontPadding is not on that list, so
     * declaring it in the token is silently a no-op. This test exists to hold that
     * fact still, because it is not visible anywhere except in what the views
     * measure - and because it is the reason a code-side application layer is
     * needed at all, rather than android:textAppearance on its own.
     *
     * It matters because the frozen line heights are Figma's, and Figma has no
     * font padding. Leaving it on adds the font's top/bottom overshoot to the
     * first and last line of every paragraph, so the box stops being lines x
     * lineHeight even when every line height is exactly right.
     */
    @Test
    fun tokenIncludeFontPaddingIsSilentlyDropped() {
        val results = withRealInflater { inflater ->
            surfaces.map { s ->
                probe(inflater, s) { tv, _ ->
                    // Forced on first, so the question is well posed. Some of
                    // these layouts are migrated now, which means the inflater has
                    // already run MyataTypography over them and turned it off -
                    // and then the test would be measuring the layer rather than
                    // the text appearance.
                    tv.includeFontPadding = true
                    tv.setTextAppearance(s.token)
                }
            }
        }
        report("INCLUDE FONT PADDING - token asked for false", results)

        val stillOn = results.filter { it.includeFontPadding }
        android.util.Log.i(
            "TYPO",
            "includeFontPadding after applying the token: still true on ${stillOn.size}/${results.size} " +
                "surfaces on API ${Build.VERSION.SDK_INT}",
        )
        assertTrue(
            "a TextAppearance carried includeFontPadding on API ${Build.VERSION.SDK_INT}, which it is " +
                "not supposed to be able to do - re-check whether the application layer is still needed",
            stillOn.size == results.size,
        )
    }

    /**
     * Mechanism C: the whole contract, in the order it has to happen.
     *
     * The appearance first, because everything after it depends on the final
     * typeface and text size; then includeFontPadding, which the appearance cannot
     * carry; then the line height, measured against the metrics those two just
     * settled. This is the candidate the migration would ship, and the assertions
     * are the acceptance conditions in full - right line height, no clipped
     * ascenders, and a box that is exactly lines x lineHeight.
     */
    @Test
    fun fullCompatApplicationMeetsTheContract() {
        val results = withRealInflater { inflater ->
            surfaces.map { s ->
                probe(inflater, s) { tv, requested ->
                    tv.setTextAppearance(s.token)
                    tv.includeFontPadding = false
                    TextViewCompat.setLineHeight(tv, requested)
                }
            }
        }
        report("MECHANISM C - appearance + includeFontPadding + compat line height", results)

        val wrongLineHeight = results.filterNot { it.lineHeightOk }
        assertTrue(
            "line height wrong on API ${Build.VERSION.SDK_INT} for: " +
                wrongLineHeight.joinToString {
                    "${it.surface} (want ${it.requestedLineHeightPx}, got ${it.actualLineHeightPx})"
                },
            wrongLineHeight.isEmpty(),
        )

        val stillPadded = results.filter { it.includeFontPadding }
        assertTrue("includeFontPadding still on for: ${stillPadded.map { it.surface }}", stillPadded.isEmpty())

        val clipped = results.filter { it.ascenderHeadroomPx < 0 }
        assertTrue(
            "ascenders clipped on API ${Build.VERSION.SDK_INT} for: " +
                clipped.joinToString { "${it.surface} (headroom ${it.ascenderHeadroomPx}px)" },
            clipped.isEmpty(),
        )

        // The rhythm the design actually specifies: every line exactly the token's
        // line height from the one before it. Exact, because with font padding off
        // there is nothing left to absorb a discrepancy.
        val uneven = results.filter { it.worstLineGapDeltaPx != 0 }
        assertTrue(
            "line-to-line spacing is not the token's line height on API ${Build.VERSION.SDK_INT} for: " +
                uneven.joinToString { "${it.surface} (off by ${it.worstLineGapDeltaPx}px)" },
            uneven.isEmpty(),
        )
    }

    /**
     * The token has to bring its font across too, and on API 24 that is not the
     * framework's doing - android:fontFamily pointing at @font is API 26, so below
     * that only AppCompat's back-port resolves it, and only on views it inflated.
     * A failure here reads as text silently rendering in the system font, or in
     * Regular where the design asked for Medium or Black.
     */
    @Test
    fun tokenAppliesTheCorrectStaticFontAndWeight() {
        val results = withRealInflater { inflater ->
            surfaces.map { s -> probe(inflater, s) { tv, _ -> tv.setTextAppearance(s.token) } }
        }
        report("FONT AND WEIGHT", results)

        val bad = results.filter { it.fontMatch.startsWith("WRONG") || it.fontMatch.startsWith("UNKNOWN") }
        assertTrue(
            "wrong font on API ${Build.VERSION.SDK_INT} for: " +
                bad.joinToString { "${it.surface} -> ${it.fontMatch}" },
            bad.isEmpty(),
        )
    }

    /**
     * The mechanism end to end, with nothing applied by the test at all.
     *
     * Every other test here reaches in and calls setTextAppearance, which proves
     * the pieces work but not that they are wired up. This one takes the bottom
     * nav labels straight off the running MainActivity - inflated by the app, from
     * the app's own layout, through MyataTypography.Factory - and asks what they
     * came out as. It is the only test that would notice the factory not being
     * installed, and it is the reason BottomNavLabel was migrated first.
     */
    @Test
    fun inflatedNavLabelsGetTheirTokenWithoutAnyoneApplyingIt() {
        val expected = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .resources.getDimensionPixelSize(R.dimen.line_height_onest_medium_12_16)

        val labels = listOf(R.id.home_label, R.id.player_label, R.id.favorites_label, R.id.info_label)
        val seen = mutableListOf<String>()
        val wrong = mutableListOf<String>()

        onScenario { activity ->
            labels.forEach { id ->
                val tv = activity.findViewById<TextView>(id)
                val name = activity.resources.getResourceEntryName(id)
                seen += "$name: class=${tv.javaClass.simpleName} textSize=${tv.textSize} " +
                    "lineHeight=${tv.lineHeight} (want $expected) " +
                    "includeFontPadding=${tv.includeFontPadding}"
                if (tv.lineHeight != expected) wrong += "$name lineHeight=${tv.lineHeight}"
                if (tv.includeFontPadding) wrong += "$name includeFontPadding=true"
            }
        }
        android.util.Log.i("TYPO", "==== XML PATH - as inflated by the app (API ${Build.VERSION.SDK_INT}) ====")
        seen.forEach { android.util.Log.i("TYPO", "  $it") }

        assertTrue(
            "the nav labels did not come out of inflation carrying their token on API " +
                "${Build.VERSION.SDK_INT} - is MyataTypography.Factory installed before " +
                "super.onCreate? $wrong",
            wrong.isEmpty(),
        )
    }

    /**
     * Every migrated surface, as the app really inflates it.
     *
     * The nav-label test above proves the factory is installed; this one proves
     * the migration landed on each surface that took a token. Nothing is applied
     * by the test - the layouts are inflated through the activity's inflater and
     * asked what they came out as, so a token that was mistyped, a textSize left
     * behind next to a textAppearance, or a layout that stopped going through the
     * factory all show up here.
     *
     * Note which layout is actually measured: every device in the matrix is wider
     * than 320dp, so layout-sw320dp wins over layout/ for the two screens that
     * have both variants. That is the one users get, and so it is the one tested.
     */
    @Test
    fun everyMigratedSurfaceCarriesItsToken() {
        // The migration contract, as a table: layout, view, and the line height
        // its token promises.
        val migrated = listOf(
            Triple(R.layout.fragment_info, R.id.description, R.dimen.line_height_onest_regular_15_24),
            Triple(R.layout.fragment_info, R.id.donate_cta, R.dimen.line_height_montserrat_medium_22_28),
            Triple(R.layout.fragment_favorites, R.id.title, R.dimen.line_height_montserrat_medium_24_32),
            // The two export pills these used to name are gone: Phase B moves the
            // export actions into the header overflow, where the frozen design
            // puts them. Their place in this table is taken by the surfaces the
            // migrated screen actually draws.
            Triple(R.layout.fragment_favorites, R.id.collection_subtitle, R.dimen.line_height_onest_regular_14_20),
            Triple(R.layout.fragment_favorites, R.id.empty_title, R.dimen.line_height_onest_medium_16_28),
            Triple(R.layout.fragment_favorites, R.id.empty_body, R.dimen.line_height_onest_regular_12_20),
            Triple(R.layout.fragment_main, R.id.playlistString, R.dimen.line_height_montserrat_bold_28_36),
            // These two were the wrong way round, and have been failing on main
            // since PR #40: that PR corrected PLAYER, which had the artist in the
            // 24 Black slot and the title in the 18 Regular one, but this table
            // was not moved with it. main_song carries Black 24/24 now and
            // main_author Regular 18/18, which is what fragment_myata_stream.xml
            // says. Corrected here because the suite has to pass to be evidence;
            // nothing on PLAYER is touched.
            Triple(R.layout.fragment_myata_stream, R.id.main_song, R.dimen.line_height_montserrat_black_24_24),
            Triple(R.layout.fragment_myata_stream, R.id.main_author, R.dimen.line_height_montserrat_regular_18_18),
            Triple(R.layout.item_history_track, R.id.tv_time, R.dimen.line_height_onest_regular_14_20),
            Triple(R.layout.item_history_track, R.id.tv_artist, R.dimen.line_height_onest_regular_14_20),
            Triple(R.layout.item_history_track, R.id.tv_title, R.dimen.line_height_onest_regular_17_28),
            Triple(R.layout.item_favorite_track, R.id.tv_artist, R.dimen.line_height_onest_regular_12_20),
            Triple(R.layout.item_favorite_track, R.id.tv_track, R.dimen.line_height_onest_regular_16_28),
        )

        val seen = mutableListOf<String>()
        val wrong = mutableListOf<String>()
        withRealInflater { inflater ->
            val res = inflater.context.resources
            migrated.forEach { (layout, viewId, dimen) ->
                val root = inflater.inflate(layout, null) as ViewGroup
                val tv = root.findViewById<TextView>(viewId)
                val want = res.getDimensionPixelSize(dimen)
                val name = "${res.getResourceEntryName(layout)}/${res.getResourceEntryName(viewId)}"
                seen += "$name: ${tv.javaClass.simpleName} size=${tv.textSize} " +
                    "lineHeight=${tv.lineHeight} (want $want) pad=${tv.includeFontPadding}"
                if (tv.lineHeight != want) wrong += "$name lineHeight=${tv.lineHeight} want=$want"
                if (tv.includeFontPadding) wrong += "$name includeFontPadding=true"
            }
        }
        android.util.Log.i("TYPO", "==== MIGRATED SURFACES (API ${Build.VERSION.SDK_INT}) ====")
        seen.forEach { android.util.Log.i("TYPO", "  $it") }

        assertTrue(
            "migrated surfaces did not come out of inflation carrying their token on API " +
                "${Build.VERSION.SDK_INT}: $wrong",
            wrong.isEmpty(),
        )
    }

    /**
     * TV keeps its own sizes and gets no token - but it must still get its font.
     *
     * TvMainActivity deliberately has no MyataTypography factory, so the five TV
     * surfaces are plain android:fontFamily="@font/onest_*" on views AppCompat
     * inflates. That is the whole mechanism there, and below API 26 the framework
     * does not understand a font resource at all - only AppCompat's back-port
     * does. Onest Light is the case worth the trouble: 300 is the one weight the
     * mobile design never uses, it exists nowhere else in the app, and a font that
     * failed to resolve would fall back to the system sans and look like nothing
     * more than a slightly different grey.
     *
     * Inflated under TvTheme rather than the app theme, because that is the theme
     * TV runs, and it is a different Material parent.
     */
    @Test
    fun tvSurfacesKeepTheirSizesAndGetOnest() {
        val tv = ContextThemeWrapper(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(),
            R.style.TvTheme,
        )
        val expected = listOf(
            Triple(R.layout.fragment_tv_stream_selection, R.id.tv_title, R.font.onest_light) to 32f,
            Triple(R.layout.fragment_tv_player, R.id.tv_track_info, R.font.onest_regular) to 18f,
            Triple(R.layout.fragment_tv_player, R.id.btn_stream_myata, R.font.onest_regular) to 13f,
            Triple(R.layout.fragment_tv_player, R.id.btn_stream_gold, R.font.onest_regular) to 13f,
            Triple(R.layout.fragment_tv_player, R.id.btn_stream_xtra, R.font.onest_regular) to 13f,
        )
        val wrong = mutableListOf<String>()
        val seen = mutableListOf<String>()

        onScenario { activity ->
            // The activity's inflater cloned into the TV theme: AppCompat's
            // factory survives cloneInContext, which is what TV relies on.
            val inflater = activity.layoutInflater.cloneInContext(tv)
            expected.forEach { (spec, sizeSp) ->
                val (layout, viewId, font) = spec
                val root = inflater.inflate(layout, null) as ViewGroup
                val view = root.findViewById<TextView>(viewId)
                val name = tv.resources.getResourceEntryName(viewId)
                val wantPx = android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp, tv.resources.displayMetrics,
                )
                val want = ResourcesCompat.getFont(tv, font)
                val matches = view.typeface == want
                seen += "$name: ${view.javaClass.simpleName} size=${view.textSize} (want $wantPx) " +
                    "font=${tv.resources.getResourceEntryName(font)} matched=$matches"
                if (abs(view.textSize - wantPx) > 1f) wrong += "$name size ${view.textSize} != $wantPx"
                if (!matches) wrong += "$name did not resolve ${tv.resources.getResourceEntryName(font)}"
            }
        }
        android.util.Log.i("TYPO", "==== TV SURFACES (API ${Build.VERSION.SDK_INT}) ====")
        seen.forEach { android.util.Log.i("TYPO", "  $it") }

        assertTrue("TV typography wrong on API ${Build.VERSION.SDK_INT}: $wrong", wrong.isEmpty())
    }

    /**
     * No Muller resource survives.
     *
     * The reason the migration happened is licensing, so "it looks right" is not
     * the acceptance condition - the binaries being gone is. Resources.getIdentifier
     * asks the real resource table of the installed app.
     */
    @Test
    fun noMullerFontResourceRemains() {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val names = listOf(
            "muller_bold", "muller_light", "muller_regular", "mullerblack", "mullerheavy",
            "mullerlight", "mullermedium", "mullerregular", "mullerthin",
        )
        val survivors = names.filter { ctx.resources.getIdentifier(it, "font", ctx.packageName) != 0 }
        android.util.Log.i("TYPO", "Muller font resources still in the table: $survivors")
        assertTrue("Muller font resources still present: $survivors", survivors.isEmpty())
    }

    /**
     * Not a behaviour test - a recorded fact the report has to carry.
     *
     * The brief lists Settings / Sleep Timer as one surface to probe, and it has
     * been two things since G1. **Settings now exists** - `fragment_settings`,
     * `SettingsFragment` and the `settings` destination - and its typography is
     * covered by `SettingsLayoutTest`, which measures the real screen rather than
     * a stand-in. **The sleep timer still does not**: no layout, no dialog, no
     * fragment, no destination and no string, and it is a slice of its own.
     *
     * So the recorded fact narrows rather than disappearing. The fractional
     * 15/27.5 token - the only one in the set whose line height does not land on a
     * whole sp, and the reason this entry exists at all - is still probed on a real
     * stream-screen label, because the surface the brief wanted it probed on is
     * still not in the app.
     *
     * ## The package name, which this used to get wrong
     *
     * Until G1 this passed `"com.example.musicplayerapp"` to `getIdentifier` as the
     * package. That is the **namespace**, not the `applicationId`, which is
     * `dlinemedia.radioplayer.myata` - so every lookup returned 0, every layout
     * looked absent, and the assertion that three were missing passed without ever
     * asking the resource table anything. It was found by adding the positive half
     * below, which claimed `fragment_settings` was missing on a build that had just
     * shipped it.
     *
     * `context.packageName` now, so the lookups are real and both halves mean
     * something.
     */
    @Test
    fun sleepTimerSurfaceIsAbsent() {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val res = ctx.resources

        // Sanity: prove the lookup can find something before trusting it to report
        // that something is missing. This is what the old hardcoded package broke.
        assertTrue(
            "getIdentifier cannot resolve a layout that certainly exists - the " +
                "package name is wrong and every 'absent' answer below is vacuous",
            res.getIdentifier("fragment_main", "layout", ctx.packageName) != 0,
        )

        val sleepTimer = listOf("fragment_sleep_timer", "dialog_sleep_timer")
        val absent = sleepTimer
            .filter { res.getIdentifier(it, "layout", ctx.packageName) == 0 }
        android.util.Log.i(
            "TYPO",
            "Sleep Timer: no such surface in this app (missing layouts: $absent). " +
                "Probed the fractional 15/27.5 token on R.id.main_song instead. " +
                "Settings itself exists as of G1 - see SettingsLayoutTest.",
        )
        assertTrue("Sleep timer layouts have appeared: $absent", absent.size == sleepTimer.size)

        // The other half of the old assertion, inverted: this used to record that
        // Settings was missing, and it now records that it arrived. A build in which
        // both halves were false again would mean the screen had been deleted.
        assertTrue(
            "fragment_settings is missing - Settings shipped in G1",
            res.getIdentifier("fragment_settings", "layout", ctx.packageName) != 0,
        )
    }

    /* --------------------------------------------------------------- infra -- */

    /**
     * Runs [block] with the real MainActivity's LayoutInflater.
     *
     * This is the whole point of using an activity: LayoutInflater.from() on a
     * bare ContextThemeWrapper has no AppCompat factory, so every `<TextView>`
     * inflates as a plain android.widget.TextView and the probe would measure a
     * class the app does not have. The activity's inflater carries the factory,
     * so the theme's viewInflater decides - as it does in production.
     */
    private fun <T> withRealInflater(block: (LayoutInflater) -> T): T {
        var result: T? = null
        onScenario { activity -> result = block(activity.layoutInflater) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /**
     * Launches MainActivity, hands it to [block], and gets rid of it again.
     *
     * Deliberately not `use { }`. ActivityScenario.close() asserts the activity
     * reaches DESTROYED within a timeout, and on the software-rendered API 24
     * image it can still be in PAUSED when that runs out - which fails the test
     * for a reason that has nothing to do with typography. The work is already
     * finished by then, so a close that times out is not a result worth losing;
     * the runner tears the activity down between tests regardless.
     */
    private fun onScenario(block: (MainActivity) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity(block)
        } finally {
            try {
                scenario.close()
            } catch (e: Throwable) {
                // Throwable, not RuntimeException: the timeout arrives as an
                // AssertionError, which is an Error and would otherwise escape.
                android.util.Log.w("TYPO", "activity close timed out; results already collected", e)
            }
        }
    }

    private fun report(title: String, results: List<Probe>) {
        val head = "==== $title  (API ${Build.VERSION.SDK_INT}) ===="
        android.util.Log.i("TYPO", head)
        results.forEach { android.util.Log.i("TYPO", it.toString()) }
        // Also on stdout, so the run's own output carries the evidence without
        // needing a separate logcat capture.
        println(head)
        results.forEach { println(it) }
    }
}
