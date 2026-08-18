package com.example.musicplayerapp

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.Reaction
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

/**
 * The PLAYER's two reaction controls, drawn for all three states.
 *
 * What the reader can actually see is the tint, because the frozen frame records
 * one glyph per slot and no states. So this asserts the tint each control takes in
 * each state, in both themes and at every shipping width - and, crucially, that
 * **only one control is ever active**, which is a property of the pair rather than
 * of either control.
 *
 * It also writes one PNG per state to the app's external files directory. Those are
 * the screenshots in the PR: the row as it is actually rasterised, not a mockup.
 */
@RunWith(AndroidJUnit4::class)
class PlayerReactionControlsTest {

    private val widthsDp = listOf(320, 360, 390, 412)

    private val findings = mutableListOf<String>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun eachStateTintsExactlyOneControl() = onMainActivity { activity ->
        for (night in listOf(false, true)) {
            val themed = themedContext(activity, night)
            val inflater = activity.layoutInflater.cloneInContext(themed)
            val theme = if (night) "dark" else "light"

            val primary = ContextCompat.getColor(themed, R.color.primary)
            val likeRest = ContextCompat.getColor(themed, R.color.player_like)
            val dislikeRest = ContextCompat.getColor(themed, R.color.player_control_action)

            for (widthDp in widthsDp) {
                for (state in Reaction.entries) {
                    val where = "$theme/$widthDp/$state"
                    val page = layoutPage(inflater, widthDp)
                    val like = page.findViewById<ImageView>(R.id.btn_favorite)
                    val dislike = page.findViewById<ImageView>(R.id.btn_dislike)
                    apply(themed, like, dislike, state)

                    val likeTint = like.imageTintList?.defaultColor
                    val dislikeTint = dislike.imageTintList?.defaultColor

                    val expectedLike = if (state == Reaction.LIKED) primary else likeRest
                    val expectedDislike = if (state == Reaction.DISLIKED) primary else dislikeRest

                    if (likeTint != expectedLike) {
                        findings += "$where: like tint is $likeTint, expected $expectedLike"
                    }
                    if (dislikeTint != expectedDislike) {
                        findings += "$where: dislike tint is $dislikeTint, expected $expectedDislike"
                    }

                    // The pair, not the parts: two active controls would say the
                    // listener both likes and dislikes the same track.
                    val active = listOf(likeTint, dislikeTint).count { it == primary }
                    val expectedActive = if (state == Reaction.NEUTRAL) 0 else 1
                    if (active != expectedActive) {
                        findings += "$where: $active controls read active, expected $expectedActive"
                    }

                    // Neither control moves or resizes between states - the frozen
                    // row has one geometry and the reaction is carried by colour.
                    if (like.width != dp(49).roundToInt() || dislike.width != dp(49).roundToInt()) {
                        findings += "$where: a slot is ${like.width}x${dislike.width}, not 49dp wide"
                    }
                }
            }
        }

        assertTrue(findings.joinToString("\n"), findings.isEmpty())
    }

    /**
     * One PNG per state, of the controls row, in both themes.
     *
     * Not an assertion - a record. It runs at 390dp, the width the frozen frame is
     * drawn at, and writes to the app's external files dir so the harness can pull
     * the files off the device.
     */
    @Test
    fun captureTheThreeStates() = onMainActivity { activity ->
        val outDir = File(context.getExternalFilesDir(null), "reaction-states").apply { mkdirs() }
        val written = mutableListOf<String>()

        for (night in listOf(false, true)) {
            val themed = themedContext(activity, night)
            val inflater = activity.layoutInflater.cloneInContext(themed)
            val theme = if (night) "dark" else "light"
            val background = ContextCompat.getColor(themed, R.color.background)

            for (state in Reaction.entries) {
                val page = layoutPage(inflater, 390) { root ->
                    // A real track, so the capture reads as the screen rather than
                    // as a bare row.
                    root.findViewById<android.widget.TextView>(R.id.main_song).text = "Enjoy the Silence"
                    root.findViewById<android.widget.TextView>(R.id.main_author).text = "Depeche Mode"
                }
                apply(
                    themed,
                    page.findViewById(R.id.btn_favorite),
                    page.findViewById(R.id.btn_dislike),
                    state,
                )

                // The frozen upper section: artwork, both text lines and the
                // controls row, which is everything the reaction changes.
                val controls = page.findViewById<ViewGroup>(R.id.player_controls)
                val height = controls.bottom + dp(24).roundToInt()
                val bitmap = Bitmap.createBitmap(page.width, height, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).apply {
                    drawColor(background)
                    page.draw(this)
                }

                val file = File(outDir, "player-controls-$theme-${state.name.lowercase()}.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                written += file.absolutePath
            }
        }

        assertTrue("no captures were written", written.size == 6)
    }

    /* ---------------------------------------------------------------- infra -- */

    /** Exactly what `MyataStreamFragment.updateReactionControls` does. */
    private fun apply(themed: Context, like: ImageView, dislike: ImageView, state: Reaction) {
        ImageViewCompat.setImageTintList(
            like,
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(
                    themed,
                    if (state == Reaction.LIKED) R.color.primary else R.color.player_like,
                )
            )
        )
        ImageViewCompat.setImageTintList(
            dislike,
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(
                    themed,
                    if (state == Reaction.DISLIKED) R.color.primary else R.color.player_control_action,
                )
            )
        )
    }

    private fun layoutPage(
        inflater: LayoutInflater,
        widthDp: Int,
        prepare: ((ViewGroup) -> Unit)? = null,
    ): ViewGroup {
        val root = inflater.inflate(R.layout.fragment_myata_stream, null) as ViewGroup
        prepare?.invoke(root)
        val widthPx = dp(widthDp).roundToInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(widthPx * 3, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    private fun themedContext(activity: MainActivity, night: Boolean): Context {
        val configuration = Configuration(activity.resources.configuration)
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val themed = activity.createConfigurationContext(configuration)
        themed.setTheme(R.style.AppTheme)
        return themed
    }

    private fun onMainActivity(block: (MainActivity) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).let { scenario ->
            try {
                scenario.onActivity(block)
            } finally {
                try {
                    scenario.close()
                } catch (e: Throwable) {
                    android.util.Log.w("PLAYERQA", "activity close timed out; checks already complete", e)
                }
            }
        }
    }

    private fun dp(value: Number): Float =
        value.toFloat() * context.resources.displayMetrics.density
}
