package com.example.musicplayerapp.ui

import com.example.musicplayerapp.data.Reaction
import com.example.musicplayerapp.data.ReactionToggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * What the PLAYER's two controls show, walked through every tap a listener can make.
 *
 * The look is a function of the stored reaction alone, so these taps go through the same
 * [ReactionToggle] the ViewModel writes with and then ask [PlayerReactionLook] what the
 * stored result looks like - which is exactly what the screen shows once Room reports it.
 */
class PlayerReactionLookTest {

    private val none = PlayerReactionLook(likeActive = false, dislikeActive = false)
    private val like = PlayerReactionLook(likeActive = true, dislikeActive = false)
    private val dislike = PlayerReactionLook(likeActive = false, dislikeActive = true)

    @Test
    fun `each stored reaction has exactly its own look`() {
        assertEquals(none, PlayerReactionLook.of(Reaction.NEUTRAL))
        assertEquals(like, PlayerReactionLook.of(Reaction.LIKED))
        assertEquals(dislike, PlayerReactionLook.of(Reaction.DISLIKED))
    }

    @Test
    fun `like and dislike are never active together`() {
        for (reaction in Reaction.entries) {
            val look = PlayerReactionLook.of(reaction)
            assertFalse("$reaction reads both active", look.likeActive && look.dislikeActive)
        }
    }

    @Test
    fun `NONE, tap like - like stays active`() =
        assertEquals(like, afterTaps(Reaction.NEUTRAL, ReactionToggle::likeTap))

    @Test
    fun `LIKE, tap like again - back to none`() =
        assertEquals(none, afterTaps(Reaction.LIKED, ReactionToggle::likeTap))

    @Test
    fun `NONE, tap dislike - dislike stays active`() =
        assertEquals(dislike, afterTaps(Reaction.NEUTRAL, ReactionToggle::dislikeTap))

    @Test
    fun `DISLIKE, tap dislike again - back to none`() =
        assertEquals(none, afterTaps(Reaction.DISLIKED, ReactionToggle::dislikeTap))

    @Test
    fun `LIKE, tap dislike - only dislike is active`() =
        assertEquals(dislike, afterTaps(Reaction.LIKED, ReactionToggle::dislikeTap))

    @Test
    fun `DISLIKE, tap like - only like is active`() =
        assertEquals(like, afterTaps(Reaction.DISLIKED, ReactionToggle::likeTap))

    @Test
    fun `a longer run of taps always ends on the look of the last stored reaction`() {
        assertEquals(
            like,
            afterTaps(
                Reaction.NEUTRAL,
                ReactionToggle::likeTap,     // LIKED
                ReactionToggle::dislikeTap,  // DISLIKED
                ReactionToggle::dislikeTap,  // NEUTRAL
                ReactionToggle::likeTap,     // LIKED
            ),
        )
    }

    private fun afterTaps(start: Reaction, vararg taps: (Reaction) -> Reaction): PlayerReactionLook =
        PlayerReactionLook.of(taps.fold(start) { stored, tap -> tap(stored) })
}
