package com.example.musicplayerapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering rules that decide whether an artwork answer still belongs on
 * screen (G5a).
 *
 * These are the recon's four failures written as tests. Each one is a plain
 * function call here because that is the point of [NowPlayingArtwork]: the
 * ViewModel used to decide these things by reading its own LiveData back, which
 * made every one of them depend on main-thread delivery timing and none of them
 * testable. Nothing in this file mentions LiveData, a looper or a coroutine, and
 * that is what says the decision no longer depends on any of them.
 */
class NowPlayingArtworkTest {

    private val myata = "myata"
    private val gold = "gold"

    private val trackA = NowPlayingArtwork.identityOf("ROYAL BLOOD", "FIGURE IT OUT")
    private val trackB = NowPlayingArtwork.identityOf("SOMBR", "POTENTIAL")
    private val trackC = NowPlayingArtwork.identityOf("THE CARDIGANS", "ERASE/REWIND")

    private val coverA = "https://example.test/a.jpg"
    private val coverB = "https://example.test/b.jpg"
    private val coverC = "https://example.test/c.jpg"

    /**
     * G test 1. A has a cover, the stream moves to B, B's lookup has not finished:
     * the stream owns no cover at all. A's must not be what B is drawn with.
     */
    @Test
    fun `a new track starts with no cover, never the previous track's`() {
        val artwork = NowPlayingArtwork()

        artwork.announce(myata, trackA)
        assertTrue(artwork.offer(myata, trackA, coverA))
        assertEquals(coverA, artwork.current(myata))

        val owned = artwork.announce(myata, trackB)

        assertNull("B starts with nothing to draw", owned)
        assertNull("A's cover is gone the moment B is announced", artwork.current(myata))
        assertEquals(trackB, artwork.identity(myata))
    }

    /**
     * G test 2. The lookup for B was answered from the repository's cache, so it
     * comes back before anything has been delivered anywhere. It must still apply.
     */
    @Test
    fun `an answer that arrives immediately is applied, not discarded`() {
        val artwork = NowPlayingArtwork()

        artwork.announce(myata, trackA)
        artwork.offer(myata, trackA, coverA)
        artwork.announce(myata, trackB)

        // Nothing has been "delivered": announce returned a moment ago and this is
        // the very next call. The identity is all that is consulted.
        assertTrue(artwork.offer(myata, trackB, coverB))
        assertEquals(coverB, artwork.current(myata))
    }

    /**
     * G test 3. A -> B -> C with B's lookup still running. B's answer arrives last
     * and must not paint over C.
     */
    @Test
    fun `an answer for a track that has already finished cannot paint`() {
        val artwork = NowPlayingArtwork()

        artwork.announce(myata, trackA)
        artwork.announce(myata, trackB)
        artwork.announce(myata, trackC)

        assertFalse("B's late answer is not C's", artwork.offer(myata, trackB, coverB))
        assertNull(artwork.current(myata))

        assertTrue(artwork.offer(myata, trackC, coverC))
        assertEquals(coverC, artwork.current(myata))
    }

    /**
     * G test 4. The session callback reports the current track with no artwork of
     * its own, after the resolver has already found one. Null is not an answer.
     */
    @Test
    fun `nothing offered never takes away a cover the same track already has`() {
        val artwork = NowPlayingArtwork()

        artwork.announce(myata, trackA)
        artwork.offer(myata, trackA, coverA)

        assertFalse(artwork.offer(myata, trackA, null))
        assertFalse(artwork.offer(myata, trackA, ""))
        assertFalse(artwork.offer(myata, trackA, "   "))

        assertEquals(coverA, artwork.current(myata))
    }

    /**
     * G test 5. The station plays a track again. It is announced fresh, so it is
     * looked up again and the answer applies - which is the case the old code got
     * wrong, because a repeat is exactly what the repository answers from cache.
     */
    @Test
    fun `a repeated track resolves again and gets its cover back`() {
        val artwork = NowPlayingArtwork()

        artwork.announce(myata, trackA)
        artwork.offer(myata, trackA, coverA)
        artwork.announce(myata, trackB)
        artwork.offer(myata, trackB, coverB)

        assertNull("A comes back around as a new announcement", artwork.announce(myata, trackA))
        assertTrue(artwork.offer(myata, trackA, coverA))
        assertEquals(coverA, artwork.current(myata))
    }

    /**
     * The poll and the session both report the same track, one after the other.
     * The second announcement must hand back the cover rather than drop it, or the
     * artwork would blink and be looked up twice on every tick.
     */
    @Test
    fun `re-announcing the same track keeps its cover`() {
        val artwork = NowPlayingArtwork()

        artwork.announce(myata, trackA)
        artwork.offer(myata, trackA, coverA)

        assertEquals(coverA, artwork.announce(myata, trackA))
        assertEquals(coverA, artwork.current(myata))
    }

    @Test
    fun `a real cover replaces the marker for one that was never found`() {
        val artwork = NowPlayingArtwork()
        artwork.announce(myata, trackA)

        assertTrue(artwork.offer(myata, trackA, NowPlayingArtwork.NO_IMAGE))
        assertTrue("the session found one after the resolver did not", artwork.offer(myata, trackA, coverA))
        assertEquals(coverA, artwork.current(myata))
    }

    @Test
    fun `the marker for no cover never replaces a real one`() {
        val artwork = NowPlayingArtwork()
        artwork.announce(myata, trackA)
        artwork.offer(myata, trackA, coverA)

        assertFalse(artwork.offer(myata, trackA, NowPlayingArtwork.NO_IMAGE))
        assertEquals(coverA, artwork.current(myata))
    }

    /** Two writers answering the same track must not swap the cover under the reader. */
    @Test
    fun `the first real answer for a track is the answer while it plays`() {
        val artwork = NowPlayingArtwork()
        artwork.announce(myata, trackA)

        assertTrue(artwork.offer(myata, trackA, coverA))
        assertFalse(artwork.offer(myata, trackA, coverB))
        assertEquals(coverA, artwork.current(myata))
    }

    @Test
    fun `each stream owns its own track and cover`() {
        val artwork = NowPlayingArtwork()

        artwork.announce(myata, trackA)
        artwork.announce(gold, trackB)
        artwork.offer(myata, trackA, coverA)
        artwork.offer(gold, trackB, coverB)

        assertEquals(coverA, artwork.current(myata))
        assertEquals(coverB, artwork.current(gold))

        // The same track on another stream is that stream's own announcement.
        assertFalse(artwork.offer(gold, trackA, coverA))
        assertEquals(coverB, artwork.current(gold))
    }

    @Test
    fun `a stream that has announced nothing accepts nothing`() {
        val artwork = NowPlayingArtwork()

        assertFalse(artwork.offer(myata, trackA, coverA))
        assertNull(artwork.current(myata))
        assertNull(artwork.identity(myata))
    }

    @Test
    fun `identity is the track key where the pair is a track`() {
        assertEquals(TrackKey.of("ROYAL BLOOD", "FIGURE IT OUT"), trackA)
        assertNotEquals(trackA, trackB)

        // Case and spacing are the two endpoints' disagreement, not a new track.
        assertEquals(trackA, NowPlayingArtwork.identityOf("royal blood", " FIGURE IT OUT "))
    }

    /**
     * The pairs [TrackKey] refuses - a blank field between tracks, the station's
     * own ident - still need an identity, or they would compare equal to each
     * other and to the track before them and inherit its cover.
     */
    @Test
    fun `the pairs that are not tracks still get identities of their own`() {
        val empty = NowPlayingArtwork.identityOf("", "")
        val ident = NowPlayingArtwork.identityOf("YOUR MUSIC! YOUR STATION!", "RADIO MYATA")
        val halfA = NowPlayingArtwork.identityOf("", "SOMETHING")
        val halfB = NowPlayingArtwork.identityOf("SOMETHING", "")

        assertNotEquals(empty, ident)
        assertNotEquals(empty, trackA)
        assertNotEquals(ident, trackA)
        assertNotEquals("the separator keeps the halves apart", halfA, halfB)

        val artwork = NowPlayingArtwork()
        artwork.announce(myata, trackA)
        artwork.offer(myata, trackA, coverA)
        assertNull("an ident does not inherit the last track's cover", artwork.announce(myata, ident))
    }

    @Test
    fun `no cover to draw is null, a marker or blank`() {
        assertNull(NowPlayingArtwork.coverUrl(null))
        assertNull(NowPlayingArtwork.coverUrl(""))
        assertNull(NowPlayingArtwork.coverUrl("   "))
        assertNull(NowPlayingArtwork.coverUrl(NowPlayingArtwork.NO_IMAGE))
        assertEquals(coverA, NowPlayingArtwork.coverUrl(coverA))
    }
}
