package com.example.musicplayerapp.service

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The private command channel, on the JVM.
 *
 * The channel is [PlaybackCommandInbox]: the app's own storage, written before the
 * service start is requested and read by the service, because the RAM queue this
 * replaced lost the listener's gesture whenever the process died between the two
 * (the P1 this file pins shut).
 *
 * ## What is real here and what is not
 *
 * The slot is a fake - a map of the *encoded strings* and nothing else - so the
 * whole queue can be exercised without a device, and so "the process died and was
 * recreated" is honest: nothing but those strings crosses from one
 * [PlaybackCommandInbox] to the next. The fake reproduces the two properties the
 * real `SharedPreferences` slot has and that this design needs: every edit lands in
 * one call, and what is read back is what was written.
 *
 * The Android half - `commit = true`, the file's name and its exclusion from
 * backup, and a service that really does act on a record it finds after its start
 * - is `PlaybackCommandHandoffTest` on a device, plus the source-level assertions
 * at the end of this file for the parts of the wiring a JVM test can read.
 */
class PlaybackCommandChannelTest {

    /**
     * App-private storage, as the inbox uses it: encoded strings on "disk" and
     * nothing in memory. A new [PlaybackCommandInbox] over the same [Disk] is a new
     * process reading records that outlived the old one.
     */
    private class Disk : PlaybackCommandInbox.Slot {

        private val values = mutableMapOf<String, String>()

        override fun read(): Map<String, String> = values.toMap()

        override fun edit(changes: Map<String, String?>) {
            for ((key, value) in changes) {
                if (value == null) values.remove(key) else values[key] = value
            }
        }

        /** Test-only: what is physically there, values only. */
        fun values(): List<String> = values.values.toList()
    }

    private val disk = Disk()

    /** The channel as a fresh process sees it: no state but what is on [disk]. */
    private fun process() = PlaybackCommandInbox(disk)

    private fun actions(): List<String> = process().pending().map { it.command.action }

    // ==================== durable before the start ====================

    /**
     * The order [ServiceUtils] depends on: [PlaybackCommandInbox.enqueue] returns
     * only once the record is on disk, so a start requested after it cannot outrun
     * the command it is a start for - whether or not this process survives.
     */
    @Test
    fun `a command is on disk before the caller asks for a start`() {
        val queued = process().enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))

        assertTrue("enqueue must have written before it returned", disk.values().isNotEmpty())
        assertNotNull(queued.id)
        assertEquals(listOf("play"), actions())
    }

    /**
     * The P1 itself: the start request survives a kill, so the command has to.
     * Nothing of the enqueueing process is reused to read it back.
     */
    @Test
    fun `a recreated process finds the command the dead one never handled`() {
        process().enqueue(PlaybackCommand.of("play", stream = "gold", nowElapsedMs = 0L))

        val recreated = process()
        val pending = recreated.pending()

        assertEquals(1, pending.size)
        assertEquals("play", pending.single().command.action)
        assertEquals("gold", pending.single().command.stream)
    }

    // ==================== each command, across the boundary ====================

    @Test
    fun `play survives recreation with everything it carries`() {
        process().enqueue(
            PlaybackCommand.of(
                "play", stream = "myata_hits", artist = "Аквариум", song = "Поезд",
                forcePlay = true, openForeground = true, nowElapsedMs = 0L,
            ),
        )

        val command = process().pending().single().command
        assertEquals("play", command.action)
        assertEquals("myata_hits", command.stream)
        assertEquals("Аквариум", command.artist)
        assertEquals("Поезд", command.song)
        assertTrue(command.forcePlay)
        assertTrue("a Play is a foreground start", command.openForeground)
    }

    @Test
    fun `stop survives recreation and does not come back as anything else`() {
        process().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L))

        assertEquals(listOf("stop"), actions())
    }

    @Test
    fun `a station switch survives recreation with the station it names`() {
        process().enqueue(
            PlaybackCommand.of("switch", stream = "gold", forcePlay = true, openForeground = true, nowElapsedMs = 0L),
        )

        val command = process().pending().single().command
        assertEquals("switch", command.action)
        assertEquals("gold", command.stream)
        assertTrue(command.forcePlay)
    }

    @Test
    fun `a track metadata update survives recreation`() {
        process().enqueue(
            PlaybackCommand.of("switch_track", stream = "myata", artist = "A", song = "B", nowElapsedMs = 0L),
        )

        val entry = process().pending().single()
        assertEquals("switch_track", entry.command.action)
        assertEquals("A", entry.command.artist)
        assertEquals("B", entry.command.song)
    }

    /**
     * A sleep timer is a duration when the listener picks it and an instant when it
     * is recorded. Handling it after a process death must re-arm *that* instant, or
     * the radio stops later than it was asked to - which is why the deadline is
     * resolved before the command is stored.
     */
    @Test
    fun `a sleep timer set survives recreation with the deadline the listener chose`() {
        val chosenAt = 1_000_000L
        process().enqueue(
            PlaybackCommand.of(
                SleepTimerContract.ACTION_SET, minutes = 30, isCustom = true,
                nowElapsedMs = chosenAt,
            ),
        )

        val command = process().pending().single().command
        assertEquals(SleepTimerContract.ACTION_SET, command.action)
        assertEquals(30, command.minutes)
        assertTrue(command.isCustom)
        assertEquals(
            "the deadline must be the instant the listener chose, not the moment of handling",
            chosenAt + 30 * 60_000L,
            command.deadlineElapsedMs,
        )
        assertFalse("a timer is not a foreground start", command.openForeground)
    }

    @Test
    fun `a sleep timer cancel survives recreation`() {
        process().enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_CANCEL, nowElapsedMs = 0L))

        val command = process().pending().single().command
        assertEquals(SleepTimerContract.ACTION_CANCEL, command.action)
        assertNull("only a set carries a deadline", command.deadlineElapsedMs)
    }

    // ==================== the foreground obligation ====================

    /**
     * The other half of the P1: a `startForegroundService` call owes Android a
     * `startForeground` within five seconds, and only the caller knows it asked for
     * one. That fact is stored with the command, so the obligation is still known
     * by a service the system recreated.
     */
    @Test
    fun `the foreground obligation survives recreation`() {
        process().enqueue(PlaybackCommand.of("play", stream = "myata", openForeground = true, nowElapsedMs = 0L))
        process().enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_SYNC, nowElapsedMs = 0L))

        val pending = process().pending()
        assertTrue(pending.first { it.command.action == "play" }.command.openForeground)
        assertFalse(
            "a command that came in on a plain startService must not manufacture one",
            pending.first { it.command.action == SleepTimerContract.ACTION_SYNC }.command.openForeground,
        )
    }

    // ==================== order ====================

    @Test
    fun `several commands survive recreation in the order they were made`() {
        val first = process().enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))
        val second = process().enqueue(PlaybackCommand.of("switch", stream = "gold", nowElapsedMs = 0L))
        val third = process().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L))

        val pending = process().pending()
        assertEquals(listOf("play", "switch", "stop"), pending.map { it.command.action })
        assertEquals(
            "identity is assigned in order and survives the boundary",
            listOf(first.id, second.id, third.id),
            pending.map { it.id },
        )
    }

    @Test
    fun `handling runs oldest first`() {
        val inbox = process()
        inbox.enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))
        inbox.enqueue(PlaybackCommand.of("switch", stream = "gold", nowElapsedMs = 0L))
        inbox.enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L))

        val ran = mutableListOf<String>()
        inbox.drain { ran += it.action }

        assertEquals(listOf("play", "switch", "stop"), ran)
    }

    /** A gesture made while the service is working is this pass's work, not the next one's. */
    @Test
    fun `a command appended during a pass is handled by that pass`() {
        val inbox = process()
        inbox.enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))

        val ran = mutableListOf<String>()
        inbox.drain { command ->
            ran += command.action
            if (command.action == "play") {
                process().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L))
            }
        }

        assertEquals(listOf("play", "stop"), ran)
        assertTrue(process().pending().isEmpty())
    }

    // ==================== acknowledgement ====================

    @Test
    fun `a command that was handled is acknowledged exactly once`() {
        val inbox = process()
        inbox.enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))

        val ran = mutableListOf<String>()
        inbox.drain { ran += it.action }
        // The next start of the service, on the same records.
        process().drain { ran += it.action }

        assertEquals("handled once, and not again on the next start", listOf("play"), ran)
        assertTrue(process().pending().isEmpty())
    }

    @Test
    fun `a start that was refused leaves nothing behind`() {
        val queued = process().enqueue(PlaybackCommand.of("play", stream = "myata", openForeground = true, nowElapsedMs = 0L))

        process().withdraw(queued.id)

        assertTrue(
            "a command whose start never happened must not be run by an unrelated start later",
            process().pending().isEmpty(),
        )
    }

    @Test
    fun `withdraw takes back its own command and not the one behind it`() {
        val refused = process().enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))
        process().enqueue(PlaybackCommand.of("switch", stream = "gold", nowElapsedMs = 0L))

        process().withdraw(refused.id)

        assertEquals(listOf("switch"), actions())
    }

    // ==================== failure ====================

    /**
     * The requirement that makes the queue trustworthy: a handler that fails must
     * not cost the listener anything. Its command stays, and so does everything
     * behind it - and it is *not* silently dropped on the way past.
     */
    @Test
    fun `a handler that fails leaves its command and everything behind it pending`() {
        val inbox = process()
        val failing = inbox.enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))
        val later = inbox.enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L))

        val attempted = mutableListOf<String>()
        inbox.drain { command ->
            attempted += command.action
            throw IllegalStateException("the handler could not run")
        }

        assertEquals("the pass stops at the failure", listOf("play"), attempted)
        assertEquals(listOf(failing.id, later.id), process().pending().map { it.id })

        // The next start retries it, and the queue moves on once it works.
        val ran = mutableListOf<String>()
        process().drain { ran += it.action }
        assertEquals(listOf("play", "stop"), ran)
        assertTrue(process().pending().isEmpty())
    }

    /**
     * The other side of that: a command that can never work must not sit in front of
     * every later Play press for the life of the install. It is dropped after a
     * bounded number of attempts, and the queue behind it is not.
     */
    @Test
    fun `a command that always fails is dropped after a bounded number of attempts`() {
        val inbox = process()
        inbox.enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))
        inbox.enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L))

        repeat(PlaybackCommandInbox.MAX_HANDLER_ATTEMPTS) {
            inbox.drain { throw IllegalStateException("still broken") }
        }

        assertEquals("the broken command is gone, the one behind it is not", listOf("stop"), actions())

        val ran = mutableListOf<String>()
        process().drain { ran += it.action }
        assertEquals(listOf("stop"), ran)
    }

    @Test
    fun `an attempt that does not exist cannot be counted against a pending one`() {
        val inbox = process()
        val queued = inbox.enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))
        inbox.ack(queued.id)

        assertTrue("nothing left to fail", inbox.noteFailure(queued.id))
        assertTrue(process().pending().isEmpty())
    }

    // ==================== unreadable records ====================

    /**
     * A record this code cannot read is not run and not retried: what it said is
     * unknowable, and guessing would be guessing with the speaker.
     */
    @Test
    fun `an unreadable record is dropped rather than run or retried`() {
        val inbox = process()
        inbox.enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))

        val corrupt = disk.read().keys.associateWith { "<not a record>" }
        disk.edit(corrupt)

        assertTrue(process().pending().isEmpty())

        // And the queue still works afterwards.
        process().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L))
        assertEquals(listOf("stop"), actions())
    }

    // ==================== identity ====================

    @Test
    fun `identity keeps climbing, so a re-used slot cannot re-acknowledge an old id`() {
        val first = process().enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))
        process().ack(first.id)
        val second = process().enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))

        assertTrue(
            "ids must never be reused: an id is how a command is acknowledged",
            second.id.toLong() > first.id.toLong(),
        )
    }

    // ==================== the resolved requests ====================

    /**
     * The replay-safety of the two commands that could not state their own meaning.
     * Both are resolved where the listener makes the gesture, by the one factory
     * every caller goes through.
     */
    @Test
    fun `startStop is stored as a desire, never as a question`() {
        val toggle = PlaybackCommand.of(PlaybackCommand.ACTION_START_STOP, stream = "myata", nowElapsedMs = 0L)

        assertEquals(
            "every call site means \"start playback\"; a stored toggle replayed as Pause",
            true,
            toggle.desiredPlaying,
        )
        assertNull("only startStop has a desire to resolve", PlaybackCommand.of("play", nowElapsedMs = 0L).desiredPlaying)
        assertNull(PlaybackCommand.of("stop", nowElapsedMs = 0L).desiredPlaying)
        assertNull(PlaybackCommand.of("switch", stream = "gold", nowElapsedMs = 0L).desiredPlaying)
    }

    @Test
    fun `only a sleep timer set is resolved to a deadline`() {
        val set = PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 15, nowElapsedMs = 500L)
        assertEquals(500L + 15 * 60_000L, set.deadlineElapsedMs)

        for (action in listOf(
            SleepTimerContract.ACTION_CANCEL,
            SleepTimerContract.ACTION_UNDO,
            SleepTimerContract.ACTION_SYNC,
            "play",
            "startStop",
        )) {
            assertNull("$action carries no deadline", PlaybackCommand.of(action, nowElapsedMs = 500L).deadlineElapsedMs)
        }
    }

    /**
     * A command with awkward characters in it - the artist and title come from a
     * stream's own metadata feed - has to come back as itself, which is what the
     * record's escaping is for.
     */
    @Test
    fun `a record survives the characters that would break a positional format`() {
        val artist = "AC%\tDC\n\"AC/DC\"" //tab, newline, percent, quotes
        val song = "Back in Black\r\n"
        process().enqueue(PlaybackCommand.of("switch_track", artist = artist, song = song, nowElapsedMs = 0L))

        val command = process().pending().single().command
        assertEquals(artist, command.artist)
        assertEquals(song, command.song)
    }

    // ==================== the wiring a JVM test can read ====================

    /**
     * The start is requested *after* the record is written, in the one place a
     * command meets a start, and the record is taken back when the platform refuses
     * that start. Both halves are asserted against the source because the ordering
     * between them is the property, and a JVM test cannot make a real start.
     */
    @Test
    fun `the command is written before the start and taken back when the start is refused`() {
        val source = file("src/main/java/com/example/musicplayerapp/utils/ServiceUtils.kt").readText()

        val enqueue = source.indexOf("PlaybackCommandInbox.forContext(context).enqueue(")
        val startForeground = source.indexOf("startForegroundService(context, intent)")
        val start = source.indexOf("context.startService(intent)")
        val withdraw = source.indexOf("PlaybackCommandInbox.forContext(context).withdraw(")

        assertTrue("the inbox is written in ServiceUtils", enqueue >= 0)
        assertTrue("a command is recorded before the foreground start", enqueue < startForeground)
        assertTrue("a command is recorded before the plain start", enqueue < start)
        assertTrue("a refused start takes its command back out", withdraw > enqueue)
    }

    /** `commit`, not `apply`: a write that returns before the file does is not durable. */
    @Test
    fun `the production slot commits synchronously`() {
        val source = source("service/PlaybackCommandInbox.kt")

        assertTrue("the inbox must commit, not apply", source.contains("edit(commit = true)"))
        assertFalse("apply() returns before the file is written", source.contains(".apply()"))
    }

    /**
     * One writer, one reader, and both inside the app: a command can only be added
     * by the app's own screens and can only be consumed by its own service.
     */
    @Test
    fun `the inbox has exactly two ends, one in the app's screens and one in the service`() {
        val reachingIt = productionFiles()
            .filter { it.readText().contains("PlaybackCommandInbox.forContext(") }
            .map { it.name }
            .toSortedSet()

        assertEquals(
            "a command is added by the one door that also starts the service, and consumed " +
                "by the service's start path - nothing else in the app touches the inbox",
            sortedSetOf("MediaPlayerService.kt", "ServiceUtils.kt"),
            reachingIt,
        )

        val screens = source("utils/ServiceUtils.kt")
        assertTrue(
            "the adding end",
            screens.contains("PlaybackCommandInbox.forContext(context).enqueue("),
        )
        assertTrue(
            "and the end that takes a refused start back out",
            screens.contains("PlaybackCommandInbox.forContext(context).withdraw("),
        )

        val service = source("service/MediaPlayerService.kt")
        assertTrue("the consuming end reads the inbox", service.contains("inbox.pending()"))
        assertTrue("and hands it to the one pass that acknowledges", service.contains("inbox.drain {"))
    }

    // ==================== not a thing a backup may carry ====================

    /**
     * One device's gesture at one device's speaker. Restored onto a new phone it
     * would be an instruction nobody gave there, and the exclusion has to be in
     * both rule files: `fullBackupContent` is API 24-30, `dataExtractionRules` is
     * 31+ and has a separate section per direction data can leave in.
     */
    @Test
    fun `the inbox is excluded from cloud backup and device transfer`() {
        val excluded = "sharedpref:${PlaybackCommandInbox.FILE}.xml"
        assertEquals("myata_playback_commands", PlaybackCommandInbox.FILE)

        assertTrue(
            "$excluded must be excluded on API 24-30",
            excluded in excludes("src/main/res/xml/lastfm_backup_rules.xml"),
        )

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file("src/main/res/xml/lastfm_data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val nodes = doc.getElementsByTagName(section)
            assertEquals("one <$section>", 1, nodes.length)
            val found = excludes(nodes.item(0) as Element)
            assertTrue("$excluded must be excluded from <$section>, found $found", excluded in found)
        }
    }

    // ============================ plumbing ============================

    private fun excludes(path: String): List<String> =
        excludes(
            DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(file(path))
                .documentElement,
        )

    private fun excludes(root: Element): List<String> {
        val nodes = root.getElementsByTagName("exclude")
        return (0 until nodes.length).map {
            val e = nodes.item(it) as Element
            "${e.getAttribute("domain")}:${e.getAttribute("path")}"
        }
    }

    private fun productionFiles(): List<File> =
        File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun source(path: String): String =
        file("src/main/java/com/example/musicplayerapp/$path").readText()

    /** Unit tests run from the module directory; the positive control is that the file exists. */
    private fun file(path: String): File = File(path).also {
        assertTrue("expected ${it.absolutePath} to exist", it.isFile)
    }
}
