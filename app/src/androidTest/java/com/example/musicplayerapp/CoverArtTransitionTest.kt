package com.example.musicplayerapp

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.graphics.drawable.VectorDrawable
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.NowPlayingArtwork
import com.example.musicplayerapp.ui.CoverArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The current track is shown as a pair - its title and artist with its own cover
 * or with the placeholder - and never as the new track's text over the previous
 * track's cover (owner's rule, UI polish pass).
 *
 * [CoverArt.NowPlaying] binds the text through the `apply` it is given, so the
 * pair is observable directly: [Surface.text] is what `apply` last wrote, and the
 * drawable is what the view shows (the top layer while a crossfade runs).
 *
 * Covers are local resource URIs, which Picasso decodes off the main thread
 * without a network - "on disk" in the rule's terms. An `example.invalid` URL
 * is "not local": the offline-only first attempt misses.
 *
 * Picasso must be called on the main thread, so every call happens there; the
 * assertions are made back on the test thread.
 */
@RunWith(AndroidJUnit4::class)
class CoverArtTransitionTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val pkg get() = instrumentation.targetContext.packageName
    private val coverA get() = "android.resource://$pkg/${R.drawable.zaglushka_1_img}"
    private val coverB get() = "android.resource://$pkg/${R.drawable.zaglushka_3_img}"
    private val remote = "https://example.invalid/not-cached.jpg"

    private inner class Surface {
        lateinit var view: ImageView
        lateinit var cover: CoverArt.NowPlaying
        @Volatile var text: String? = null

        init {
            main {
                view = ImageView(instrumentation.targetContext)
                view.layout(0, 0, 120, 120) // fit() needs a size
                view.setImageResource(R.drawable.artwork_placeholder)
                cover = CoverArt.NowPlaying(view)
            }
        }

        fun present(img: String?, title: String) = main { cover.present(img) { text = title } }

        fun shown(): Drawable? {
            var d: Drawable? = null
            main { d = view.drawable }
            return (d as? TransitionDrawable)?.let { it.getDrawable(it.numberOfLayers - 1) } ?: d
        }

        fun showsCover() = shown() is BitmapDrawable
        fun showsPlaceholder() = shown() is VectorDrawable
    }

    /** A surface already showing track A with its cover. */
    private fun onTrackA(): Surface = Surface().apply {
        present(coverA, "A")
        await("A with its cover") { text == "A" && showsCover() }
    }

    @Test
    fun aLocalCoverGoesUpTogetherWithItsTrack() {
        val s = onTrackA()
        val coverOfA = s.shown()

        s.present(coverB, "B")
        // Until B's cover has decoded, A is still up - text and cover, a true pair.
        assertEquals("B's text went up before its cover", "A", s.text)
        assertSame(coverOfA, s.shown())

        await("B with its cover") { s.text == "B" && s.showsCover() && s.shown() !== coverOfA }
    }

    @Test
    fun aCoverThatIsNotLocalPutsTheTextUpOverThePlaceholder() {
        val s = onTrackA()

        s.present(remote, "B")
        await("B's text") { s.text == "B" }
        assertTrue("B's text is over A's cover", s.showsPlaceholder())
    }

    @Test
    fun aPendingLookupShowsTheNewTrackOverThePlaceholder() {
        val s = onTrackA()

        s.present(null, "B")
        assertEquals("B", s.text)
        assertTrue("B's text is over A's cover", s.showsPlaceholder())
    }

    @Test
    fun noCoverForTheTrackShowsThePlaceholder() {
        val s = onTrackA()

        s.present(NowPlayingArtwork.NO_IMAGE, "B")
        assertEquals("B", s.text)
        assertTrue(s.showsPlaceholder())
    }

    /** A metadata tick, or a new track on the same release: nothing reloads. */
    @Test
    fun theSameCoverSwitchesTheTextAtOnceAndLeavesTheCover() {
        val s = onTrackA()
        val coverOfA = s.shown()

        s.present(coverA, "A2")
        assertEquals("A2", s.text)
        assertSame("the cover already up was replaced", coverOfA, s.shown())
    }

    /** A superseded track never goes up: only the latest one's text is applied. */
    @Test
    fun aTrackSupersededWhileItsCoverLoadsNeverGoesUp() {
        val s = onTrackA()

        s.present(coverB, "B")
        s.present(null, "C")
        assertEquals("C", s.text)
        Thread.sleep(500)
        assertEquals("B arrived after C", "C", s.text)
        assertTrue(s.showsPlaceholder())
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(what: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }
}
