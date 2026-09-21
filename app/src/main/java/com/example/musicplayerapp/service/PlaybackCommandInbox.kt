package com.example.musicplayerapp.service

import android.content.Context
import androidx.core.content.edit

/**
 * The durable inbox between the app's own screens and the one service that owns
 * playback.
 *
 * ## Why this exists
 *
 * The commands were process memory for one slice of this work, and that was a P1.
 * A caller enqueued in RAM and then asked the system to start the service; the
 * system could accept the start and kill the process before the service ever ran
 * its `onStartCommand`. The **start request survives that** - the service is
 * `START_STICKY` and comes back - and the RAM queue did not, so the listener's
 * gesture was gone: a Play, a Stop, a station switch, a sleep timer, or the
 * foreground obligation a `startForegroundService` call leaves behind.
 *
 * The command therefore goes to disk first, in the app's own private storage, and
 * the service reads it from there:
 *
 * ```
 *   Activity / Fragment / ViewModel                (any thread)
 *            │
 *            ├── inbox.enqueue(command)  ── committed to app-private prefs ──┐
 *            │                                                              │
 *            └── bare start intent, no extras ──────────┐                   │
 *                                                       ▼                   ▼
 *                        MediaPlayerService.onStartCommand
 *                             inbox.pending() ──▶ handle one ──▶ inbox.ack(id)
 * ```
 *
 * ## The boundary
 *
 * `SharedPreferences` inside the app's private data directory. Only a process
 * running as this app's uid can write it: an external app cannot reach the file,
 * and it cannot reach this API either - which is what replaced "it lives in our
 * process" as the thing that keeps the protocol private. The exported
 * `MediaPlayerService` keeps taking bare starts from anyone, and a bare start
 * carries no instruction, so an arbitrary app starting it can only make the
 * service *look* at the inbox - never add to it, never remove from it, never
 * reorder it. Waking the service while legitimate commands are pending is
 * acceptable and is exactly what a `START_STICKY` restart does anyway.
 *
 * ## One at a time, acknowledged after the handler
 *
 * [drain] is the service's way in, and it runs the head command and nothing else.
 * [pending] reads and changes nothing, and [ack] - called by [drain], once the
 * handler has returned - is the one place a command leaves the queue on the success
 * path. So:
 *
 *  - a process death *before* a handler runs leaves the command where it was, and
 *    the next start of the service runs it - this is the property the fix is for;
 *  - a process death *after* a handler ran replays it, which is why every handler
 *    is replay-safe and why the two requests that were not (a toggle, a duration)
 *    are resolved before they are stored - see [PlaybackCommand];
 *  - a handler that fails keeps its command **and everything behind it** pending
 *    ([noteFailure] counts the failures so one broken command cannot block the
 *    queue forever).
 *
 * [withdraw] is the other way out, and it is not an acknowledgement: it is for a
 * start the platform refused, where the command never reached the service at all.
 * A command that never reached the service has to leave nothing behind, or the
 * next start of any kind - minutes later, for a different reason - would act on a
 * gesture the listener had stopped expecting anything from.
 *
 * ## Order, identity, and who may write
 *
 * Each record is one preference key, `command_<sequence>`, and the sequence is a
 * monotonic counter (`next_sequence`) that is never reused, even by a process that
 * dies immediately after an [enqueue]. Order is the sequence, so FIFO survives
 * both a process death and concurrent callers: every read-modify-write here runs
 * under one process-wide lock, so two threads cannot interleave an append.
 *
 * The lock is process-wide rather than per-instance because there is exactly one
 * writer of each record at a time in production - a screen appends, the service
 * acknowledges - and both go through the one instance [forContext] hands out.
 *
 * ## Not backed up
 *
 * `myata_playback_commands.xml` is excluded from cloud backup and device transfer
 * (`lastfm_backup_rules.xml`, `lastfm_data_extraction_rules.xml`), for the same
 * reason `PlaybackIntentStore` is: a pending command is one device's gesture at one
 * device's speaker. Restored onto a new phone it would be an instruction nobody
 * gave there.
 *
 * ## What is not stamped
 *
 * No boot identity, unlike `SleepTimerStore`. Nothing starts this service at boot
 * (there is no `BOOT_COMPLETED` receiver anywhere), so a record that outlives a
 * reboot is never acted on by itself: it is read when something has already
 * started the service, and a stale record is consumed before whatever that start
 * was for. Stamping the boot would mean dropping every pending command on the
 * devices where the boot counter cannot be read at all, which is the opposite of
 * what this inbox is for.
 */
internal class PlaybackCommandInbox(private val slot: Slot) {

    /**
     * One waiting command, with the stable identity that makes its
     * acknowledgement possible. [id] is unique for the life of the install.
     */
    class Entry(val id: String, val command: PlaybackCommand)

    /**
     * Where the records live. An interface so the queue's own rules - order,
     * identity, acknowledgement, retry - can be pinned on the JVM against a
     * fake, with the real `SharedPreferences` construction left to
     * `PlaybackCommandHandoffTest` on a device.
     */
    interface Slot {

        /** Every key this inbox owns, in no particular order. */
        fun read(): Map<String, String>

        /**
         * Applies every change in one commit. A `null` value removes the key.
         * One edit is what makes an append atomic: the record and the advanced
         * sequence land together, or neither does.
         */
        fun edit(changes: Map<String, String?>)
    }

    /**
     * Records [command] durably and returns it with its identity.
     *
     * Synchronous by contract, and that is the point: the caller requests the
     * service start only after this returns, so there is no instant in which the
     * start exists and the command does not.
     */
    fun enqueue(command: PlaybackCommand): Entry = synchronized(LOCK) {
        val stored = slot.read()
        val decoded = decode(stored)
        // The stored counter, and - in case a truncated file left a record without
        // it - the highest sequence actually present. Either way the new id is one
        // no record has ever used.
        val sequence = maxOf(
            stored[KEY_SEQUENCE]?.toLongOrNull() ?: 0L,
            decoded.entries.maxOfOrNull { it.sequence } ?: 0L,
        ) + 1L

        slot.edit(
            mapOf(
                KEY_SEQUENCE to sequence.toString(),
                keyFor(sequence) to PlaybackCommandCodec.encode(command, attempts = 0),
            ),
        )
        Entry(sequence.toString(), command)
    }

    /**
     * Everything waiting, in the order it was enqueued. Reads only.
     *
     * A record that cannot be decoded is removed here rather than left to fail
     * again on every start - its contents are unknowable, so there is nothing to
     * run - and that removal is the only write this method ever makes.
     */
    fun pending(): List<Entry> = synchronized(LOCK) {
        val decoded = decode(slot.read())
        if (decoded.unreadable.isNotEmpty()) {
            PlaybackLog.problem("COMMAND_UNREADABLE", "records" to decoded.unreadable.size)
            slot.edit(decoded.unreadable.associateWith { null })
        }
        decoded.entries.map { Entry(it.sequence.toString(), it.command) }
    }

    /**
     * The command has been handled: it leaves the queue, exactly once.
     *
     * Called after the handler, never before it. The window this leaves is the
     * reason every handler is replay-safe.
     */
    fun ack(id: String) = synchronized(LOCK) {
        slot.edit(mapOf(keyFor(id) to null))
    }

    /**
     * Runs everything waiting, one command at a time, oldest first.
     *
     * The whole handover, start to finish: take the head, run [handler] on it, and
     * acknowledge it only once that has returned. The queue is re-read between
     * commands because the acknowledgement changes which command is the head, so a
     * command appended while this runs is picked up by *this* pass rather than left
     * for the next start.
     *
     *  - a handler that returns leaves its command acknowledged exactly once, so a
     *    later start finds nothing to do;
     *  - a handler that throws leaves its command - and everything behind it -
     *    pending and ends this pass, so the next start retries it rather than
     *    skipping the listener's gesture;
     *  - [noteFailure] counts those failures and drops a command that has exhausted
     *    them, so one broken command cannot block the queue for the life of the
     *    install.
     */
    fun drain(handler: (PlaybackCommand) -> Unit) {
        while (true) {
            val entry = pending().firstOrNull() ?: return
            val handled = try {
                handler(entry.command)
                true
            } catch (e: Exception) {
                PlaybackLog.problem(
                    "COMMAND_FAILED",
                    "action" to entry.command.action, "id" to entry.id,
                    "cause" to e.javaClass.simpleName,
                )
                false
            }

            when {
                handled -> ack(entry.id)
                noteFailure(entry.id) -> PlaybackLog.problem(
                    "COMMAND_DISCARDED",
                    "action" to entry.command.action, "id" to entry.id,
                    "attempts" to MAX_HANDLER_ATTEMPTS,
                )
                else -> return
            }
        }
    }

    /**
     * The start never happened: take the command back out, unused.
     *
     * Not an acknowledgement - nothing ran. See the class docs for why a refused
     * start must leave nothing behind.
     */
    fun withdraw(id: String) = synchronized(LOCK) {
        slot.edit(mapOf(keyFor(id) to null))
    }

    /**
     * A handler failed. Counts the failure and keeps the command pending.
     *
     * Returns true when that was the last attempt and the record was removed -
     * one command that throws every time must not block every Play after it for
     * the life of the install - and false while it is still waiting to be retried
     * by a later start.
     */
    fun noteFailure(id: String): Boolean = synchronized(LOCK) {
        val key = keyFor(id)
        val raw = slot.read()[key]
        val decoded = raw?.let(PlaybackCommandCodec::decode)
        when {
            decoded == null -> {
                // Gone, or unreadable and therefore unretryable. Either way there
                // is nothing left to keep.
                slot.edit(mapOf(key to null))
                true
            }
            decoded.attempts + 1 >= MAX_HANDLER_ATTEMPTS -> {
                slot.edit(mapOf(key to null))
                true
            }
            else -> {
                slot.edit(mapOf(key to PlaybackCommandCodec.encode(decoded.command, decoded.attempts + 1)))
                false
            }
        }
    }

    /** Test-only: leave this install's inbox the way a fresh one finds it. */
    fun clearForTest() = synchronized(LOCK) {
        slot.edit(slot.read().keys.associateWith { null })
    }

    // ============================ read ============================

    private class Decoded(val sequence: Long, val attempts: Int, val command: PlaybackCommand)

    private class Read(val entries: List<Decoded>, val unreadable: List<String>)

    /** Decodes a whole slot: the commands in sequence order, and what could not be read. */
    private fun decode(stored: Map<String, String>): Read {
        val entries = mutableListOf<Decoded>()
        val unreadable = mutableListOf<String>()

        for ((key, raw) in stored) {
            if (!key.startsWith(KEY_PREFIX)) continue
            val sequence = key.removePrefix(KEY_PREFIX).toLongOrNull()
            val decoded = raw.let(PlaybackCommandCodec::decode)
            if (sequence == null || decoded == null) {
                unreadable += key
            } else {
                entries += Decoded(sequence, decoded.attempts, decoded.command)
            }
        }

        entries.sortBy { it.sequence }
        return Read(entries, unreadable)
    }

    private fun keyFor(sequence: Long): String = "$KEY_PREFIX$sequence"

    private fun keyFor(id: String): String = "$KEY_PREFIX$id"

    companion object {

        /** Kept public so the backup-rule test can assert the excluded path is this one. */
        const val FILE = "myata_playback_commands"

        /**
         * How many times one command may fail before it is dropped. Three, so a
         * transient failure - the player being torn down mid-command, say - is
         * retried by the next starts, and a command that can never work does not
         * sit in front of every later Play press.
         */
        const val MAX_HANDLER_ATTEMPTS = 3

        private const val KEY_SEQUENCE = "next_sequence"
        private const val KEY_PREFIX = "command_"

        /**
         * One lock for every inbox in this process: the append a screen makes and
         * the acknowledgement the service makes are read-modify-writes over the
         * same keys, and ordering is the property they must not corrupt.
         */
        private val LOCK = Any()

        @Volatile
        private var process: PlaybackCommandInbox? = null

        /** The process's one inbox - the callers' half and the service's half. */
        fun forContext(context: Context): PlaybackCommandInbox = process ?: synchronized(this) {
            process ?: PlaybackCommandInbox(PrefsSlot(context.applicationContext)).also { process = it }
        }

        /**
         * `commit = true`, deliberately: `apply()` returns before the file is
         * written and is lost if the process is killed in between, which is
         * precisely the event this inbox exists for. A command is written once, by
         * a listener pressing something, so the synchronous write costs nothing
         * that matters - the same trade `PlaybackIntentStore` makes.
         */
        private class PrefsSlot(private val context: Context) : Slot {

            override fun read(): Map<String, String> =
                prefs().all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

            override fun edit(changes: Map<String, String?>) {
                prefs().edit(commit = true) {
                    for ((key, value) in changes) {
                        if (value == null) remove(key) else putString(key, value)
                    }
                }
            }

            private fun prefs() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }
}

/**
 * One command as a string, and back.
 *
 * Tab-separated fields, each one escaped, and a version field first: an inbox
 * record outlives the process that wrote it, so a record this code cannot read has
 * to be *recognisable* rather than silently misread as some other command - which
 * is what a positional format with no version marker would do the first time a
 * field is added.
 *
 * Hand-rolled rather than a serialization library on purpose: this is the one
 * record whose misreading would start a radio, so every field is written and read
 * explicitly here, and a missing or malformed field is refused instead of
 * defaulting into a plausible-looking command.
 */
private object PlaybackCommandCodec {

    private const val VERSION = "1"
    private const val SEPARATOR = '\t'
    private const val FIELD_COUNT = 12

    private const val IDX_VERSION = 0
    private const val IDX_ATTEMPTS = 1
    private const val IDX_ACTION = 2
    private const val IDX_STREAM = 3
    private const val IDX_ARTIST = 4
    private const val IDX_SONG = 5
    private const val IDX_FORCE_PLAY = 6
    private const val IDX_MINUTES = 7
    private const val IDX_IS_CUSTOM = 8
    private const val IDX_OPEN_FOREGROUND = 9
    private const val IDX_DESIRED_PLAYING = 10
    private const val IDX_DEADLINE = 11

    class Decoded(val command: PlaybackCommand, val attempts: Int)

    fun encode(command: PlaybackCommand, attempts: Int): String = listOf(
        VERSION,
        attempts.toString(),
        escape(command.action),
        escape(command.stream),
        escape(command.artist),
        escape(command.song),
        flag(command.forcePlay),
        command.minutes.toString(),
        flag(command.isCustom),
        flag(command.openForeground),
        command.desiredPlaying?.let(::flag).orEmpty(),
        command.deadlineElapsedMs?.toString().orEmpty(),
    ).joinToString(SEPARATOR.toString())

    fun decode(raw: String): Decoded? {
        val fields = raw.split(SEPARATOR)
        if (fields.size != FIELD_COUNT) return null
        if (fields[IDX_VERSION] != VERSION) return null

        val action = unescape(fields[IDX_ACTION])
        if (action.isEmpty()) return null

        val attempts = fields[IDX_ATTEMPTS].toIntOrNull() ?: return null
        val forcePlay = flag(fields[IDX_FORCE_PLAY]) ?: return null
        val minutes = fields[IDX_MINUTES].toIntOrNull() ?: return null
        val isCustom = flag(fields[IDX_IS_CUSTOM]) ?: return null
        val openForeground = flag(fields[IDX_OPEN_FOREGROUND]) ?: return null
        val desiredPlaying = fields[IDX_DESIRED_PLAYING].let {
            if (it.isEmpty()) null else flag(it) ?: return null
        }
        val deadlineElapsedMs = fields[IDX_DEADLINE].let {
            if (it.isEmpty()) null else it.toLongOrNull() ?: return null
        }

        return Decoded(
            attempts = attempts,
            command = PlaybackCommand(
                action = action,
                stream = unescape(fields[IDX_STREAM]).ifEmpty { null },
                artist = unescape(fields[IDX_ARTIST]).ifEmpty { null },
                song = unescape(fields[IDX_SONG]).ifEmpty { null },
                forcePlay = forcePlay,
                minutes = minutes,
                isCustom = isCustom,
                openForeground = openForeground,
                desiredPlaying = desiredPlaying,
                deadlineElapsedMs = deadlineElapsedMs,
            ),
        )
    }

    private fun flag(value: Boolean): String = if (value) "1" else "0"

    private fun flag(value: String): Boolean? = when (value) {
        "1" -> true
        "0" -> false
        else -> null
    }

    /**
     * Only the separator and the escape character itself need escaping for the
     * format to be unambiguous, plus the newlines that would make a record
     * unreadable to anything that prints the file.
     */
    private fun escape(value: String?): String = value
        ?.replace("%", "%25")
        ?.replace("\t", "%09")
        ?.replace("\n", "%0A")
        ?.replace("\r", "%0D")
        .orEmpty()

    /** The reverse of [escape]; the escape character is undone last, never first. */
    private fun unescape(value: String): String = value
        .replace("%0D", "\r")
        .replace("%0A", "\n")
        .replace("%09", "\t")
        .replace("%25", "%")
}
