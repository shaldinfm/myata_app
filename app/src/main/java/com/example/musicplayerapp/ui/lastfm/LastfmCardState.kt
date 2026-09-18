package com.example.musicplayerapp.ui.lastfm

/**
 * What the Last.fm screen's single card is showing.
 *
 * Four states, one card. The frozen frames draw two of them - `Not connected`
 * 2523:134 and `Connected` 2523:132 - stacked on one review frame so both could be
 * seen at once; the shipped screen shows exactly one at a time. The other two are
 * owner decisions recorded in this slice, and they reuse the drawn geometry rather
 * than inventing any: same 358x172 card, same heading / two body lines / button.
 *
 * Kept free of Android types so the mapping from state to rendered content is a
 * JVM test rather than a screenshot. [LastfmCardContent] is what the fragment
 * actually draws, and it is derived here and nowhere else - so a state can never
 * be drawn with another state's button.
 *
 * ## P3a has no way to reach three of these
 *
 * There is no session store and no network in this slice, so the only state a
 * shipped build can produce is [Disconnected]. The other three exist because the
 * screen is built to render them and the tests pin that it does; the code that
 * *produces* them arrives with the auth flow in P3b. That is deliberate: it keeps
 * the visual work reviewable against the frozen frames on its own, without a state
 * machine or a network in the same diff.
 */
sealed class LastfmCardState {

    /** Nothing linked. The frozen `Not connected` card. */
    data object Disconnected : LastfmCardState()

    /**
     * A request token exists and the listener has not finished authorising it.
     *
     * Owner decision: the frozen file draws no such state, and without one a
     * listener who returns from the browser without granting sees the plain
     * disconnected card and no hint that anything is half-done. The wording says
     * what is true and what to do, and the button reopens Last.fm.
     *
     * The P3b behaviour this anticipates: error 14 keeps the existing token, and
     * reopening uses that same token. A new one is requested only after it expires
     * or after a restart-required error (4 / 15).
     */
    data object AuthorizationPending : LastfmCardState()

    /**
     * Linked. The frozen `Connected` card.
     *
     * @property username the Last.fm account name, shown as-is.
     * @property playcount the account's **lifetime** Last.fm total from
     *   `user.getInfo`, or null while it is being fetched or if it could not be
     *   read. Null renders the username alone rather than an error or a zero -
     *   a count is an enrichment, and a wrong one is worse than none.
     */
    data class Connected(val username: String, val playcount: Long?) : LastfmCardState()

    /**
     * The stored session is no longer accepted and the listener has to authorise
     * again.
     *
     * Owner decision, and the wording is deliberately neutral: it states that the
     * session is no longer valid, **not** that Last.fm revoked access. A session
     * can end for several reasons and the app cannot tell which, so claiming a
     * cause would sometimes be a lie told to the person it is about.
     *
     * @property username who was linked, kept so the card can stay specific.
     */
    data class ReauthRequired(val username: String) : LastfmCardState()
}

/**
 * The card's four slots, resolved for one state.
 *
 * String resources are referenced by id rather than resolved here, so this stays a
 * pure function and the tests do not need a Context. [bodySecond] is nullable
 * because the two invented states say what they need in one line, and the card
 * hides the second rather than padding it with filler.
 *
 * @property logoTintPrimary whether the mark is drawn in `primary` rather than
 *   `text_secondary`. True only when connected - in the frozen file that tint is
 *   the one element that changes with connection state, which is what makes it the
 *   state indicator rather than decoration.
 * @property showCheck whether the 24dp check sits at (318, 18). Connected only.
 * @property buttonPrimary whether the button is the filled primary or the outlined
 *   secondary. Disconnect is the only outlined one.
 */
data class LastfmCardContent(
    val headingRes: Int,
    val bodyFirstRes: Int,
    val bodySecondRes: Int?,
    val buttonLabelRes: Int,
    val logoTintPrimary: Boolean,
    val showCheck: Boolean,
    val buttonPrimary: Boolean,
)
