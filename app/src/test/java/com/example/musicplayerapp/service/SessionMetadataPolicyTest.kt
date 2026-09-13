package com.example.musicplayerapp.service

import com.example.musicplayerapp.service.SessionMetadataPolicy.Installed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G5d: notification / lock-screen metadata after pause -> Play.
 *
 * The reproduced bug: `startStop` installs a bare stream MediaItem, `updateMetadata`
 * is then called with the same artist and title and skipped, and the session falls
 * back to the raw ICY title with no artist and no artwork.
 */
class SessionMetadataPolicyTest {

    private val cover = "https://is1-ssl.mzstatic.com/image/thumb/a/600x600bb.jpg"

    @Test
    fun `a bare item after resume is not the installed track`() {
        assertFalse(SessionMetadataPolicy.isInstalled(Installed(null, null, null), "HOME", "JUNIOR VARSITY", cover))
    }

    @Test
    fun `no current item is not the installed track`() {
        assertFalse(SessionMetadataPolicy.isInstalled(null, "HOME", "JUNIOR VARSITY", null))
    }

    @Test
    fun `a raw stream title in place of the structured one is not installed`() {
        val icyLike = Installed("JUNIOR VARSITY - HOME", null, null)
        assertFalse(SessionMetadataPolicy.isInstalled(icyLike, "HOME", "JUNIOR VARSITY", null))
    }

    @Test
    fun `title and artist without the known cover are not installed`() {
        assertFalse(SessionMetadataPolicy.isInstalled(Installed("HOME", "JUNIOR VARSITY", null), "HOME", "JUNIOR VARSITY", cover))
    }

    @Test
    fun `the same track with the same cover is installed`() {
        assertTrue(SessionMetadataPolicy.isInstalled(Installed("HOME", "JUNIOR VARSITY", cover), "HOME", "JUNIOR VARSITY", cover))
    }

    @Test
    fun `the same track with no cover found is installed`() {
        assertTrue(SessionMetadataPolicy.isInstalled(Installed("HOME", "JUNIOR VARSITY", null), "HOME", "JUNIOR VARSITY", null))
    }

    @Test
    fun `another track's cover on the item is not installed`() {
        val other = "https://is1-ssl.mzstatic.com/image/thumb/b/600x600bb.jpg"
        assertFalse(SessionMetadataPolicy.isInstalled(Installed("HOME", "JUNIOR VARSITY", other), "HOME", "JUNIOR VARSITY", cover))
    }

    @Test
    fun `a new track always looks its cover up`() {
        assertTrue(fetch(trackChanged = true, settled = true, inFlight = true))
        assertTrue(fetch(trackChanged = true, settled = false, inFlight = false))
    }

    @Test
    fun `a refresh of the same track does not restart an in-flight lookup`() {
        assertFalse(fetch(trackChanged = false, settled = false, inFlight = true))
    }

    @Test
    fun `a refresh of the same track does not repeat a finished lookup`() {
        assertFalse(fetch(trackChanged = false, settled = true, inFlight = false))
    }

    @Test
    fun `a same-track lookup that was lost is started again`() {
        assertTrue(fetch(trackChanged = false, settled = false, inFlight = false))
    }

    @Test
    fun `a placeholder reset never looks a cover up`() {
        assertFalse(SessionMetadataPolicy.shouldFetchArtwork(true, trackChanged = true, artworkSettled = false, fetchInFlight = false))
        assertFalse(SessionMetadataPolicy.shouldFetchArtwork(true, trackChanged = false, artworkSettled = false, fetchInFlight = false))
    }

    private fun fetch(trackChanged: Boolean, settled: Boolean, inFlight: Boolean) =
        SessionMetadataPolicy.shouldFetchArtwork(false, trackChanged, settled, inFlight)
}
