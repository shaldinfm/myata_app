package com.example.musicplayerapp

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.NowPlayingArtwork
import com.example.musicplayerapp.ui.CoverArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What is actually on screen the instant the track changes (G5a).
 *
 * [com.example.musicplayerapp.data.NowPlayingArtworkTest] pins which cover a
 * track is entitled to; this pins the frame. The bug was never in the URL the
 * ViewModel held - it was that the view kept displaying the previous track's
 * bitmap while the next one loaded, because `noPlaceholder` leaves whatever is
 * there until a load completes. So what is inspected here is the state
 * *immediately* after the call, before any load can have finished: at that moment
 * the previous cover must already be gone.
 *
 * The previous cover is a [ColorDrawable] so it is unmistakable. No network is
 * needed and none is waited for - the URLs are unreachable on purpose, and what
 * they eventually do is not what is being tested.
 *
 * Picasso must be called on the main thread, so the render happens there; the
 * assertions are made back on the test thread, because an assertion that fails
 * inside `runOnMainSync` takes the process down instead of failing the test.
 */
@RunWith(AndroidJUnit4::class)
class CoverArtTransitionTest {

    private val coverA = "https://example.invalid/a.jpg"
    private val coverB = "https://example.invalid/b.jpg"

    /** What one [CoverArt.render] call left behind. */
    private class Frame(
        val previous: Drawable,
        val drawn: Drawable?,
        val loaded: String?,
    )

    /**
     * Puts a stand-in for A's cover in a fresh view, renders [img] over it, and
     * reports what the view holds the moment the call returns.
     */
    private fun renderOverACover(img: String?, loaded: String?): Frame {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var frame: Frame
        instrumentation.runOnMainSync {
            val view = ImageView(instrumentation.targetContext)
            val previous = ColorDrawable(Color.MAGENTA)
            view.setImageDrawable(previous)

            val result = CoverArt.render(view, img, loaded)
            frame = Frame(previous = previous, drawn = view.drawable, loaded = result)
        }
        return frame
    }

    /**
     * The track changed and the new cover has not loaded yet. This is the frame
     * the owner asked about: B's metadata is on screen, so A's artwork must not be.
     */
    @Test
    fun theNewTracksFrameNeverShowsTheOldTracksCover() {
        val frame = renderOverACover(img = coverB, loaded = coverA)

        assertEquals("the view is now bound to B's cover", coverB, frame.loaded)
        assertNotNull("something is drawn - the plate", frame.drawn)
        assertTrue(
            "A's cover was still on screen under B's metadata",
            frame.drawn !== frame.previous && frame.drawn !is ColorDrawable,
        )
    }

    /**
     * The common case: the track changed and no lookup has answered yet, so the
     * ViewModel publishes no cover at all.
     */
    @Test
    fun aTrackWithNoCoverYetDropsThePreviousOne() {
        val frame = renderOverACover(img = null, loaded = coverA)

        assertNull("nothing is bound", frame.loaded)
        assertNotNull(frame.drawn)
        assertTrue("the previous cover was left up", frame.drawn !== frame.previous)
    }

    /** A lookup that finished and found nothing reads the same way as "not yet". */
    @Test
    fun theNoCoverMarkerAlsoDropsThePreviousOne() {
        val frame = renderOverACover(img = NowPlayingArtwork.NO_IMAGE, loaded = coverA)

        assertNull(frame.loaded)
        assertTrue("the previous cover was left up", frame.drawn !== frame.previous)
    }

    /**
     * The other half of the rule. A metadata tick that repeats the current track
     * must leave its cover alone - taking it down and loading it again is what
     * would make the artwork blink every few seconds.
     */
    @Test
    fun repeatingTheSameCoverLeavesTheViewUntouched() {
        val frame = renderOverACover(img = coverA, loaded = coverA)

        assertEquals(coverA, frame.loaded)
        assertSame("the cover already up was replaced", frame.previous, frame.drawn)
    }
}
