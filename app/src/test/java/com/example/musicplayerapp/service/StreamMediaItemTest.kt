package com.example.musicplayerapp.service

import com.example.musicplayerapp.data.Streams
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every stream item carries its stream key, and that is load-bearing.
 *
 * Media3 strips `localConfiguration` - and with it the URI - from every
 * `MediaItem` it hands across the session boundary, so a `MediaController` cannot
 * see where a stream is coming from. The media id is what survives, and it is the
 * only way the UI can ask the session which station is playing.
 *
 * That was not academic: `StreamsViewModel.onMediaMetadataChanged` used to
 * identify the stream by matching `localConfiguration.uri` against "gold" and
 * "myata_hits". On a controller that value is always null, so the match always
 * fell through to the UI's own current selection and validated nothing at all.
 */
class StreamMediaItemTest {

    @Test
    fun `an item is labelled with its stream key`() {
        for (key in listOf(Streams.MYATA, Streams.GOLD, Streams.XTRA)) {
            assertEquals(key, streamMediaItem("https://radio.dline-media.com/x", key).mediaId)
        }
    }

    @Test
    fun `the label is a key the UI can resolve back to a stream`() {
        // A round trip through the same normaliser the ViewModel uses: a label
        // Streams cannot read would leave the UI with no answer from the session.
        for (key in listOf(Streams.MYATA, Streams.GOLD, Streams.XTRA)) {
            val item = streamMediaItem("https://radio.dline-media.com/x", key)
            assertEquals(key, Streams.normalise(item.mediaId))
        }
    }

    /**
     * The six stream items are all built through the labelled helper.
     *
     * `MediaItem.fromUri` leaves the media id at Media3's default, which is the
     * state this slice replaced. One of the six reverting would silently take the
     * session's answer away from the UI, and nothing else would fail.
     */
    @Test
    fun `the service builds no stream item without a label`() {
        val source = File("src/main/java/com/example/musicplayerapp/service/MediaPlayerService.kt")
        assertTrue("expected ${source.absolutePath} to exist", source.isFile)
        val text = source.readText()
        assertEquals(
            "MediaItem.fromUri leaves the media id unset - use streamMediaItem",
            0,
            Regex("MediaItem\\.fromUri").findAll(text).count(),
        )
        assertEquals(
            "six stream items: three stations, HTTPS and the HTTP fallback",
            6,
            Regex("= streamMediaItem\\(").findAll(text).count(),
        )
    }
}
