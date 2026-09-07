package com.example.musicplayerapp

import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The five frozen report frames, measured.
 *
 * report-empty 2517:2129 / report-filled 2517:2172 / report-sending 2517:2215 /
 * report-error 2517:2258 / report-success 2517:2317, and their dark twins. Every
 * expected number here is an absolute Figma offset or the difference between two,
 * so a failure says which anchor moved rather than that "the layout changed".
 *
 * The four form states are one layout, so what is measured per state is what the
 * state is allowed to change: the selected row's treatment, the banner's presence,
 * and the button's label, fill and y. Everything else is asserted once and must be
 * identical in all four.
 */
@RunWith(AndroidJUnit4::class)
class ReportProblemLayoutTest {

    private val widthsDp = listOf(320, 360, 390, 412)
    private val findings = mutableListOf<String>()
    private val log = mutableListOf<String>()

    // ==================== the form ====================

    @Test
    fun theFormReproducesTheFrozenFrames() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                val inflater = inflaterFor(activity, night)
                val theme = if (night) "dark" else "light"
                for (widthDp in widthsDp) sweepForm(inflater, theme, widthDp)
            }
        }
        report("REPORT/form")
    }

    private fun sweepForm(inflater: LayoutInflater, theme: String, widthDp: Int) {
        val dm = inflater.context.resources.displayMetrics
        val dp = { v: Number -> v.toFloat() * dm.density }
        val where = "report/$theme@${widthDp}dp"

        val root = inflate(inflater, R.layout.fragment_report_problem, dp(widthDp).roundToInt())
        val form = root.find(R.id.report_form)

        /* ---- the band, identical to the settings and profile bands ---- */
        val band = root.find(R.id.report_header)
        val back = root.find(R.id.report_back)
        val title = root.text(R.id.report_title)
        expect(where, "band height", band.height, dp(64))
        expect(where, "back size", back.height, dp(24))
        expect(where, "back x", leftIn(back, root), dp(12))
        // 20 + 24 = 44 of a 64 band, so the back button is vertically centred.
        expect(where, "back y", topIn(back, root), dp(20))
        expect(where, "title x", leftIn(title, root), dp(56))
        assertEquals("Сообщить о проблеме", title.text.toString())

        /* ---- prompt at 80, 16 below the band ---- */
        val prompt = root.text(R.id.report_prompt)
        expect(where, "prompt y", topIn(prompt, root), dp(80))
        expect(where, "prompt x", leftIn(prompt, root), dp(16))

        /* ---- five contiguous category rows: 110 / 174 / 238 / 302 / 366 ---- */
        val rows = categoryRows(root)
        assertEquals("the frozen form has five categories", 5, rows.size)
        // The frozen frame stacks these at a 64 pitch - a zero gap. The owner
        // reversed that as too tight, so they run on the app's own 8dp gap and the
        // pitch is 72. Pinned, because the gap is the whole point of the change.
        val rowY = listOf(110, 182, 254, 326, 398)
        rows.forEachIndexed { i, row ->
            expect(where, "category $i y", topIn(row, root), dp(rowY[i]))
            expect(where, "category $i height", row.height, dp(64))
            expect(where, "category $i x", leftIn(row, root), dp(16))
            if (i > 0) {
                expect(
                    where, "category $i pitch",
                    topIn(row, root) - topIn(rows[i - 1], root), dp(72),
                )
                expect(
                    where, "gap above category $i",
                    topIn(row, root) - (topIn(rows[i - 1], root) + rows[i - 1].height), dp(8),
                )
            }
        }

        // The row's internals - the appearance screen's component, unchanged.
        //
        // These are absolute frame offsets, which is what `leftIn(_, root)`
        // measures: the frozen glyph sits at 16 inside a row that sits at 16, so it
        // is at 32 on the frame. Stating the sum rather than the inner number is
        // what lets a change to either one fail here.
        val icon = root.find(R.id.report_category_0_icon)
        val label = root.text(R.id.report_category_0_label)
        val check = root.find(R.id.report_category_0_check)
        expect(where, "category glyph x", leftIn(icon, root), dp(16 + 16))
        expect(where, "category glyph size", icon.height, dp(24))
        expect(where, "category label x", leftIn(label, root), dp(16 + 56))
        expect(where, "category check size", check.height, dp(24))
        if (widthDp == 390) {
            // 318 + 24 = 342 inside the row, which is the 358 content width less
            // the 16 inset - so 334 on the frame.
            expect(where, "category check x", leftIn(check, root), dp(16 + 318))
        }

        /* ---- description label 446, field 476..596 ---- */
        val descLabel = root.text(R.id.report_description_label)
        val input = root.find(R.id.report_message)
        expect(where, "descLabel y", topIn(descLabel, root), dp(478))
        expect(where, "input y", topIn(input, root), dp(508))
        expect(where, "input height", input.height, dp(120))
        expect(where, "input x", leftIn(input, root), dp(16))

        /* ---- diagnostics card 612..850 ---- */
        val card = root.find(R.id.report_diagnostics)
        expect(where, "diagnostics y", topIn(card, root), dp(644))
        expect(where, "diagnostics height", card.height, dp(238))
        expect(where, "diagnostics x", leftIn(card, root), dp(16))

        val cardTitle = root.text(R.id.report_diagnostics_title)
        expect(where, "diagnostics title y", topIn(cardTitle, root) - topIn(card, root), dp(16))

        // The six lines, measured at the **centre** of each box.
        //
        // The frozen frame draws 20-high text boxes at 50, 74, ... on a 24 pitch;
        // the layout draws 24-high boxes with the text centred, which puts the same
        // text on the same centre without stacking twelve independent roundings
        // (see the dimens note). So the centre is the invariant worth asserting,
        // and 60 + i*24 is the frozen box's own centre.
        val lines = diagnosticLineIds.map { root.text(it) }
        lines.forEachIndexed { i, line ->
            expect(
                where, "diagnostics line $i centre",
                topIn(line, root) - topIn(card, root) + line.height / 2f, dp(60 + i * 24),
            )
        }
        val rule = root.find(R.id.report_diagnostics_rule)
        val notice = root.text(R.id.report_diagnostics_notice)
        expect(where, "notice rule y", topIn(rule, root) - topIn(card, root), dp(194))
        expect(
            where, "notice centre",
            topIn(notice, root) - topIn(card, root) + notice.height / 2f, dp(214),
        )
        assertEquals("Личные данные не отправляются.", notice.text.toString())

        /* ---- the button, resting at 906 ---- */
        val button = root.find(R.id.report_send)
        expect(where, "button y", topIn(button, root), dp(906))
        expect(where, "button height", button.height, dp(52))
        expect(where, "button x", leftIn(button, root), dp(16))

        // 24dp of air under the button, on the SCROLLING content rather than on
        // the ScrollView - so on a short screen it can be scrolled clear of the
        // system navigation area instead of being a dead band beneath it.
        //
        // Asserted structurally here, and as a measured gap in
        // `theButtonKeepsItsAirBelowIt`. It cannot be measured *here*: this sweep
        // measures the root at an artificial 1100dp so the whole form is laid out
        // at once, which leaves the viewport taller than the content, and
        // `fillViewport` then stretches the form to fill it. That stretch is what
        // the first version of this assertion measured - 372px of it.
        expect(where, "air under the button", form.paddingBottom, dp(24))
        val last = (form as ViewGroup).getChildAt(form.childCount - 1)
        if (last !== button) {
            findings += "$where: the button must be the last thing in the form, " +
                "or the padding below it is not below it"
        }

        // The error banner is not drawn in three of the four form states, and takes
        // no space in them: report-empty is 950 tall and report-error 1022, and the
        // 72 between them is this banner plus its gap.
        val banner = root.find(R.id.report_error_banner)
        if (banner.visibility != View.GONE) {
            findings += "$where: the error banner must be gone until a send fails"
        }

        if (widthDp == 390) {
            log += "$where: form ends at ${(topIn(button, root) + button.height) / dm.density}dp"
        }
        // The content is 358 wide on the 390 reference frame, and every plate is
        // full-width, so all of them move together at other widths.
        if (widthDp == 390) {
            listOf(rows[0], input, card, button).forEach {
                expect(where, "content width", it.width, dp(358))
            }
        }
    }

    /**
     * report-error: the banner appears at 906 and the button moves to 978.
     *
     * Measured by making the banner visible and re-measuring, which is exactly what
     * the fragment does - see `ReportProblemFragment.renderBanner`, which also swaps
     * the button's top margin between the two named dimens.
     */
    @Test
    fun theErrorBannerTakesItsFrozenPlaceAndMovesTheButton() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                val inflater = inflaterFor(activity, night)
                val dm = inflater.context.resources.displayMetrics
                val dp = { v: Number -> v.toFloat() * dm.density }
                val where = "report-error/${if (night) "dark" else "light"}"

                val root = inflate(inflater, R.layout.fragment_report_problem, dp(390).roundToInt()) {
                    val banner = it.findViewById<View>(R.id.report_error_banner)
                    banner.visibility = View.VISIBLE
                    val send = it.findViewById<View>(R.id.report_send)
                    (send.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                        it.context.resources
                            .getDimensionPixelSize(R.dimen.report_button_margin_top_after_banner)
                }

                val card = root.find(R.id.report_diagnostics)
                val banner = root.find(R.id.report_error_banner)
                val button = root.find(R.id.report_send)

                expect(where, "banner y", topIn(banner, root), dp(906))
                expect(where, "banner height", banner.height, dp(56))
                expect(where, "banner x", leftIn(banner, root), dp(16))
                expect(
                    where, "gap between the card and the banner",
                    topIn(banner, root) - (topIn(card, root) + card.height), dp(24),
                )
                expect(where, "button y with a banner", topIn(button, root), dp(978))
                expect(
                    where, "gap between the banner and the button",
                    topIn(button, root) - (topIn(banner, root) + banner.height), dp(16),
                )

                // `Отправить ещё раз` gets the same air as `Отправить`, because it
                // is one padding on the content rather than a margin on the button
                // - the two states cannot drift apart. Measured for real in
                // `theButtonKeepsItsAirBelowIt`.
                expect(where, "air under the retry button", root.find(R.id.report_form).paddingBottom, dp(24))

                val msg = root.text(R.id.report_error_message)
                assertEquals("Не удалось отправить сообщение.", msg.text.toString())
                // The glyph at 16 and the sentence at 52 inside a banner that
                // itself sits at 16 - so 68 on the frame.
                expect(where, "banner message x", leftIn(msg, root), dp(16 + 52))
            }
        }
        report("REPORT/error")
    }

    // ==================== report-success ====================

    @Test
    fun theSuccessPanelReproducesItsFrozenFrame() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                val inflater = inflaterFor(activity, night)
                val dm = inflater.context.resources.displayMetrics
                val dp = { v: Number -> v.toFloat() * dm.density }
                val where = "report-success/${if (night) "dark" else "light"}"

                val root = inflate(inflater, R.layout.fragment_report_problem, dp(390).roundToInt()) {
                    it.findViewById<View>(R.id.report_scroll).visibility = View.GONE
                    it.findViewById<View>(R.id.report_success).visibility = View.VISIBLE
                }

                val card = root.find(R.id.report_success_card)
                val badge = root.find(R.id.report_success_badge)
                val headline = root.text(R.id.report_success_headline)
                val body1 = root.text(R.id.report_success_body_1)
                val body2 = root.text(R.id.report_success_body_2)
                val done = root.text(R.id.report_success_done)

                // The card is 268 rather than the frozen 236: the owner asked for
                // air, and each of its four vertical gaps grew by one 8dp step
                // (32->40 padding, 20->28 to the headline, 12->20 to the body,
                // 32->40 below). The card's own 120 anchor and every horizontal
                // number are the frame's, unchanged.
                expect(where, "card y", topIn(card, root), dp(120))
                expect(where, "card height", card.height, dp(268))
                expect(where, "card x", leftIn(card, root), dp(16))
                expect(where, "badge size", badge.height, dp(64))
                expect(where, "badge y", topIn(badge, root) - topIn(card, root), dp(40))
                // Centred in the 358 card: (358 - 64) / 2 = 147.
                expect(where, "badge x", leftIn(badge, root) - leftIn(card, root), dp(147))
                expect(where, "headline y", topIn(headline, root) - topIn(card, root), dp(132))
                expect(
                    where, "gap between the badge and the headline",
                    topIn(headline, root) - (topIn(badge, root) + badge.height), dp(28),
                )
                expect(where, "body y", topIn(body1, root) - topIn(card, root), dp(184))
                expect(
                    where, "gap between the headline and the body",
                    topIn(body1, root) - (topIn(headline, root) + headline.height), dp(20),
                )
                expect(
                    where, "air below the body",
                    (topIn(card, root) + card.height) - (topIn(body2, root) + body2.height), dp(40),
                )
                expect(where, "button y", topIn(done, root), dp(412))
                expect(where, "button height", done.height, dp(52))

                assertEquals("Спасибо!", headline.text.toString())
                assertEquals("Готово", done.text.toString())

                // Owner correction D5. The frozen body promises a Telegram reply,
                // and this flow has no handle, no contact field and no reply channel
                // - so the promise is replaced rather than reproduced. Asserted, so
                // that restoring the frozen sentence is a deliberate act.
                val body = body1.text.toString() + " " + body2.text.toString()
                assertEquals("Сообщение отправлено. Вы помогаете нам улучшать приложение.", body)
                assertTrue(
                    "the success screen must not promise a reply it cannot send",
                    !body.contains("Telegram", ignoreCase = true),
                )
            }
        }
        report("REPORT/success")
    }

    /**
     * The literal claim: from the bottom of the button to the bottom of the
     * scrollable content is 24dp, in both the resting and the error state.
     *
     * Measured at a **realistic** viewport rather than this file's usual 1100dp.
     * The form is ~918dp tall and the sweep's tall measure leaves the viewport
     * taller than that, at which point `fillViewport` stretches the form and the
     * gap under the button becomes the stretch instead of the padding. On a device
     * the content always overflows - 918dp against 731dp on the API 24 emulator -
     * so 640dp here is the honest condition, not a convenient one.
     */
    @Test
    fun theButtonKeepsItsAirBelowIt() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                val inflater = inflaterFor(activity, night)
                val dm = inflater.context.resources.displayMetrics
                val dp = { v: Number -> v.toFloat() * dm.density }

                for (state in listOf("resting", "error")) {
                    val where = "report-$state/${if (night) "dark" else "light"}@640dp-tall"
                    val root = inflate(
                        inflater, R.layout.fragment_report_problem,
                        dp(390).roundToInt(), heightDp = 640,
                    ) {
                        if (state == "error") {
                            it.findViewById<View>(R.id.report_error_banner).visibility = View.VISIBLE
                            val send = it.findViewById<View>(R.id.report_send)
                            (send.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                                it.context.resources
                                    .getDimensionPixelSize(R.dimen.report_button_margin_top_after_banner)
                        }
                    }
                    val form = root.find(R.id.report_form)
                    val button = root.find(R.id.report_send)

                    // The content really does overflow, so `fillViewport` is inert
                    // and the number below is the padding rather than a stretch.
                    val viewport = root.find(R.id.report_scroll).height
                    if (form.height <= viewport) {
                        findings += "$where: the content must overflow for this to mean anything " +
                            "(form ${form.height}, viewport $viewport)"
                    }

                    expect(
                        where, "button bottom to content bottom",
                        (topIn(form, root) + form.height) - (topIn(button, root) + button.height),
                        dp(24),
                    )
                }
            }
        }
        report("REPORT/bottom air")
    }

    /**
     * The two arrangements never share the screen.
     *
     * The form and the success panel are siblings, and exactly one of them is
     * visible - the resting state of the layout is the form, which is what makes
     * `report-empty` the frame the file draws by default.
     */
    @Test
    fun theFormAndTheSuccessPanelAreMutuallyExclusive() {
        onMainActivity { activity ->
            val root = inflate(
                inflaterFor(activity, night = false),
                R.layout.fragment_report_problem,
                (390 * activity.resources.displayMetrics.density).roundToInt(),
            )
            assertEquals(View.VISIBLE, root.find(R.id.report_scroll).visibility)
            assertEquals(View.GONE, root.find(R.id.report_success).visibility)
        }
    }

    // ==================== harness ====================

    private val diagnosticLineIds = listOf(
        R.id.report_diagnostics_line_0, R.id.report_diagnostics_line_1,
        R.id.report_diagnostics_line_2, R.id.report_diagnostics_line_3,
        R.id.report_diagnostics_line_4, R.id.report_diagnostics_line_5,
    )

    private val categoryRowIds = listOf(
        R.id.report_category_0, R.id.report_category_1, R.id.report_category_2,
        R.id.report_category_3, R.id.report_category_4,
    )

    private fun categoryRows(root: View): List<View> = categoryRowIds.map { root.find(it) }

    /** SettingsLayoutTest's own harness, unchanged - including its close-timeout guard. */
    private fun onMainActivity(body: (MainActivity) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity(body)
        } finally {
            try {
                scenario.close()
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "activity close timed out", e)
            }
        }
    }

    private fun inflaterFor(activity: MainActivity, night: Boolean): LayoutInflater {
        val config = android.content.res.Configuration(activity.resources.configuration)
        config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES
            else android.content.res.Configuration.UI_MODE_NIGHT_NO
        val themed = activity.createConfigurationContext(config)
        themed.setTheme(R.style.AppTheme)
        return activity.layoutInflater.cloneInContext(themed)
    }

    /**
     * Inflates and measures through a parent rather than a null root, for the
     * reason SleepTimerSurfacesTest gives: a null root discards the layout's own
     * `layout_width`.
     *
     * The height spec is EXACTLY rather than AT_MOST: this screen's root is
     * `match_parent` with a weighted ScrollView in it, so an unbounded height would
     * let the scroll take its content's whole height and every offset below the
     * fold would be measured against a viewport that does not exist on a device.
     * 950 is `report-empty`'s own frame height.
     */
    private fun inflate(
        inflater: LayoutInflater,
        layout: Int,
        widthPx: Int,
        heightDp: Int = 1100,
        prepare: (View) -> Unit = {},
    ): View {
        val parent = android.widget.FrameLayout(inflater.context)
        val root = inflater.inflate(layout, parent, false)
        prepare(root)
        val density = inflater.context.resources.displayMetrics.density
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((heightDp * density).roundToInt(), View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    private fun View.find(id: Int): View = findViewById(id)
        ?: throw AssertionError("no view with id ${resources.getResourceEntryName(id)}")

    private fun View.text(id: Int): TextView = find(id) as TextView

    private fun topIn(view: View, root: View): Int {
        var y = 0
        var v: View? = view
        while (v != null && v !== root) {
            y += v.top
            v = v.parent as? View
        }
        return y
    }

    private fun leftIn(view: View, root: View): Int {
        var x = 0
        var v: View? = view
        while (v != null && v !== root) {
            x += v.left
            v = v.parent as? View
        }
        return x
    }

    private fun expect(where: String, what: String, actual: Number, expected: Number, tolerance: Float = 1.5f) {
        if (abs(actual.toFloat() - expected.toFloat()) > tolerance) {
            findings += "$where: $what is $actual, frozen is $expected"
        }
    }

    private fun report(label: String) {
        android.util.Log.i(TAG, "==== $label (API ${Build.VERSION.SDK_INT}) ====")
        log.forEach { android.util.Log.i(TAG, "  $it") }
        findings.forEach { android.util.Log.e(TAG, "  FINDING $it") }
        assertTrue(
            "$label findings on API ${Build.VERSION.SDK_INT}:\n" +
                findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }

    private companion object {
        const val TAG = "ReportProblemLayout"
    }
}
