package com.example.musicplayerapp.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Mini Player visibility contract, one case per rule.
 *
 * The rule that carries the whole thing is the last pair: `isPlaying` is not an
 * input here at all, so a paused stream is indistinguishable from a playing one
 * and only the *session* decides. That is what stops a pause from hiding the pill
 * and what keeps a cold start from showing one.
 */
class MiniPlayerVisibilityTest {

    private fun show(
        screen: String?,
        session: Boolean = true,
        split: Boolean = false,
    ) = MiniPlayerVisibility.shouldShow(screen, split, session)

    /* 1. clean launch: nothing has been started, so there is nothing to show. */

    @Test
    fun `a cold start with no session is hidden on every screen`() {
        for (screen in listOf("main", "favorites", "info", "player", "donate", null)) {
            assertFalse("screen=$screen", show(screen, session = false))
        }
    }

    /* 2-4. a session, playing or paused, is the same session. */

    @Test
    fun `a session shows the pill on the screens the design draws it on`() {
        assertTrue(show("main"))
        assertTrue(show("favorites"))
        assertTrue(show("info"))
    }

    @Test
    fun `visibility does not depend on whether the stream is playing`() {
        // isPlaying is not a parameter. Pausing cannot change this answer, which
        // is the point: a paused stream is still the user's chosen stream.
        assertTrue(show("main", session = true))
    }

    /* 5. navigation between the three screens never drops it. */

    @Test
    fun `moving between HOME, COLLECTION and ABOUT US keeps it up`() {
        for (screen in listOf("main", "favorites", "info", "main")) {
            assertTrue("screen=$screen", show(screen))
        }
    }

    /* 6-7. PLAYER hides it; leaving PLAYER brings it back. */

    @Test
    fun `PLAYER never shows the pill, session or not`() {
        assertFalse(show("player", session = true))
        assertFalse(show("player", session = false))
    }

    @Test
    fun `returning from PLAYER shows it again`() {
        assertFalse(show("player"))
        assertTrue(show("main"))
    }

    /* 8. a recreated UI is just a session it did not start itself. */

    @Test
    fun `a session the UI did not start is treated no differently`() {
        // Nothing in the rule knows who started the stream, so a controller that
        // reconnects to a live service produces the same answer as a first play.
        assertTrue(show("main", session = true))
    }

    /* The rest of the surface. */

    @Test
    fun `donate and splash are not screens the design gives a mini player`() {
        assertFalse(show("donate"))
        assertFalse(show(null))
        assertFalse(show("something_else"))
    }

    @Test
    fun `split screen hides it, because the bar it floats on is hidden too`() {
        assertFalse(show("main", split = true))
        assertFalse(show("favorites", split = true))
    }
}
