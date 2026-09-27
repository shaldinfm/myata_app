package com.example.musicplayerapp

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.NowPlayingArtwork
import com.example.musicplayerapp.ui.CoverArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What is on screen the instant the track changes.
 *
 * The owner's rule since the UI polish pass: previous cover -> the next cover has
 * decoded -> crossfade -> next cover. The placeholder is never an in-between frame;
 * it stands only where there genuinely is no cover - nothing shown yet, NO_IMAGE,
 * or a failed load. (This inverts G5a, which dropped the cover the instant the URL
 * changed and produced the old -> plate -> new flash.)
 *
 * The previous cover is a [ColorDrawable] so it is unmistakable, and the
 * placeholder is the vector `artwork_placeholder`, so "is the plate up" is "is a
 * VectorDrawable drawn". The cover URLs are unreachable on purpose: what is tested
 * is the frame immediately after the call, before any load can finish.
 *
 * Picasso must be called on the main thread, so the render happens there; the
 * assertions are made back on the test thread, because an assertion that fails
 * inside `runOnMainSync` takes the process down instead of failing the test.
 */
@RunWith(AndroidJUnit4::class)
class CoverArtTransitionTest {

    private val coverA = "https://example.invalid/a.jpg"
    private val coverB = "https://example.invalid/b.jpg"
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** What one [CoverArt.render] call left behind. */
    private class Frame(
        val previous: Drawable,
        val drawn: Drawable?,
        val loaded: String?,
    )

    private fun renderOver(img: String?, loaded: String?): Frame {
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

    /** The new cover is loading: the previous one stays until it has decoded. */
    @Test
    fun theNextCoverLoadsUnderThePreviousOneWithNoPlaceholderFrame() {
        val frame = renderOver(img = coverB, loaded = coverA)

        assertEquals("the view is now bound to B's cover", coverB, frame.loaded)
        assertSame("the placeholder came up between two covers", frame.previous, frame.drawn)
    }

    /** The common case: a new track whose lookup has not answered yet. */
    @Test
    fun aPendingLookupKeepsThePreviousCover() {
        val frame = renderOver(img = null, loaded = coverA)

        assertEquals("the previous cover is still the one on screen", coverA, frame.loaded)
        assertSame("the placeholder came up while the lookup ran", frame.previous, frame.drawn)
    }

    /** The resolver looked and found nothing: this track really has no cover. */
    @Test
    fun noCoverForTheTrackShowsThePlaceholder() {
        val frame = renderOver(img = NowPlayingArtwork.NO_IMAGE, loaded = coverA)

        assertNull(frame.loaded)
        assertTrue("the placeholder is not up", frame.drawn is VectorDrawable)
    }

    /** Nothing has been shown yet: the first frame of a session is the placeholder. */
    @Test
    fun theFirstLoadShowsThePlaceholder() {
        val frame = renderOver(img = null, loaded = null)

        assertNull(frame.loaded)
        assertTrue("the placeholder is not up", frame.drawn is VectorDrawable)
    }

    /**
     * A metadata tick that repeats the current track must leave its cover alone -
     * taking it down and loading it again is what would make the artwork blink.
     */
    @Test
    fun repeatingTheSameCoverLeavesTheViewUntouched() {
        val frame = renderOver(img = coverA, loaded = coverA)

        assertEquals(coverA, frame.loaded)
        assertSame("the cover already up was replaced", frame.previous, frame.drawn)
    }

    /** A cover that cannot load gives way to the placeholder and is forgotten. */
    @Test
    fun aFailedLoadReturnsToThePlaceholder() {
        var failed = false
        lateinit var view: ImageView
        instrumentation.runOnMainSync {
            view = ImageView(instrumentation.targetContext)
            // Laid out, so the fit() load has a size and starts at once.
            view.layout(0, 0, 100, 100)
            view.setImageDrawable(ColorDrawable(Color.MAGENTA))
            CoverArt.render(view, "file:///data/local/tmp/myata-no-such-cover.png", coverA) {
                failed = true
            }
        }

        val deadline = System.currentTimeMillis() + 10_000
        var plate = false
        while (System.currentTimeMillis() < deadline && !plate) {
            Thread.sleep(25)
            instrumentation.runOnMainSync { plate = failed && view.drawable is VectorDrawable }
        }
        assertTrue("the failed load did not bring the placeholder back", plate)
    }
}
