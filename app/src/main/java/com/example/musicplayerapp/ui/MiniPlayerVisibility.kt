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
     * `currentFragmentLiveData` keys for the screens the frozen design gives a
     * mini player. PLAYER is absent because it already shows all of this full
     * size; "donate" is absent because it is reached from inside "О нас" and has
     * no canonical frame, so there is nothing to reproduce.
     */
    val SCREENS = setOf("main", "favorites", "info")

    fun shouldShow(
        screen: String?,
        inSplitMode: Boolean,
        hasPlaybackSession: Boolean,
    ): Boolean = hasPlaybackSession && screen in SCREENS && !inSplitMode
}
