package com.example.musicplayerapp.ui

/**
 * When the Mini Player is on screen.
 *
 * Three independent conditions, and the interesting one is the session. The
 * frozen design draws the pill on HOME, COLLECTION and ABOUT US, but every frame
 * it is drawn on already has a track in it - the design has nothing to say about
 * an app that has never played anything. The owner's contract fills that gap: the
 * pill is a handle on a stream the user chose, so before there is one there is
 * nothing to put a handle on.
 *
 * **The session is not `isPlaying`.** A paused stream is still the user's chosen
 * stream and the pill stays up showing it; that is the whole reason the two are
 * separate inputs here. `hasPlaybackSession` comes from the service's own
 * timeline (see `StreamsViewModel`), so a cold start with nothing loaded is
 * distinguishable from a loaded stream sitting paused, and neither is inferred
 * from a persisted flag.
 *
 * Kept free of Android types so the whole contract is a unit test.
 */
object MiniPlayerVisibility {

    /**
     * The screen keys the frozen design gives a mini player.
     *
     * `HOME`, `COLLECTION` (filled and empty alike) and `ABOUT` draw the pill;
     * `PLAYER` is absent because it already shows all of this full size, and
     * `PUSHED` - every Profile, Settings, auth, Report and later destination - is
     * absent because no frozen pushed frame draws it either.
     *
     * This is an **allowlist**, and that is the whole safety property: the key
     * now arrives from [NavScreen] via the shell's destination listener rather
     * than from the screens themselves, so a destination nobody thought about
     * lands on `PUSHED` and is hidden without anyone having to remember. See
     * [NavScreen] for why the writing moved.
     */
    val SCREENS = setOf(NavScreen.HOME, NavScreen.COLLECTION, NavScreen.ABOUT)

    fun shouldShow(
        screen: String?,
        inSplitMode: Boolean,
        hasPlaybackSession: Boolean,
    ): Boolean = hasPlaybackSession && screen in SCREENS && !inSplitMode
}
