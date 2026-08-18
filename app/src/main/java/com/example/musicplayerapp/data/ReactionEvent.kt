package com.example.musicplayerapp.data

/**
 * What a listener did to a track, as reported to analytics.
 *
 * Four events, not two. The vocabulary used to be a free-form `String` with two
 * documented values, "LIKE or DISLIKE", and no way to say "the reaction went back
 * to neutral" - so every removal site reused `DISLIKE`, and taking a track out of
 * the Collection was recorded as the listener disliking it. Two more spellings had
 * grown from the same gap, a bare `DISLIKE` and a `💔 DISLIKE`, which the sheet
 * counted as different actions.
 *
 * The reaction model these names come from has three states per listener and track
 * - NEUTRAL, LIKED, DISLIKED - and these are the transitions between them:
 *
 * ```
 *   NEUTRAL  --LIKE-->     LIKED      LIKED    --UNLIKE-->    NEUTRAL
 *   NEUTRAL  --DISLIKE-->  DISLIKED   DISLIKED --UNDISLIKE--> NEUTRAL
 * ```
 *
 * [UNLIKE] is a return to neutral and **never** means [DISLIKE]. That distinction
 * is the whole point of this type, and making the parameter typed is what stops the
 * two being confused again by whoever writes the next call site.
 *
 * [DISLIKE] and [UNDISLIKE] are declared because they are the other half of the
 * model and give the type its meaning, but nothing produces them yet: the PLAYER's
 * `dislike` slot is still the frozen History control, so the sheet will not see
 * either name until the Dislike control is actually built.
 *
 * @property wire the exact string sent to the sheet. The Apps Script writes
 *   `params.action` through without a whitelist, so these names are the schema and
 *   changing one silently splits a column's history in two.
 */
enum class ReactionEvent(val wire: String) {

    /** Explicit positive reaction. The track is saved to the Collection. */
    LIKE("LIKE"),

    /** The Like is withdrawn: out of the Collection, back to neutral. Not a dislike. */
    UNLIKE("UNLIKE"),

    /** Explicit negative reaction. Not produced yet - see the class KDoc. */
    DISLIKE("DISLIKE"),

    /** The Dislike is withdrawn, back to neutral. Not produced yet. */
    UNDISLIKE("UNDISLIKE");

    companion object {

        /**
         * The event a save earned, or null if it earned none.
         *
         * [ReactionDao.like] returns false when the track was already LIKED: nothing
         * changed, the listener expressed nothing new, and reporting a second [LIKE]
         * for it would count one opinion twice.
         */
        fun forLike(changed: Boolean): ReactionEvent? = if (changed) LIKE else null

        /**
         * The event a withdrawal earned, or null if it earned none.
         *
         * Un-liking something that is not liked changes nothing, so it reports
         * nothing. A Like that really went is an [UNLIKE] - a return to neutral,
         * never a [DISLIKE].
         */
        fun forUnlike(changed: Boolean): ReactionEvent? = if (changed) UNLIKE else null
    }
}
