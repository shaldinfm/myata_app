package com.example.musicplayerapp.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The notification's cover bitmap is downloaded through the app's artwork
 * pipeline - the shared image client and its disk cache - and not through the
 * service's own HTTP client.
 *
 * With its own client the service downloaded every cover once for the
 * notification, and the PLAYER and the Mini Player, which read the artwork cache,
 * then downloaded it a second time (measured on device, UI polish pass: the
 * screen's offline-only first attempt missed right after the service had fetched
 * the same URL). A source check, because the service's download is not reachable
 * from a JVM test without a device, and the rule is narrow enough to state
 * exactly: this one function, and only it, is on the artwork client.
 */
class NotificationArtworkClientTest {

    private val service: String by lazy {
        val file = listOf(
            File("src/main/java/com/example/musicplayerapp/service/MediaPlayerService.kt"),
            File("app/src/main/java/com/example/musicplayerapp/service/MediaPlayerService.kt"),
        ).firstOrNull { it.isFile } ?: error("cannot locate MediaPlayerService.kt from ${File("").absolutePath}")
        file.readText()
    }

    /** The body of `loadAlbumArtBitmap`, up to the next function declaration. */
    private val loadAlbumArtBitmap: String by lazy {
        val start = service.indexOf("private suspend fun loadAlbumArtBitmap(")
        check(start >= 0) { "loadAlbumArtBitmap is gone - update this test with its replacement" }
        val end = service.indexOf("private fun ", start + 1).takeIf { it > 0 } ?: service.length
        service.substring(start, end)
    }

    @Test
    fun `the notification cover is downloaded once, into the shared artwork cache`() {
        assertTrue(
            "the download must go through ArtworkResolver.warmImage, shared with the ViewModel",
            loadAlbumArtBitmap.contains("artworkResolver.warmImage(imageUrl)"),
        )
        assertTrue(
            "the bitmap must be read through the artwork image client",
            loadAlbumArtBitmap.contains("artworkImageClient.newCall("),
        )
        assertFalse(
            "the service's own client has no artwork cache",
            loadAlbumArtBitmap.contains("httpClient.newCall("),
        )
        assertTrue(
            "artworkImageClient must be ArtworkModule's image client",
            service.contains("artworkImageClient by lazy { com.example.musicplayerapp.data.ArtworkModule.imageClient(this) }"),
        )
    }
}
