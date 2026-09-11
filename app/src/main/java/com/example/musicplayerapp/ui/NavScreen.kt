package com.example.musicplayerapp.ui

/**
 * The screen identity the shell publishes for whatever destination is on screen.
 *
 * ## Why this exists
 *
 * `MiniPlayerVisibility` answers "is the pill on this screen" from a key, and the
 * key used to be written by the screens themselves - each of the four bottom-bar
 * fragments assigning its own name in `onCreateView` and again in `onResume`.
 * That works exactly as long as every screen remembers, and the pushed screens
 * never did: Profile, Settings, the auth screens and Report wrote nothing, so the
 * key kept naming the screen you had **left** and the pill stayed up over all of
 * them. The rule was right the whole time; it was being asked about the wrong
 * screen.
 *
 * So the key is no longer something a screen announces about itself. It is
 * derived, in one place, from the destination the NavController actually arrived
 * at - see `MainActivity.screenKeyOf`. Adding a destination cannot forget to
 * write it, because the destination never writes it.
 *
 * ## The default is what makes it safe
 *
 * Every id that is not one of the four bottom-bar destinations maps to [PUSHED],
 * and [PUSHED] is not in `MiniPlayerVisibility.SCREENS`. A new pushed
 * destination - profile-avatar, the full-screen history, anything G4b or later
 * adds - is therefore hidden by default, with no edit here and none there. The
 * only way to make the pill appear on a screen is to add that screen to the
 * allowlist deliberately.
 *
 * That is the opposite of the arrangement this replaces, where a new screen was
 * *shown* by default and had to remember to opt out.
 *
 * Kept free of Android types so the vocabulary is shared by the unit tests that
 * pin the rule.
 */
object NavScreen {

    /** HOME. Frozen `HOME` 2393:1628 - carries the pill. */
    const val HOME = "main"

    /** PLAYER. Shows the same content full size, so it carries no pill. */
    const val PLAYER = "player"

    /** COLLECTION, filled or empty. Both frozen frames carry the pill. */
    const val COLLECTION = "favorites"

    /** ABOUT US. Frozen `ABOUT US` 2411:31594 - carries the pill. */
    const val ABOUT = "info"

    /**
     * Any pushed destination: Profile, Settings and its subpages, the auth
     * screens, Report - and whatever is pushed next.
     *
     * One key for all of them rather than one per screen, because nothing
     * downstream needs to tell them apart: no frozen pushed frame draws the pill
     * or the bottom bar, so the only question ever asked of this value is whether
     * it is one of the four above.
     */
    const val PUSHED = "pushed"
}
