package com.example.musicplayerapp.service

import android.content.Context
import com.example.musicplayerapp.data.BootIdentity

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
 *    ([noteFailure] decides what happens from there: a command whose loss cannot
 *    cost the listener anything is dropped once it has exhausted its attempts, and
 *    one the listener asked for is never dropped at all - see
 *    [PlaybackCommand.critical]).
 *
 * ## Every write is checked
 *
 * A `SharedPreferences` commit can fail - a disk that is full, a file that cannot be
 * opened - and a queue whose "success" is a write that never reached the disk is the
 * same class of bug as one that lived in memory: the caller would start the service
 * for a command that is not there. So every write this class makes is checked and
 * reported: an enqueue that did not commit is **not an accepted command**
 * ([enqueue]), a removal that did not commit is **not an acknowledgement** ([ack]),
 * and a failure that could not be written down does not change what happens to the
 * command ([noteFailure]).
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
 * One *pass* at a time is a property of the thread rather than of that lock: [drain] is
 * called from exactly one place, `MediaPlayerService.onStartCommand`, which runs on the
 * main thread, so two start commands cannot interleave their passes and the same head
 * cannot be executed twice. That is why the lock is held around each read-modify-write
 * and never across a handler.
 *
 * ## Not backed up
 *
 * `myata_playback_commands.xml` is excluded from cloud backup and device transfer
 * (`lastfm_backup_rules.xml`, `lastfm_data_extraction_rules.xml`), for the same
 * reason `PlaybackIntentStore` is: a pending command is one device's gesture at one
 * device's speaker. Restored onto a new phone it would be an instruction nobody
 * gave there.
 *
 * ## Boot identity, where it is load-bearing
 *
 * Most commands here mean the same thing whenever they are read: a Play is a Play, a
 * Stop is a Stop, a switch names its station. One does not - `sleep_timer_set`
 * carries an `elapsedRealtime` deadline, and that clock restarts at boot, so the same
 * number can look like a perfectly ordinary deadline in a new boot's epoch. That
 * command therefore records the boot it was measured on
 * ([PlaybackCommand.deadlineBootId], using the same `BootIdentity` semantics
 * `SleepTimerStore` does) and the service refuses a deadline that cannot prove it
 * belongs to this boot.
 *
 * Everything else is deliberately not stamped. Nothing starts this service at boot
 * (there is no `BOOT_COMPLETED` receiver anywhere), so a record that outlives a
 * reboot is never acted on by itself: it is read when something has already started
 * the service, and a stale record is consumed before whatever that start was for.
 * Stamping every command would mean dropping all of them on a device whose boot
 * counter cannot be read, which is the opposite of what this inbox is for.
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
         * Applies every change in one commit, and reports whether it reached the
         * disk. A `null` value removes the key. One edit is what makes an append
         * atomic: the record and the advanced sequence land together, or neither
         * does - and the caller is told which.
         */
        fun edit(changes: Map<String, String?>): Boolean
    }

    /**
     * Records [command] durably and returns it with its identity.
     *
     * Synchronous by contract, and that is the point: the caller requests the
     * service start only after this returns, so there is no instant in which the
     * start exists and the command does not.
     *
     * `null` means the record did **not** commit: the command was not accepted, and
     * the caller must not request a start for it. The sequence is not advanced
     * either, so the id that was not used is not left as a hole.
     */
    fun enqueue(command: PlaybackCommand): Entry? = synchronized(LOCK) {
        val stored = slot.read()
        val decoded = decode(stored)
        // The stored counter, and - in case a truncated file left a record without
        // it - the highest sequence actually present. Either way the new id is one
        // no record has ever used.
        val sequence = maxOf(
            stored[KEY_SEQUENCE]?.toLongOrNull() ?: 0L,
            decoded.entries.maxOfOrNull { it.sequence } ?: 0L,
        ) + 1L

        val committed = slot.edit(
            mapOf(
                KEY_SEQUENCE to sequence.toString(),
                keyFor(sequence) to PlaybackCommandCodec.encode(command, attempts = 0),
            ),
        )
        if (!committed) {
            PlaybackLog.problem(
                "COMMAND_NOT_STORED",
                "action" to command.action, "sequence" to sequence,
                "outcome" to "command_not_accepted",
            )
            return@synchronized null
        }
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
            if (!slot.edit(decoded.unreadable.associateWith { null })) {
                // Those records are not run either way - what they said is unknowable
                // - but one that could not be removed is read again on the next start,
                // which is worth its own line.
                PlaybackLog.problem("COMMAND_UNREADABLE_NOT_REMOVED", "records" to decoded.unreadable.size)
            }
        }
        decoded.entries.map { Entry(it.sequence.toString(), it.command) }
    }

    /**
     * The command has been handled: it leaves the queue, exactly once.
     *
     * Called after the handler, never before it. The window this leaves is the
     * reason every handler is replay-safe.
     *
     * @return true only when the removal is durable. `false` means the record is
     *   still on disk: a command whose removal did not commit has **not** been
     *   acknowledged, and the next start of the service replays it - which is the
     *   safe direction, because the alternative is a handler's side effect with no
     *   record left to say it happened.
     */
    fun ack(id: String): Boolean = synchronized(LOCK) { slot.edit(mapOf(keyFor(id) to null)) }

    /**
     * Runs everything waiting, one command at a time, oldest first.
     *
     * The whole handover, start to finish: take the head, offer it to [prepare], run
     * [handler] on it, and acknowledge it only once that has returned. The queue is
     * re-read between commands because the acknowledgement changes which command is
     * the head, so a command appended while this runs is picked up by *this* pass
     * rather than left for the next start.
     *
     *  - [prepare] is the one thing that has to happen *before* a command is handled
     *    and cannot be done by its handler: the caller's obligation to the platform.
     *    It is called with the entry as it is on disk at that instant, so a command
     *    appended while this pass runs is offered to it too - which is what keeps that
     *    obligation attached to the command that carries it rather than to a snapshot
     *    of the queue taken when the pass began. Returning false declines the command:
     *    no handler, no acknowledgement, and the pass ends with the head and
     *    everything behind it still pending.
     *  - a handler that returns leaves its command acknowledged exactly once, so a
     *    later start finds nothing to do. An acknowledgement that cannot be committed
     *    ends the pass instead, with the command left exactly where it was: not
     *    acknowledged, and therefore replayable - see [ack].
     *  - a handler that throws leaves its command - and everything behind it -
     *    pending and ends this pass, so the next start retries it rather than
     *    skipping the listener's gesture; [noteFailure] decides whether the record is
     *    kept or dropped.
     */
    fun drain(prepare: (Entry) -> Boolean = { true }, handler: (Entry) -> Unit) {
        while (true) {
            val entry = pending().firstOrNull() ?: return
            if (!prepare(entry)) {
                PlaybackLog.event(
                    "COMMAND_NOT_PREPARED",
                    "action" to entry.command.action, "id" to entry.id,
                    "outcome" to "left_pending",
                )
                return
            }
            val handled = try {
                handler(entry)
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
                handled -> if (!ack(entry.id)) {
                    PlaybackLog.problem(
                        "COMMAND_ACK_NOT_STORED",
                        "action" to entry.command.action, "id" to entry.id,
                        "outcome" to "command_left_replayable",
                    )
                    return
                }

                else -> when (noteFailure(entry.id)) {
                    Failure.Dropped -> PlaybackLog.problem(
                        "COMMAND_DISCARDED",
                        "action" to entry.command.action, "id" to entry.id,
                        "attempts" to MAX_HANDLER_ATTEMPTS,
                    )

                    Failure.Retried -> return

                    Failure.NotPersisted -> {
                        PlaybackLog.problem(
                            "COMMAND_FAILURE_NOT_STORED",
                            "action" to entry.command.action, "id" to entry.id,
                            "outcome" to "command_still_pending",
                        )
                        return
                    }
                }
            }
        }
    }

    /**
     * The start never happened: take the command back out, unused.
     *
     * Not an acknowledgement - nothing ran. See the class docs for why a refused
     * start must leave nothing behind.
     *
     * @return [Withdraw.REMOVED_PENDING] when the command was still waiting and is now
     *   gone; [Withdraw.NOT_FOUND] when it was not there to take back, which is what a
     *   command another start has already consumed looks like. A removal that cannot
     *   be committed is reported the same way and logged: the record is still on disk
     *   and a later start will run it, so the one answer that must not be given is a
     *   delivery failure that did not happen.
     */
    fun withdraw(id: String): Withdraw = synchronized(LOCK) {
        val key = keyFor(id)
        if (!slot.read().containsKey(key)) return@synchronized Withdraw.NOT_FOUND

        if (slot.edit(mapOf(key to null))) {
            Withdraw.REMOVED_PENDING
        } else {
            PlaybackLog.problem(
                "COMMAND_NOT_WITHDRAWN", "id" to id,
                "outcome" to "command_still_pending",
            )
            Withdraw.NOT_FOUND
        }
    }

    /**
     * A handler failed. Records the failure and says what happens to the command.
     *
     * A failure is not a delivery, and what this decides is only ever whether the
     * record can be *dropped*:
     *
     *  - a critical command is never dropped, however often it fails. No retry count
     *    may throw away a state the listener asked for
     *    ([PlaybackCommand.critical]): the command stays pending, the pass that failed
     *    has already ended, and the next legitimate start tries again;
     *  - anything else is dropped after [MAX_HANDLER_ATTEMPTS], so one command that
     *    can never work does not sit in front of every later Play press for the life
     *    of the install.
     *
     * Either way the record only moves if the write that moves it committed: a failure
     * that could not be written down is [Failure.NotPersisted] and leaves the record
     * exactly as it was.
     */
    fun noteFailure(id: String): Failure = synchronized(LOCK) {
        val key = keyFor(id)
        val decoded = slot.read()[key]?.let(PlaybackCommandCodec::decode)

        if (decoded == null) {
            // Gone, or unreadable and therefore unretryable. Either way there is
            // nothing left to keep.
            return@synchronized if (slot.edit(mapOf(key to null))) Failure.Dropped else Failure.NotPersisted
        }

        val attempts = decoded.attempts + 1
        if (attempts >= MAX_HANDLER_ATTEMPTS && !decoded.command.critical) {
            return@synchronized if (slot.edit(mapOf(key to null))) Failure.Dropped else Failure.NotPersisted
        }

        // A critical command's count is a record of the failures, never an input to a
        // decision about dropping it - so it saturates instead of climbing forever.
        val recorded = if (decoded.command.critical) minOf(attempts, MAX_HANDLER_ATTEMPTS) else attempts
        val written = slot.edit(mapOf(key to PlaybackCommandCodec.encode(decoded.command, recorded)))
        return@synchronized if (written) Failure.Retried else Failure.NotPersisted
    }

    /** Test-only: leave this install's inbox the way a fresh one finds it. */
    fun clearForTest() = synchronized(LOCK) {
        slot.edit(slot.read().keys.associateWith { null })
    }

    /**
     * Test-only: put [entry] back under its own id, which is the state a lost
     * acknowledgement leaves behind - the handler ran, the removal never reached the
     * disk, and the same record is read again by the next start.
     */
    fun plantForTest(entry: Entry) = synchronized(LOCK) {
        slot.edit(mapOf(keyFor(entry.id) to PlaybackCommandCodec.encode(entry.command, attempts = 0)))
    }

    /** What [withdraw] found. */
    enum class Withdraw {
        /** The command was still waiting, and has been taken back out unused. */
        REMOVED_PENDING,

        /**
         * The command is not in the inbox: it has been consumed (its handler ran, or is
         * running now) or was withdrawn earlier. Never reported as a failed delivery.
         */
        NOT_FOUND,
    }

    /** What [noteFailure] did with the record. */
    sealed class Failure {

        /** The failure is recorded and the command is still waiting to be retried. */
        object Retried : Failure()

        /**
         * The record reached the end of its attempts and is gone. A
         * [critical][PlaybackCommand.critical] command never gets here.
         */
        object Dropped : Failure()

        /**
         * Nothing was written - the record is exactly as it was, and still pending. A
         * failure the disk would not take must not change what happens to the command.
         */
        object NotPersisted : Failure()
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
         * How many times a command whose loss cannot cost the listener anything may
         * fail before it is dropped. Three, so a transient failure - the player being
         * torn down mid-command, say - is retried by the next starts, and a command
         * that can never work does not sit in front of every later Play press.
         *
         * A [critical][PlaybackCommand.critical] command never reaches this count as a
         * decision: it is kept however often it fails, because a retry counter is not
         * a reason to throw away a state the listener asked for.
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
         * `commit()`, deliberately: `apply()` returns before the file is written and is
         * lost if the process is killed in between, which is precisely the event this
         * inbox exists for - and, unlike the `edit {}` extension, calling it directly is
         * what makes its answer available. A command is written once, by a listener
         * pressing something, so the synchronous write costs nothing that matters - the
         * same trade `PlaybackIntentStore` makes.
         */
        private class PrefsSlot(private val context: Context) : Slot {

            override fun read(): Map<String, String> =
                prefs().all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

            override fun edit(changes: Map<String, String?>): Boolean {
                val editor = prefs().edit()
                for ((key, value) in changes) {
                    if (value == null) editor.remove(key) else editor.putString(key, value)
                }
                return editor.commit()
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
 *
 * The version is `2`, and it moved when [PlaybackCommand.deadlineBootId] was added - the
 * first time this format gained a field, which is what the marker was for. A version-1
 * record is therefore not run and not guessed at: it is dropped as unreadable by
 * [PlaybackCommandInbox.pending], and a version-1 `sleep_timer_set` could not have proved
 * which boot its deadline belonged to anyway.
 */
private object PlaybackCommandCodec {

    private const val VERSION = "2"
    private const val SEPARATOR = '\t'
    private const val FIELD_COUNT = 13

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
    private const val IDX_DEADLINE_BOOT = 12

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
        command.deadlineBootId?.let(BootIdentity::toStored)?.toString().orEmpty(),
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
        val deadlineBootId = fields[IDX_DEADLINE_BOOT].let {
            if (it.isEmpty()) null else it.toIntOrNull() ?: return null
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
                deadlineBootId = deadlineBootId,
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
