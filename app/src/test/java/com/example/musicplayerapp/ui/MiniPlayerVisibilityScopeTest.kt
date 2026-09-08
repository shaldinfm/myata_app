package com.example.musicplayerapp.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which destinations the Mini Player is allowed on.
 *
 * The rule itself was never wrong. What was wrong is what it was asked about: the
 * key used to be written by the four bottom-bar fragments about themselves, and
 * the pushed screens wrote nothing, so on Profile or Settings the rule was still
 * answering for HOME and answering "yes". [NavScreen] is where that was fixed -
 * the shell derives the key from the destination now - and these hold the two
 * halves that together make the pill correct:
 *
 *  - the allowlist is exactly the three frozen surfaces, and
 *  - **anything not on it is hidden**, which is what makes a destination nobody
 *    has added to this file safe by default.
 *
 * The second half is the one that matters for G4b and later. `profile-avatar` and
 * the full-screen history do not exist yet; when they land they will map to
 * [NavScreen.PUSHED] with no edit here, and `every_pushed_destination_is_hidden`
 * already says what must happen to them.
 */
class MiniPlayerVisibilityScopeTest {

    private fun shown(screen: String?) =
        MiniPlayerVisibility.shouldShow(screen, inSplitMode = false, hasPlaybackSession = true)

    @Test
    fun the_three_frozen_surfaces_show_the_pill() {
        // HOME 2393:1628, COLLECTION 2399:31129 and its empty twin 2429:185, and
        // ABOUT US 2411:31594 are the only frames in the file that draw it.
        assertTrue("HOME must show the pill", shown(NavScreen.HOME))
        assertTrue("COLLECTION must show the pill", shown(NavScreen.COLLECTION))
        assertTrue("ABOUT US must show the pill", shown(NavScreen.ABOUT))
    }

    @Test
    fun the_player_does_not_show_the_pill() {
        // It is the same content full size; the frozen PLAYER frame has no pill.
        assertFalse(shown(NavScreen.PLAYER))
    }

    @Test
    fun every_pushed_destination_is_hidden() {
        // Profile, Settings and its subpages, the auth screens, Report - and the
        // ones G4b will add. They share one key precisely so that this holds for
        // screens that do not exist yet.
        assertFalse("a pushed destination must hide the pill", shown(NavScreen.PUSHED))
    }

    @Test
    fun an_unknown_key_is_hidden_rather_than_shown() {
        // The allowlist's whole safety property: the default is off. If this ever
        // becomes an denylist, this is the test that fails.
        assertFalse(shown("some-destination-added-later"))
        assertFalse(shown(""))
        assertFalse(shown(null))
    }

    @Test
    fun a_frozen_surface_still_needs_a_session_and_a_full_screen() {
        // Unchanged by G4a, and asserted here so the scope work cannot quietly
        // drop either condition: the pill is a handle on a stream the listener
        // chose, and split mode gives it nowhere to sit.
        assertFalse(
            "no session, no pill",
            MiniPlayerVisibility.shouldShow(NavScreen.HOME, inSplitMode = false, hasPlaybackSession = false),
        )
        assertFalse(
            "split mode has no room for it",
            MiniPlayerVisibility.shouldShow(NavScreen.HOME, inSplitMode = true, hasPlaybackSession = true),
        )
    }
}
