package com.example.musicplayerapp.service

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The exported service, held to what it can still be made to do.
 *
 * ## The finding this pins
 *
 * `MediaPlayerService` is a `MediaSessionService` and it is exported, and its
 * `onStartCommand` used to read `ACTION` / `STREAM` / `ARTIST` / `SONG` /
 * `force_play` / `MINUTES` / `IS_CUSTOM` out of whatever intent started it. Any app
 * on the device could send that intent and have this app start a station, stop it,
 * re-write the notification or arm a sleep timer. An exported component cannot
 * authenticate a start command - there is no caller identity in `onStartCommand` -
 * so the protocol had to stop being an intent, not be guarded inside one. See
 * [PlaybackCommand].
 *
 * ## What can be checked here, and what cannot
 *
 * A JVM test cannot send an intent to a service, so the *live* answer is
 * `PlaybackCommandBoundaryTest` on a device. What is checkable here, with no
 * device and no build variant, is the half that would silently rot: that the
 * exported component reads no extra at all, that exactly one file starts the
 * service and its intent carries nothing, and that the exported declaration is
 * still the Media3 session and the media button - which is what the endpoint is
 * *for*, and the reason it is not simply unexported.
 */
class PlaybackCommandExposureTest {

    // ---- 1. the exported component takes no instruction ----------------------------------

    /**
     * Not "the known actions are refused" but "nothing is read": a reader added later
     * for a new extra would be a new hole with this test as the thing that says so.
     */
    @Test
    fun `MediaPlayerService reads no intent extra`() {
        val source = source("service/MediaPlayerService.kt")
        for (read in listOf(
            "getStringExtra(",
            "getIntExtra(",
            "getBooleanExtra(",
            "getLongExtra(",
            "getParcelableExtra(",
            "getSerializableExtra(",
        )) {
            assertFalse(
                "the exported start path must not read `$read`: an extra on a start " +
                    "intent for an exported component is a remote control",
                source.contains(read),
            )
        }
    }

    /**
     * One door into the service, and it is the one that hands the command over first.
     * A second caller starting the service directly would still not be able to make
     * it do anything - the command queue is the only input - but it would be a place
     * where the wiring can drift out of step with the queue, and that is worth a test.
     */
    @Test
    fun `the only production code that starts the service is ServiceUtils`() {
        val starters = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter {
                val text = it.readText()
                text.contains(".startService(") || text.contains(".startForegroundService(")
            }
            .map { it.name }
            .toSortedSet()
        assertEquals(
            "every service start must go through the one place that queues the command first",
            sortedSetOf("ServiceUtils.kt"),
            starters,
        )
    }

    /**
     * The other half of the same door: the intent itself is empty, so a start is a
     * wake-up and a lifecycle signal and nothing more.
     */
    @Test
    fun `the start intent carries no extras`() {
        val source = source("utils/ServiceUtils.kt")
        assertFalse(
            "the start intent must stay empty - the command travels through the app's own " +
                "private storage, which is the only thing another app cannot write to",
            source.contains("putExtra("),
        )
    }

    /** The protocol this replaced, gone from production rather than merely unused. */
    @Test
    fun `no production code writes the app-private protocol as intent extras`() {
        val writers = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("putExtra(\"ACTION\"") }
            .map { it.name }
            .toSortedSet()
        assertTrue(
            "these files would put the private protocol back on an exported start " +
                "intent: $writers",
            writers.isEmpty(),
        )
    }

    // ---- 2. what the exported endpoint is for is unchanged ------------------------------

    /**
     * Exported, and only as a session: Media3's own service action for the session
     * and the media button the notification's own controls arrive on. Nothing else
     * was added, and this is the assertion that keeps the export decision explicit -
     * the endpoint stays reachable, the private protocol is no longer what reaches
     * through it.
     */
    @Test
    fun `the service is exported for the Media3 session and the media button only`() {
        val service = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file("src/main/AndroidManifest.xml"))
            .let { doc -> (0 until doc.getElementsByTagName("service").length).map { doc.getElementsByTagName("service").item(it) as Element } }
            .single { it.getAttribute("android:name") == ".service.MediaPlayerService" }

        assertEquals("the session endpoint stays reachable", "true", service.getAttribute("android:exported"))
        assertEquals("mediaPlayback", service.getAttribute("android:foregroundServiceType"))

        val actions = service.getElementsByTagName("action")
        assertEquals(
            listOf(
                "androidx.media3.session.MediaSessionService",
                "android.intent.action.MEDIA_BUTTON",
            ),
            (0 until actions.length).map { (actions.item(it) as Element).getAttribute("android:name") },
        )
    }

    /** The app-private actions are in-process identifiers; none of them is a manifest action. */
    @Test
    fun `no app-private command is declared as a manifest action`() {
        val manifest = file("src/main/AndroidManifest.xml").readText()
        for (action in listOf(
            "play",
            "startStop",
            "switch",
            "switch_track",
            "get_status",
            "stop",
            PlaybackIntentContract.ACTION_RESTORE,
            SystemPlaybackEventContract.ACTION_BECOMING_NOISY,
            SleepTimerContract.ACTION_SET,
            SleepTimerContract.ACTION_CANCEL,
            SleepTimerContract.ACTION_UNDO,
            SleepTimerContract.ACTION_SYNC,
        )) {
            assertFalse(
                "`$action` must never become a manifest action - a filter is a door",
                manifest.contains("\"$action\""),
            )
        }
    }

    /** Unit tests run from the module directory; the positive control is that the file exists. */
    private fun file(path: String): File = File(path).also {
        assertTrue("expected ${it.absolutePath} to exist", it.isFile)
    }

    private fun source(path: String): String =
        file("src/main/java/com/example/musicplayerapp/$path").readText()
}
