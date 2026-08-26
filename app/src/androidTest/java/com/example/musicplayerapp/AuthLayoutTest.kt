package com.example.musicplayerapp

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two auth screens against their authoritative frames, measured rather than
 * eyeballed.
 *
 * auth-sign-in 2517:2603 / 2517:3570 is a fixed 390x576, auth-create-account
 * 2517:2624 / 2517:3591 a fixed 390x596, and their anchors are the design:
 *
 *     sign-in                        create-account
 *     Header - TopAppBar 0..64       Header - TopAppBar 0..64
 *     b0                92..114      Label Имя         92..112
 *     b1               114..136      Input Имя        116..172
 *     Label Email      156..176      Label Email      188..208
 *     Input Email      180..236      Input Email      212..268
 *     Label Пароль     252..272      Label Пароль     284..304
 *     Input Пароль     276..332      Input Пароль     308..364
 *     Забыли пароль?   344..364      Минимум 8        372..392
 *     Войти            388..440      Создать аккаунт  412..464
 *     Создать аккаунт  456..508      Уже есть аккаунт 488..508
 *     Продолжить       532..552
 *
 * ## The chain is asserted, not the absolute offsets
 *
 * Every row height and every gap between rows is checked to the pixel, at every
 * shipping width. The absolute `y` of each row follows from those by construction,
 * and is deliberately **not** asserted below the first one.
 *
 * That is not a softening. Android resolves each dp independently and rounds each to
 * a whole pixel, and at this AVD's density almost every number in these screens
 * lands on exactly half a pixel: 20dp is 52.5px and becomes 53, 4dp is 10.5 and
 * becomes 11. Nineteen such boundaries stack above the last control on auth-sign-in,
 * so its absolute offset arrives about 5px - two dp - below where multiplying 532 by
 * the density says it should be, on a screen where every single gap is exactly
 * right. Which way each one rounds depends on the density, so the same layout drifts
 * differently on a 443dpi device and not at all on a 320dpi one. Asserting the
 * absolute offsets would mean either a tolerance loose enough to hide a real 3dp
 * mistake, or a test that fails on some densities and passes on others.
 *
 * The chain has neither problem: a wrong margin or a wrong box is off by a whole dp
 * at least, which is caught at every width. The measured absolute offsets are logged
 * under `AUTHQA` so they can still be read off a run.
 *
 * The one exception is the first content row, which has two boundaries above it and
 * is therefore exact - and it is the row that anchors everything else.
 *
 * ## Why the full stack is only measured at 390dp
 *
 * The same reason HOME's is. Away from the reference width the strings occupy more
 * lines - `Войдите, чтобы коллекция синхронизировалась` is the longest line in
 * either screen and wraps below 390dp - and everything under a wrapped line moves
 * down. That is the design working, not breaking.
 *
 * ## It checks colour and type too
 *
 * Geometry alone would pass a screen drawn entirely in the wrong palette. Both
 * themes are inflated from a themed context, so every assertion below runs twice and
 * the light/dark token pairs are checked against the frames rather than assumed to
 * follow from `values-night` existing.
 */
@RunWith(AndroidJUnit4::class)
class AuthLayoutTest {

    private val widthsDp = listOf(320, 360, 390, 412)
    private val designWidthDp = 390

    private val findings = mutableListOf<String>()
    private val log = mutableListOf<String>()

    @Test
    fun authScreensReproduceTheFrozenFrames() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                val theme = if (night) "dark" else "light"
                sweepSignIn(inflaterFor(activity, night), theme, night)
                sweepCreateAccount(inflaterFor(activity, night), theme, night)
            }
        }

        android.util.Log.i(TAG, "==== AUTH (API ${Build.VERSION.SDK_INT}) ====")
        log.forEach { android.util.Log.i(TAG, "  $it") }
        findings.forEach { android.util.Log.e(TAG, "  FINDING $it") }

        assertTrue(
            "AUTH findings on API ${Build.VERSION.SDK_INT}:\n" + findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }

    // ==================== auth-sign-in ====================

    private fun sweepSignIn(inflater: LayoutInflater, theme: String, night: Boolean) {
        val dm = inflater.context.resources.displayMetrics
        val dp = { v: Number -> v.toFloat() * dm.density }

        for (widthDp in widthsDp) {
            val widthPx = dp(widthDp).roundToInt()
            val root = measured(inflater, R.layout.fragment_auth_sign_in, widthPx)
            val where = "sign-in/$theme@${widthDp}dp"

            val band = root.find(R.id.auth_header)
            val title = root.text(R.id.auth_title)
            val back = root.find(R.id.auth_back)
            val b0 = root.text(R.id.auth_body_first)
            val b1 = root.text(R.id.auth_body_second)
            val emailLabel = root.text(R.id.auth_email_label)
            val email = root.find(R.id.auth_email)
            val passwordLabel = root.text(R.id.auth_password_label)
            val password = root.find(R.id.auth_password)
            val forgot = root.text(R.id.auth_forgot_password)
            val submit = root.find(R.id.auth_submit)
            val create = root.text(R.id.auth_create_account)
            val guest = root.text(R.id.auth_continue_as_guest)

            /* ---- the band, identical to profile-guest's ---- */
            expect(where, "header band height", band.height, dp(64))
            expect(where, "back icon size", back.width, dp(24))
            expect(where, "back icon x", leftInRoot(back), dp(12))
            // The frame puts the heading at x=56, which is 12 + 24 + 20 - three dp
            // boundaries, each rounded to a whole pixel independently. On the API 24
            // image they round the other way and the sum lands a pixel short of
            // 56 x density while every one of the three is exactly right. So the gap
            // is asserted rather than the sum, for the same reason the vertical
            // chain is.
            expect(where, "heading gap after the back icon",
                leftInRoot(title) - (leftInRoot(back) + back.width), dp(20))

            /* ---- fixed control geometry, at every width ---- */
            expect(where, "email input height", email.height, dp(56))
            expect(where, "password input height", password.height, dp(56))
            expect(where, "Войти height", submit.height, dp(52))
            expect(where, "Создать аккаунт height", create.height, dp(52))

            for ((name, v) in listOf("email input" to email, "password input" to password,
                    "Войти" to submit, "Создать аккаунт" to create)) {
                expect(where, "$name x", leftInRoot(v), dp(16))
                expect(where, "$name width", v.width, dp(widthDp - 32))
            }

            /* ---- the gaps between rows that cannot wrap ---- */
            expect(where, "label to its input", gap(emailLabel, email), dp(4))
            expect(where, "password label to its input", gap(passwordLabel, password), dp(4))
            expect(where, "email input to password label", gap(email, passwordLabel), dp(16))
            expect(where, "password input to Забыли пароль?", gap(password, forgot), dp(12))
            expect(where, "Забыли пароль? to Войти", gap(forgot, submit), dp(24))
            expect(where, "Войти to Создать аккаунт", gap(submit, create), dp(16))
            expect(where, "Создать аккаунт to Продолжить", gap(create, guest), dp(24))

            /* ---- the row boxes, which are what the gaps are measured between ---- */
            expect(where, "band to b0", gap(band, b0), dp(28))
            expect(where, "b0 to b1", gap(b0, b1), dp(0))
            expect(where, "b1 to Label Email", gap(b1, emailLabel), dp(20))

            /* ---- the whole stack, at the width the frame is drawn at ---- */
            if (widthDp == designWidthDp) {
                // The anchor everything else hangs from. Two rounded boundaries above
                // it, so unlike the rows below it this one is exact.
                expect(where, "b0 y", topInRoot(b0), dp(92))

                // Every box the frame gives a fixed height to. With these and the
                // gaps above, the whole column is pinned without asking any absolute
                // offset to survive nineteen roundings.
                expect(where, "b0 box", b0.height, dp(22))
                expect(where, "b1 box", b1.height, dp(22))
                expect(where, "Label Email box", emailLabel.height, dp(20))
                expect(where, "Label Пароль box", passwordLabel.height, dp(20))
                expect(where, "Забыли пароль? box", forgot.height, dp(20))
                expect(where, "Продолжить box", guest.height, dp(20))

                log += table(where, dm, listOf(
                    "b0" to b0, "b1" to b1, "Label Email" to emailLabel, "Input Email" to email,
                    "Label Пароль" to passwordLabel, "Input Пароль" to password,
                    "Забыли пароль?" to forgot, "Войти" to submit,
                    "Создать аккаунт" to create, "Продолжить" to guest,
                ), frameHeightDp = 576)
            }

            /* ---- type ---- */
            type(where, "heading", title, 24f, 32f, dm)
            type(where, "body", b0, 15f, 22f, dm)
            type(where, "Label Email", emailLabel, 14f, 20f, dm)
            type(where, "Забыли пароль?", forgot, 14f, 20f, dm)
            type(where, "Войти", root.text(R.id.auth_submit_label), 21f, 28f, dm)
            type(where, "Создать аккаунт", create, 21f, 28f, dm)
            type(where, "Продолжить", guest, 14f, 20f, dm)

            /* ---- colour ---- */
            val ctx = inflater.context
            colour(where, "heading", title, ctx.tone(R.color.text_heading))
            colour(where, "body", b0, ctx.tone(R.color.text_secondary))
            colour(where, "Label Email", emailLabel, ctx.tone(R.color.text_secondary))
            colour(where, "Забыли пароль?", forgot, ctx.tone(R.color.primary))
            colour(where, "Войти label", root.text(R.id.auth_submit_label),
                ctx.tone(R.color.profile_primary_button_label))
            colour(where, "Создать аккаунт label", create, ctx.tone(R.color.text_heading))
            colour(where, "Продолжить", guest, ctx.tone(R.color.primary))

            /* ---- the resting state has no error rows and no spinner ---- */
            for (id in listOf(R.id.auth_email_error, R.id.auth_password_error, R.id.auth_form_error,
                    R.id.auth_submit_progress)) {
                if (root.find(id).visibility != View.GONE) {
                    findings += "$where: ${nameOf(root.find(id))} must be GONE at rest"
                }
            }

            if (!night && widthDp == designWidthDp) {
                log += "sign-in light@390: heading ${title.currentTextColor.hex()}, " +
                    "body ${b0.currentTextColor.hex()}, link ${forgot.currentTextColor.hex()}"
            }
        }
    }

    // ==================== auth-create-account ====================

    private fun sweepCreateAccount(inflater: LayoutInflater, theme: String, night: Boolean) {
        val dm = inflater.context.resources.displayMetrics
        val dp = { v: Number -> v.toFloat() * dm.density }

        for (widthDp in widthsDp) {
            val widthPx = dp(widthDp).roundToInt()
            val root = measured(inflater, R.layout.fragment_auth_create_account, widthPx)
            val where = "create/$theme@${widthDp}dp"

            val band = root.find(R.id.auth_header)
            val title = root.text(R.id.auth_title)
            val nameLabel = root.text(R.id.auth_name_label)
            val name = root.find(R.id.auth_name)
            val emailLabel = root.text(R.id.auth_email_label)
            val email = root.find(R.id.auth_email)
            val passwordLabel = root.text(R.id.auth_password_label)
            val password = root.find(R.id.auth_password)
            val rule = root.text(R.id.auth_password_rule)
            val submit = root.find(R.id.auth_submit)
            val have = root.text(R.id.auth_have_account)

            expect(where, "header band height", band.height, dp(64))
            expect(where, "heading gap after the back icon",
                leftInRoot(title) - (leftInRoot(root.find(R.id.auth_back)) + root.find(R.id.auth_back).width),
                dp(20))

            for ((label, input) in listOf(nameLabel to name, emailLabel to email, passwordLabel to password)) {
                expect(where, "${label.text} input height", input.height, dp(56))
                expect(where, "${label.text} input x", leftInRoot(input), dp(16))
                expect(where, "${label.text} input width", input.width, dp(widthDp - 32))
                expect(where, "${label.text} label to its input", gap(label, input), dp(4))
            }

            expect(where, "Создать аккаунт height", submit.height, dp(52))
            expect(where, "Имя input to Label Email", gap(name, emailLabel), dp(16))
            expect(where, "Email input to Label Пароль", gap(email, passwordLabel), dp(16))
            expect(where, "Пароль input to Минимум 8", gap(password, rule), dp(8))
            expect(where, "Минимум 8 to Создать аккаунт", gap(rule, submit), dp(20))
            expect(where, "Создать аккаунт to Уже есть аккаунт", gap(submit, have), dp(24))

            expect(where, "band to Label Имя", gap(band, nameLabel), dp(28))

            if (widthDp == designWidthDp) {
                expect(where, "Label Имя y", topInRoot(nameLabel), dp(92))

                expect(where, "Label Имя box", nameLabel.height, dp(20))
                expect(where, "Label Email box", emailLabel.height, dp(20))
                expect(where, "Label Пароль box", passwordLabel.height, dp(20))
                expect(where, "Минимум 8 символов box", rule.height, dp(20))
                expect(where, "Уже есть аккаунт box", have.height, dp(20))

                log += table(where, dm, listOf(
                    "Label Имя" to nameLabel, "Input Имя" to name,
                    "Label Email" to emailLabel, "Input Email" to email,
                    "Label Пароль" to passwordLabel, "Input Пароль" to password,
                    "Минимум 8 символов" to rule, "Создать аккаунт" to submit,
                    "Уже есть аккаунт" to have,
                ), frameHeightDp = 596)
            }

            type(where, "heading", title, 24f, 32f, dm)
            type(where, "Label Имя", nameLabel, 14f, 20f, dm)
            type(where, "Минимум 8 символов", rule, 14f, 20f, dm)
            type(where, "Создать аккаунт", root.text(R.id.auth_submit_label), 21f, 28f, dm)
            type(where, "Уже есть аккаунт", have, 14f, 20f, dm)

            val ctx = inflater.context
            colour(where, "heading", title, ctx.tone(R.color.text_heading))
            colour(where, "Label Имя", nameLabel, ctx.tone(R.color.text_secondary))
            colour(where, "Минимум 8 символов", rule, ctx.tone(R.color.text_secondary))
            colour(where, "Уже есть аккаунт", have, ctx.tone(R.color.primary))
            colour(where, "Создать аккаунт label", root.text(R.id.auth_submit_label),
                ctx.tone(R.color.profile_primary_button_label))

            for (id in listOf(R.id.auth_name_error, R.id.auth_email_error, R.id.auth_password_error,
                    R.id.auth_form_error, R.id.auth_submit_progress)) {
                if (root.find(id).visibility != View.GONE) {
                    findings += "$where: ${nameOf(root.find(id))} must be GONE at rest"
                }
            }
        }
    }

    // ==================== helpers ====================

    private fun ViewGroup.find(id: Int): View = findViewById(id)

    private fun ViewGroup.text(id: Int): TextView = findViewById(id)

    private fun android.content.Context.tone(id: Int): Int = resources.getColor(id, theme)

    private fun Int.hex(): String = String.format("#%08X", this)

    /**
     * The measured column, in dp, for the log.
     *
     * Not an assertion. It is what a reviewer compares against the frame by eye when
     * a screenshot looks a pixel out, and what shows at a glance that a drift is the
     * density's rounding rather than a wrong margin - the numbers land within a dp or
     * two of the frame and the gaps between them are exact.
     */
    private fun table(
        where: String,
        dm: android.util.DisplayMetrics,
        rows: List<Pair<String, View>>,
        frameHeightDp: Int,
    ): String {
        val d = { px: Int -> String.format("%.1f", px / dm.density) }
        val body = rows.joinToString("; ") { (name, v) ->
            "$name ${d(topInRoot(v))}..${d(topInRoot(v) + v.height)}"
        }
        val last = rows.last().second
        return "$where  $body  | content ends at " +
            "${d(topInRoot(last) + last.height)}dp in a ${frameHeightDp}dp frame"
    }

    /** The vertical gap between the bottom of [a] and the top of [b]. */
    private fun gap(a: View, b: View): Int = topInRoot(b) - (topInRoot(a) + a.height)

    private fun type(where: String, what: String, tv: TextView, sizeSp: Float, lineSp: Float, dm: android.util.DisplayMetrics) {
        expect(where, "$what text size", tv.textSize.roundToInt(), sizeSp * dm.scaledDensity)
        expect(where, "$what line height", tv.lineHeight, lineSp * dm.density)
    }

    private fun colour(where: String, what: String, tv: TextView, expected: Int) {
        if (tv.currentTextColor != expected) {
            findings += "$where: $what is ${tv.currentTextColor.hex()}, frame says ${expected.hex()}"
        }
    }

    private fun measured(inflater: LayoutInflater, layout: Int, widthPx: Int): ViewGroup {
        val root = inflater.inflate(layout, null) as ViewGroup
        // A real viewport height. Measuring UNSPECIFIED would hand the ScrollView an
        // unbounded one and make every number fiction the moment a screen scrolls.
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(widthPx * 2, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
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
        ActivityScenario.launch(MainActivity::class.java).let { scenario ->
            try {
                scenario.onActivity(block)
            } finally {
                try {
                    scenario.close()
                } catch (e: Throwable) {
                    android.util.Log.w(TAG, "activity close timed out; checks already complete", e)
                }
            }
        }
    }

    private fun offsetToRoot(v: View, r: Rect) {
        var p = v.parent
        while (p is View) { r.offset(p.left, p.top); p = p.parent }
    }

    private fun topInRoot(v: View): Int =
        Rect(v.left, v.top, v.right, v.bottom).also { offsetToRoot(v, it) }.top

    private fun leftInRoot(v: View): Int =
        Rect(v.left, v.top, v.right, v.bottom).also { offsetToRoot(v, it) }.left

    private fun expect(where: String, what: String, actual: Int, expected: Float) {
        if (abs(actual - expected) > 1f) {
            findings += "$where: $what is ${actual}px, frozen design says ${expected.roundToInt()}px"
        }
    }

    private fun nameOf(v: View): String = try {
        if (v.id == View.NO_ID) v.javaClass.simpleName else v.resources.getResourceEntryName(v.id)
    } catch (e: android.content.res.Resources.NotFoundException) {
        v.javaClass.simpleName
    }

    private companion object {
        const val TAG = "AUTHQA"
    }
}
