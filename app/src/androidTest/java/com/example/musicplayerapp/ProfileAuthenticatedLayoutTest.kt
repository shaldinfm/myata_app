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
 * profile-authenticated against its frames, measured rather than eyeballed.
 *
 * 2517:2671 / 2517:3638, a fixed 390x692:
 *
 *     Header - TopAppBar   0..64
 *     Account card        96..200   358x104, r24
 *     Синхронизация      224..244
 *     Row cloud          252..324   358x72
 *     Row last sync      332..404   358x72
 *     Аккаунт            428..448
 *     Row Аватар         456..520   358x64
 *     Row Сменить        528..592   358x64
 *     Row Выйти          600..664   358x64
 *
 * Built the way `AuthLayoutTest` is, and for the same reasons: the chain of gaps and
 * boxes is asserted rather than the absolute offsets, because Android rounds each dp
 * to a whole pixel independently and nine rows of that accumulate into a drift that
 * says nothing about the layout. The one absolute anchor is the first content row,
 * which has two rounded boundaries above it and is therefore exact.
 *
 * Colour is checked alongside geometry, per theme, because a screen drawn entirely
 * in the wrong palette would pass a purely dimensional sweep.
 */
@RunWith(AndroidJUnit4::class)
class ProfileAuthenticatedLayoutTest {

    private val widthsDp = listOf(320, 360, 390, 412)
    private val designWidthDp = 390

    private val findings = mutableListOf<String>()
    private val log = mutableListOf<String>()

    @Test
    fun profileAuthenticatedReproducesTheFrozenFrame() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                sweep(inflaterFor(activity, night), if (night) "dark" else "light")
            }
        }

        android.util.Log.i(TAG, "==== PROFILE-AUTH (API ${Build.VERSION.SDK_INT}) ====")
        log.forEach { android.util.Log.i(TAG, "  $it") }
        findings.forEach { android.util.Log.e(TAG, "  FINDING $it") }

        assertTrue(
            "PROFILE-AUTH findings on API ${Build.VERSION.SDK_INT}:\n" +
                findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }

    private fun sweep(inflater: LayoutInflater, theme: String) {
        val dm = inflater.context.resources.displayMetrics
        val dp = { v: Number -> v.toFloat() * dm.density }
        val ctx = inflater.context

        for (widthDp in widthsDp) {
            val root = measured(inflater, dp(widthDp).roundToInt())
            val where = "profile-auth/$theme@${widthDp}dp"

            val band = root.find(R.id.profile_header)
            val title = root.text(R.id.profile_title)
            val back = root.find(R.id.profile_back)
            val card = root.find(R.id.profile_account_card)
            val avatar = root.find(R.id.profile_account_avatar)
            val name = root.text(R.id.profile_account_name)
            val email = root.text(R.id.profile_account_email)
            val syncLabel = root.text(R.id.profile_section_sync)
            val cloud = root.find(R.id.profile_row_cloud_sync)
            val last = root.find(R.id.profile_row_last_sync)
            val accountLabel = root.text(R.id.profile_section_account)
            val rowAvatar = root.find(R.id.profile_row_avatar)
            val rowPassword = root.find(R.id.profile_row_change_password)
            val rowSignOut = root.find(R.id.profile_row_sign_out)
            val signOutLabel = root.text(R.id.profile_row_sign_out_label)

            /* ---- the band, identical to profile-guest's ---- */
            expect(where, "header band height", band.height, dp(64))
            expect(where, "back icon size", back.width, dp(24))
            expect(where, "back icon x", leftInRoot(back), dp(12))
            expect(where, "heading gap after the back icon",
                leftInRoot(title) - (leftInRoot(back) + back.width), dp(20))

            /* ---- fixed geometry, at every width ---- */
            expect(where, "account card height", card.height, dp(104))
            expect(where, "avatar circle", avatar.width, dp(64))
            expect(where, "avatar circle height", avatar.height, dp(64))
            expect(where, "avatar x in the card", leftInRoot(avatar) - leftInRoot(card), dp(16))
            expect(where, "name x after the avatar",
                leftInRoot(name) - (leftInRoot(avatar) + avatar.width), dp(16))

            for ((label, row) in listOf("cloud" to cloud, "last sync" to last)) {
                expect(where, "$label row height", row.height, dp(72))
            }
            for ((label, row) in listOf(
                "Аватар" to rowAvatar, "Сменить пароль" to rowPassword, "Выйти" to rowSignOut,
            )) {
                expect(where, "$label row height", row.height, dp(64))
            }

            for ((label, v) in listOf("card" to card, "cloud" to cloud, "last sync" to last,
                    "Аватар" to rowAvatar, "Сменить" to rowPassword, "Выйти" to rowSignOut)) {
                expect(where, "$label x", leftInRoot(v), dp(16))
                expect(where, "$label width", v.width, dp(widthDp - 32))
            }

            /* ---- the chain of gaps ---- */
            expect(where, "band to card", gap(band, card), dp(32))
            expect(where, "card to Синхронизация", gap(card, syncLabel), dp(24))
            expect(where, "Синхронизация to cloud row", gap(syncLabel, cloud), dp(8))
            expect(where, "cloud to last sync", gap(cloud, last), dp(8))
            expect(where, "last sync to Аккаунт", gap(last, accountLabel), dp(24))
            expect(where, "Аккаунт to Аватар", gap(accountLabel, rowAvatar), dp(8))
            expect(where, "Аватар to Сменить", gap(rowAvatar, rowPassword), dp(8))
            expect(where, "Сменить to Выйти", gap(rowPassword, rowSignOut), dp(8))

            if (widthDp == designWidthDp) {
                expect(where, "card y", topInRoot(card), dp(96))
                expect(where, "Синхронизация box", syncLabel.height, dp(20))
                expect(where, "Аккаунт box", accountLabel.height, dp(20))

                log += "$where content ends at " +
                    "${"%.1f".format((topInRoot(rowSignOut) + rowSignOut.height) / dm.density)}dp " +
                    "in a 692dp frame"
            }

            /* ---- type ---- */
            type(where, "heading", title, 24f, 32f, dm)
            type(where, "name", name, 17f, 28f, dm)
            type(where, "email", email, 14f, 20f, dm)
            type(where, "Синхронизация", syncLabel, 14f, 20f, dm)
            type(where, "Выйти", signOutLabel, 16f, 22f, dm)

            /* ---- colour ---- */
            colour(where, "heading", title, ctx.tone(R.color.text_heading))
            colour(where, "name", name, ctx.tone(R.color.text_primary))
            colour(where, "email", email, ctx.tone(R.color.text_secondary))
            colour(where, "Синхронизация", syncLabel, ctx.tone(R.color.profile_section_label))
            // The destructive row is the `error` token in both frames, and it is the
            // only thing on this screen that is.
            colour(where, "Выйти", signOutLabel, ctx.tone(R.color.error))
        }
    }

    // ==================== helpers ====================

    private fun ViewGroup.find(id: Int): View = findViewById(id)
    private fun ViewGroup.text(id: Int): TextView = findViewById(id)
    private fun android.content.Context.tone(id: Int): Int = resources.getColor(id, theme)
    private fun Int.hex(): String = String.format("#%08X", this)
    private fun gap(a: View, b: View): Int = topInRoot(b) - (topInRoot(a) + a.height)

    private fun type(
        where: String, what: String, tv: TextView,
        sizeSp: Float, lineSp: Float, dm: android.util.DisplayMetrics,
    ) {
        expect(where, "$what text size", tv.textSize.roundToInt(), sizeSp * dm.scaledDensity)
        expect(where, "$what line height", tv.lineHeight, lineSp * dm.density)
    }

    private fun colour(where: String, what: String, tv: TextView, expected: Int) {
        if (tv.currentTextColor != expected) {
            findings += "$where: $what is ${tv.currentTextColor.hex()}, frame says ${expected.hex()}"
        }
    }

    private fun measured(inflater: LayoutInflater, widthPx: Int): ViewGroup {
        val root = inflater.inflate(R.layout.fragment_profile_authenticated, null) as ViewGroup
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
        val scenario = ActivityScenario.launch(MainActivity::class.java)
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

    private companion object {
        const val TAG = "PROFILEQA"
    }
}
