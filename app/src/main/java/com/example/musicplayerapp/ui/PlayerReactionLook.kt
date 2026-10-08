package com.example.musicplayerapp.ui

import com.example.musicplayerapp.data.Reaction

/**
 * Which of the PLAYER's two reaction controls reads active, as a value.
 *
 * Derived from the track's stored [Reaction] and from nothing else - not from a tap,
 * not from a press, not from a server answer. One [Reaction] decides both controls, so
 * they cannot both be active. Pure, so the whole table is a JVM test.
 */
data class PlayerReactionLook(val likeActive: Boolean, val dislikeActive: Boolean) {

    companion object {
        fun of(reaction: Reaction): PlayerReactionLook = PlayerReactionLook(
            likeActive = reaction == Reaction.LIKED,
            dislikeActive = reaction == Reaction.DISLIKED,
        )
    }
}
