package com.example.musicplayerapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allow-list is what stops a framework intent action being used as a stream
 * key, which is how a launch could end up preparing a player with no media item
 * and playing nothing at all (issue #14).
 */
class StreamsTest {

    @Test
    fun `known stream keys are accepted`() {
        assertEquals(Streams.MYATA, Streams.normalise("myata"))
        assertEquals(Streams.GOLD, Streams.normalise("gold"))
        assertEquals(Streams.XTRA, Streams.normalise("myata_hits"))
    }

    @Test
    fun `framework intent actions are not stream keys`() {
        assertNull(Streams.normalise("android.intent.action.PLAY"))
        assertNull(Streams.normalise("android.intent.action.MAIN"))
        assertNull(Streams.normalise("android.intent.action.VIEW"))
        assertNull(Streams.normalise("android.media.action.MEDIA_PLAY_FROM_SEARCH"))
    }

    @Test
    fun `empty blank and null are rejected`() {
        assertNull(Streams.normalise(null))
        assertNull(Streams.normalise(""))
        assertNull(Streams.normalise("   "))
    }

    @Test
    fun `unknown values are rejected`() {
        assertNull(Streams.normalise("myata_hitsX"))
        assertNull(Streams.normalise("radio"))
        assertNull(Streams.normalise("../myata"))
    }

    @Test
    fun `xtra is accepted as an alias for the hits stream`() {
        assertEquals(Streams.XTRA, Streams.normalise("xtra"))
        assertEquals(Streams.XTRA, Streams.normalise("XTRA"))
    }

    @Test
    fun `case and surrounding whitespace are tolerated`() {
        assertEquals(Streams.MYATA, Streams.normalise("  MyAtA  "))
        assertEquals(Streams.GOLD, Streams.normalise("GOLD"))
    }

    @Test
    fun `isKnown only accepts canonical keys`() {
        assertTrue(Streams.isKnown(Streams.MYATA))
        assertTrue(Streams.isKnown(Streams.GOLD))
        assertTrue(Streams.isKnown(Streams.XTRA))
        assertFalse(Streams.isKnown("xtra"))
        assertFalse(Streams.isKnown("android.intent.action.PLAY"))
        assertFalse(Streams.isKnown(null))
        assertFalse(Streams.isKnown(""))
    }

    @Test
    fun `the default is a known stream`() {
        assertTrue(Streams.isKnown(Streams.DEFAULT))
    }
}
