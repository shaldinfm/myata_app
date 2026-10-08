package com.example.musicplayerapp

import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.Reaction
import com.example.musicplayerapp.ui.PlayerReactionControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reaction a listener sets stays on the control after the touch is over.
 *
 * The 3.6.6 report: tap Like, the glyph shows a colour while the finger is down, then it
 * is grey again - although the Like was saved. These run on attached views, in the real
 * activity, so the press animator and the tint fade both actually run.
 */
@RunWith(AndroidJUnit4::class)
class PlayerReactionPressTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun likeAndDislikeKeepTheirActiveStateAfterThePressEnds() = onPage { like, dislike, colors ->
        render(like, dislike, Reaction.LIKED)
        press(like)
        assertState("like after its press", like, dislike, colors, Reaction.LIKED)
        assertEquals("the press must return to full size", 1f, like.scaleX, 0.001f)

        render(like, dislike, Reaction.DISLIKED)
        press(dislike)
        assertState("dislike after its press", like, dislike, colors, Reaction.DISLIKED)
        assertEquals(1f, dislike.scaleX, 0.001f)
    }

    @Test
    fun everyTransitionEndsWithOnlyItsOwnControlActive() = onPage { like, dislike, colors ->
        val path = listOf(
            Reaction.LIKED,     // NONE -> LIKE
            Reaction.NEUTRAL,   // LIKE -> tap like again
            Reaction.DISLIKED,  // NONE -> DISLIKE
            Reaction.NEUTRAL,   // DISLIKE -> tap dislike again
            Reaction.LIKED,
            Reaction.DISLIKED,  // LIKE -> DISLIKE
            Reaction.LIKED,     // DISLIKE -> LIKE
        )
        for (state in path) {
            render(like, dislike, state)
            assertState("-> $state", like, dislike, colors, state)
        }
    }

    @Test
    fun aRepeatedRenderOfTheSameStateDoesNotResetTheTint() = onPage { like, dislike, colors ->
        render(like, dislike, Reaction.LIKED)
        // What a metadata poll, a scope re-emission or a sync pull re-delivers.
        repeat(3) { render(like, dislike, Reaction.LIKED) }
        assertState("after re-renders", like, dislike, colors, Reaction.LIKED)
    }

    @Test
    fun aFreshlyInflatedPageShowsTheStoredStateAtOnce() = onPage { attached, _, colors ->
        // A rebind: return to the screen, a recreated page. Not attached yet, so no fade -
        // the first frame is already right.
        lateinit var like: ImageView
        lateinit var dislike: ImageView
        instrumentation.runOnMainSync {
            // The activity's own themed context, the one a fragment inflates with.
            val page = android.view.LayoutInflater.from(attached.context)
                .inflate(R.layout.fragment_myata_stream, null)
            like = page.findViewById(R.id.btn_favorite)
            dislike = page.findViewById(R.id.btn_dislike)
            PlayerReactionControls.render(like, dislike, Reaction.DISLIKED)
        }
        val tint = ImageViewCompat.getImageTintList(dislike)?.defaultColor
        assertEquals(colors.dislikeOn, tint)
        assertTrue(dislike.isSelected)
    }

    /* ---------------------------------------------------------------- infra -- */

    private data class Colors(val likeOn: Int, val dislikeOn: Int, val likeRest: Int, val dislikeRest: Int)

    private fun render(like: ImageView, dislike: ImageView, state: Reaction) {
        instrumentation.runOnMainSync { PlayerReactionControls.render(like, dislike, state) }
        settle()
    }

    /** Finger down long enough for the press animator to finish, then up. */
    private fun press(view: ImageView) {
        instrumentation.runOnMainSync { view.isPressed = true }
        settle()
        instrumentation.runOnMainSync { view.isPressed = false }
        settle()
    }

    /** Longer than the press and the tint fade together, at any animator scale up to 1x. */
    private fun settle() {
        Thread.sleep(400)
        instrumentation.waitForIdleSync()
    }

    private fun assertState(where: String, like: ImageView, dislike: ImageView, colors: Colors, state: Reaction) {
        var likeTint = 0
        var dislikeTint = 0
        instrumentation.runOnMainSync {
            likeTint = ImageViewCompat.getImageTintList(like)!!.defaultColor
            dislikeTint = ImageViewCompat.getImageTintList(dislike)!!.defaultColor
        }
        assertEquals("$where: like tint", if (state == Reaction.LIKED) colors.likeOn else colors.likeRest, likeTint)
        assertEquals(
            "$where: dislike tint",
            if (state == Reaction.DISLIKED) colors.dislikeOn else colors.dislikeRest,
            dislikeTint,
        )
        assertEquals("$where: like selected", state == Reaction.LIKED, like.isSelected)
        assertEquals("$where: dislike selected", state == Reaction.DISLIKED, dislike.isSelected)
    }

    /** A real PLAYER page, attached on top of MainActivity's content. */
    private fun onPage(block: (ImageView, ImageView, Colors) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var like: ImageView
            lateinit var dislike: ImageView
            lateinit var colors: Colors
            scenario.onActivity { activity ->
                val page = activity.layoutInflater.inflate(R.layout.fragment_myata_stream, null)
                page.elevation = 1000f
                activity.addContentView(
                    page,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                like = page.findViewById(R.id.btn_favorite)
                dislike = page.findViewById(R.id.btn_dislike)
                colors = Colors(
                    likeOn = ContextCompat.getColor(activity, R.color.player_like_active),
                    dislikeOn = ContextCompat.getColor(activity, R.color.player_dislike_active),
                    likeRest = ContextCompat.getColor(activity, R.color.player_like),
                    dislikeRest = ContextCompat.getColor(activity, R.color.player_control_action),
                )
            }
            instrumentation.waitForIdleSync()
            block(like, dislike, colors)
        }
    }
}
