package com.example.musicplayerapp.data

/**
 * What a tap on the Like or the Dislike control means.
 *
 * The whole reaction model in one place, and pure, because this is the part that
 * is easy to get subtly wrong and impossible to see in a screenshot: which state a
 * tap leads to, and which event that transition is. `ReactionToggleTest` walks
 * every cell of it.
 *
 * Two controls, three states, and each control toggles its own state against
 * NEUTRAL:
 *
 * ```
 *                    tap like            tap dislike
 *   NEUTRAL   ->     LIKED               DISLIKED
 *   LIKED     ->     NEUTRAL             DISLIKED
 *   DISLIKED  ->     LIKED               NEUTRAL
 * ```
 *
 * So a listener can always undo their own opinion with the control that expressed
 * it, and can always change their mind with the other one - without ever passing
 * through a state they did not ask for. That last part is the reason [eventFor]
 * exists: LIKED -> DISLIKED is **one** act and reports one [ReactionEvent.DISLIKE].
 * It is not an UNLIKE followed by a DISLIKE, because the listener did not withdraw
 * anything, they disagreed; manufacturing the intermediate event would put an act
 * in the analytics that nobody performed.
 */
object ReactionToggle {

    /** Where a tap on Like leads from [current]. */
    fun likeTap(current: Reaction): Reaction =
        if (current == Reaction.LIKED) Reaction.NEUTRAL else Reaction.LIKED

    /** Where a tap on Dislike leads from [current]. */
    fun dislikeTap(current: Reaction): Reaction =
        if (current == Reaction.DISLIKED) Reaction.NEUTRAL else Reaction.DISLIKED

    /**
     * The event a transition is, or null if it is not a transition at all.
     *
     * Null for [from] == [to] is what keeps one listener's repeated tapping out of
     * the counts: the second tap on an already-active control expresses nothing new
     * and reports nothing. The same null covers a write that turned out to change
     * nothing because another screen got there first.
     */
    fun eventFor(from: Reaction, to: Reaction): ReactionEvent? = when {
        from == to -> null
        to == Reaction.LIKED -> ReactionEvent.LIKE
        to == Reaction.DISLIKED -> ReactionEvent.DISLIKE
        // to == NEUTRAL: which opinion is being withdrawn decides which it is.
        from == Reaction.LIKED -> ReactionEvent.UNLIKE
        else -> ReactionEvent.UNDISLIKE
    }
}
