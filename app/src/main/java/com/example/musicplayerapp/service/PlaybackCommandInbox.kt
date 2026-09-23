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
 * ## The other way out: a newer intent in the same domain
 *
 * "Never dropped" is not "always first". A critical command waits for the next start
 * of the service, but if it and a *newer command in its domain* are both waiting, the
 * newer one is what the listener wants and the older one is stale by definition:
 *
 * ```
 *   queued:  play(gold)      stop            -- the listener has since said stop
 *   pass:    stale, removed  handled
 * ```
 *
 * Without that, one command whose handler can never succeed - or whose foreground
 * promotion Android keeps refusing - is in front of every later gesture for the life
 * of the install: the listener presses Stop and nothing stops. What makes the older
 * command disposable is not a retry count, it is that a newer command replaced what it
 * meant; see [PlaybackDomain] for which commands may supersede which, and how a
 * command about the timer can never supersede a command about playback.
 *
 * **The generation is the sequence.** A record's own sequence - the durable, monotonic
 * id it is stored and acknowledged under - *is* its generation in its domain, and the
 * domain's newest generation is one more preference key in this same file
 * (`generation_<domain>`). So supersession needs no new record format and no new field:
 * two sequences compare, and the bigger one is newer by construction, because
 * `next_sequence` only ever climbs and a record keeps the number it was written with.
 * The newest generation a domain has seen is written in the *same* commit as the record
 * that advanced it ([enqueue]), so there is no instant in which a command is on disk
 * without the generation that says it supersedes the older ones - and a commit that
 * failed leaves both the record and the generation exactly as they were, which is what
 * keeps an old command valid when a new one was not accepted.
 *
 * [drain] applies it, and only [drain]: before a head is offered to its `prepare` step
 * (so a stale command is never promoted to the foreground) and before any handler sees
 * it, a head belonging to a domain with a newer generation is removed durably as
 * superseded and the pass moves on to the next command. A command that cannot be
 * removed is not skipped - it stays, the pass ends, and the next start recognises it as
 * stale again. [withdraw] is the one other place this matters: a command whose start the
 * platform refused never happened, so it takes its own generation advance back with it
 * when it leaves, or it would have superseded an older command with a gesture that was
 * never delivered.
 *
 * ## A command that is already running
 *
 * One race is left open on purpose, and it is the one supersession cannot close: command A
 * is *already being handled* - it is off the head, and its record is still on disk, because
 * the acknowledgement comes after the handler - when the listener's newer command B in the
 * same domain is enqueued. B advances the generation, so A is stale as of that instant, and
 * A is halfway through doing what it said. Two things follow:
 *
 *  - A finishes and is acknowledged by its own id, so B's record - a different key, with a
 *    newer sequence - is untouched by that acknowledgement and is still there afterwards;
 *  - what makes B safe from there is the generation: A can never be *run* a second time,
 *    because a replay of A (an acknowledgement that did not reach the disk) or a failure of
 *    A's handler is resolved by the next pass finding A stale and removing it in favour of
 *    B.
 *
 * So A may briefly produce its side effect if B was submitted after A had already started,
 * and B still wins, and cannot be lost.
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
 * ## Why the check is not enough: writes are transactions
 *
 * Checking the answer is necessary and not sufficient, because of what a failed
 * `commit()` means on Android. An editor's changes land in the **process's own map**
 * first and the file is written afterwards, so a commit that returns false leaves the
 * file alone *and leaves the change visible in this process* - and the next commit
 * that succeeds writes the whole map, so a value that never reached the file can
 * become durable later through an unrelated write. "The commit said no" is not
 * "nothing happened". A queue that read the failed mutation back would let a command it
 * never accepted win: the unaccepted record and the generation it advanced would be in
 * the map, the older command would then read as stale, and any later successful write
 * of any kind would make the unaccepted command the durable one.
 *
 * So the records do not live in an editor. Every read and every write in this class
 * goes through [Slot], and the only implementation of it - [TransactionalSlot] - makes
 * each write a transaction: the new state, or the previous one, and never the attempt
 * in between. A write that cannot even be put back puts the store in its failure state
 * ([Slot.healthy] is false), where reads answer from the last state the process knows
 * the file to hold and every write is refused, with one loud `QUEUE_STORAGE_UNHEALTHY`
 * line to [PlaybackLog]. See [TransactionalSlot] for the mechanism, and for why failing
 * closed is the right trade there.
 *
 * [withdraw] is the other way out, and it is not an acknowledgement: it is for a
 * start the platform refused, where the command never reached the service at all.
 * A command that never reached the service has to leave nothing behind, or the
 * next start of any kind - minutes later, for a different reason - would act on a
 * gesture the listener had stopped expecting anything from. Its removal is a
 * transaction like every other, so one that did not commit reports that and nothing
 * else ([Withdraw.NOT_REMOVED]) rather than claiming either side of a race it cannot
 * see.
 *
 * ## Order, identity, and who may write
 *
 * Each record is one preference key, `command_<sequence>`, and the sequence is a
 * monotonic counter (`next_sequence`) that is never reused, even by a process that
 * dies immediately after an [enqueue]. Order is the sequence, so FIFO survives
 * both a process death and concurrent callers: every read-modify-write here runs
 * under one process-wide lock, so two threads cannot interleave an append.
 * Supersession is decided on the same number - a record's sequence is its
 * generation - and the domain's newest generation lives in this same file, so nothing
 * about the decision is in memory: a process that dies and comes back reads the same
 * two numbers off the disk and reaches the same answer.
 *
 * The lock is process-wide rather than per-instance because there is exactly one
 * writer of each record at a time in production - a screen appends, the service
 * acknowledges - and both go through the one instance [forContext] hands out.
 *
 * One *pass* at a time is a property of the thread rather than of that lock: [drain] is
 * called from exactly one place, `PlaybackCommandPass.run`, which `MediaPlayerService`
 * drives from `onStartCommand` on the main thread, so two start commands cannot interleave
 * their passes and the same head cannot be executed twice. That is why the lock is held
 * around each read-modify-write and never across a handler.
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
     * acknowledgement possible and the generation that makes supersession possible.
     *
     * [generation] is the record's sequence, and that is the whole of what a generation
     * is here: sequences are durable, monotonic and never reused, so the newest one
     * accepted in a [PlaybackDomain] is a durable revision of that domain and a record
     * is superseded exactly when a newer one has been accepted. [id] is the same number
     * as text, because a preference key is what an acknowledgement names.
     */
    class Entry(val generation: Long, val command: PlaybackCommand) {

        /** Unique for the life of the install. See [generation]. */
        val id: String get() = generation.toString()
    }

    /**
     * Where the records live, as the queue sees it: a **transaction boundary**, not an
     * editor.
     *
     * The contract is the one the queue's correctness needs, and it is stronger than
     * what the platform gives:
     *
     * ```
     *   edit(changes) true  -> the new state is durable AND visible: it happened
     *                 false -> the previous state is still visible and still
     *                          authoritative: nothing the caller asked for leaked,
     *                          and nothing of it can become durable later
     *   read()              -> never the remains of an edit that returned false
     * ```
     *
     * An interface so the queue's own rules - order, identity, acknowledgement,
     * retry, supersession - can be pinned on the JVM against a fake, with the real
     * `SharedPreferences` construction left to `PlaybackCommandHandoffTest` on a
     * device. [TransactionalSlot] is the only implementation, production or test: the
     * fakes replace the *device* under it ([RawSlot]), never the transaction itself, so
     * every test that writes through this interface is a test of the rollback.
     */
    interface Slot {

        /**
         * Whether this store can be written to at all.
         *
         * False once it has entered its failure state - a write that did not reach the
         * file *and* could not be put back - from which nothing returns. The queue asks
         * so it can tell "this command is gone" apart from "this store cannot answer",
         * which is the difference between a delivered gesture and a storage failure
         * (see [Withdraw.NOT_REMOVED]).
         */
        val healthy: Boolean

        /** Every key this inbox owns, in no particular order. */
        fun read(): Map<String, String>

        /**
         * Applies every change in one transaction. A `null` value removes the key.
         *
         * One edit is what makes an append atomic: the record and the advanced sequence
         * land together, or neither does - and the caller is told which, with the
         * previous state left visible and authoritative when the answer is false. See
         * [TransactionalSlot] for what that costs and how it is reached.
         */
        fun edit(changes: Map<String, String?>): Boolean
    }

    /**
     * The device under [TransactionalSlot]: app-private storage as Android's
     * `SharedPreferences` actually behaves.
     *
     * This is the seam a JVM test replaces, and the fake has to reproduce the property
     * the queue's correctness rests on rather than a convenient one. An [edit] that
     * returns false has **already** been applied to the map a later [read] would
     * return; only the file was left alone. A fake that refuses before touching its map
     * cannot see the bug this class exists to close - see the class docs.
     */
    interface RawSlot {

        /** Every key this inbox owns, in no particular order. */
        fun read(): Map<String, String>

        /**
         * Applies every change, and reports whether the commit reached the file. A
         * `null` value removes the key.
         *
         * The answer is about the *file*. By the time this returns, the change is in
         * the map [read] sees whatever the answer is; putting that back is
         * [TransactionalSlot]'s job, never an assumption made here.
         */
        fun edit(changes: Map<String, String?>): Boolean
    }

    /**
     * The queue's storage, as a transaction: the successful change or the previous
     * state, never the wreckage of an attempt in between.
     *
     * ## Why a check is not enough
     *
     * `SharedPreferences.Editor.commit()` applies its changes to the **process's own
     * map** first and writes the file afterwards, so its answer is about the file and
     * nothing else. A commit that returns false therefore leaves the file alone *and
     * leaves the change visible in this process* - and the next commit that succeeds
     * writes the whole map, so a value that never reached the file can become durable
     * later through a write that has nothing to do with it. "The commit said no" is not
     * "nothing happened". A queue that read the failed mutation back would let a
     * command it never accepted win: the record and the generation it advanced would be
     * there, an older accepted command would read as stale, and any later successful
     * write would make the unaccepted command the durable one.
     *
     * ## The transaction
     *
     * ```
     *   before = what the store shows now
     *   raw.edit(change)  true  -> new state is durable and visible: done
     *                     false -> put the previous state back, and VERIFY it is back,
     *                              by reading it, instead of trusting the answer
     *                              verified -> the change is gone, the old state stands
     *                              not      -> the failure state, below
     * ```
     *
     * The restore is asked for rather than assumed, twice over. The map is read first,
     * because there may be nothing to undo; and the undo is then *checked* by reading
     * the map back, because "a second editor call put it back" is exactly the kind of
     * assumption about raw platform semantics this class exists to avoid. What makes the
     * restore sufficient is that a commit that returned false never reached the file -
     * the one thing its answer does say - so putting the map back to [before] puts both
     * halves back: what readers see, and what the file holds.
     *
     * ## The failure state: a write that could not be put back
     *
     * If the change fails *and* the restore fails, this process holds something the
     * file does not and has no way to put it back. That is not a degraded queue, it is a
     * queue whose contents are unknown, and it is not allowed to guess:
     *
     *  - [read] answers from [frozen] - the state this process knows the file to hold,
     *    captured before the failed write - so no reader sees the value that failed to
     *    commit and no accepted command is made stale by one that should not exist;
     *  - every write is refused ([healthy] is false), so a later successful commit
     *    cannot make the failed value durable, and no enqueue, acknowledgement, discard
     *    or generation advance is ever reported as done;
     *  - one loud `QUEUE_STORAGE_UNHEALTHY` line goes to [PlaybackLog], because a queue
     *    that has stopped accepting commands must not be discovered from a Play that
     *    did nothing.
     *
     * The state belongs to one process and ends with it: nothing about it reached the
     * file, so the next process reads exactly the records this one did and starts
     * healthy. Failing closed costs the rest of this process's commands and is still the
     * right trade - the alternative is an unaccepted command winning.
     */
    class TransactionalSlot(private val raw: RawSlot) : Slot {

        /**
         * The last state this process knows the file to hold, once a failed write could
         * not be put back. Null is the normal state.
         *
         * Deliberately in memory and deliberately written nowhere: the point of the
         * failure state is that the *file* is the one thing that did not change.
         */
        private var frozen: Map<String, String>? = null

        override val healthy: Boolean get() = synchronized(this) { frozen == null }

        /** What the queue reads: the store's own map, or the frozen state. */
        override fun read(): Map<String, String> = synchronized(this) { now() }

        /**
         * One write, as a transaction. True only when the new state is durable and
         * visible; false leaves the previous state visible and authoritative.
         */
        override fun edit(changes: Map<String, String?>): Boolean = synchronized(this) {
            if (frozen != null) {
                PlaybackLog.problem(
                    "QUEUE_STORAGE_REFUSED",
                    "reason" to "storage_unhealthy",
                    "outcome" to "no_write_attempted",
                )
                return@synchronized false
            }

            val before = raw.read()
            if (raw.edit(changes)) return@synchronized true

            if (restore(before)) return@synchronized false

            frozen = before
            PlaybackLog.problem(
                "QUEUE_STORAGE_UNHEALTHY",
                "reason" to "write_not_durable_and_not_undoable",
                "outcome" to "reads_frozen_writes_refused",
            )
            return@synchronized false
        }

        /** The visible state: the store's map while healthy, the snapshot once it is not. */
        private fun now(): Map<String, String> = frozen ?: raw.read()

        /**
         * Puts the visible map back to [before] after a write that did not reach the
         * file, and answers whether the map now *is* [before] again.
         *
         * A write is only made when the map is not already [before], and the undo is
         * only accepted when the store committed it - the read-back guards against the
         * undo not having taken effect at all, and the commit's own answer guards
         * against it not being durable. Either way this is what the failure state is
         * entered for; see the class docs.
         */
        private fun restore(before: Map<String, String>): Boolean {
            val leaked = raw.read()
            if (leaked == before) return true

            val undo = (leaked.keys + before.keys).associateWith { before[it] }
            if (!raw.edit(undo)) return false

            return raw.read() == before
        }
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
     *
     * That answer is only as good as the transaction it comes from: a commit that
     * failed is not by itself a record that was not written, so the record and the
     * sequence it would have spent are taken back out and the map is verified before
     * this reports anything - see [TransactionalSlot]. A caller that is told `null` can rely on
     * the queue reading exactly what it read before, and on nothing the failed attempt
     * left behind ever being written.
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

        val changes = mutableMapOf<String, String?>(
            KEY_SEQUENCE to sequence.toString(),
            keyFor(sequence) to PlaybackCommandCodec.encode(command, attempts = 0),
        )

        // The generation advance is part of the same commit, and that is the whole
        // point: the record and the fact that it supersedes older commands in its
        // domain land together or not at all. A commit that fails therefore leaves the
        // older commands valid and this one unaccepted - there is no state in which a
        // command was not stored but an older one became stale.
        PlaybackDomain.of(command.action)?.let { changes[generationKey(it)] = sequence.toString() }

        val committed = slot.edit(changes)
        if (!committed) {
            PlaybackLog.problem(
                "COMMAND_NOT_STORED",
                "action" to command.action, "sequence" to sequence,
                "outcome" to "command_not_accepted",
            )
            return@synchronized null
        }
        Entry(sequence, command)
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
        decoded.entries.map { Entry(it.sequence, it.command) }
    }

    /**
     * The command has been handled: it leaves the queue, exactly once.
     *
     * Called after the handler, never before it. The window this leaves is the
     * reason every handler is replay-safe.
     *
     * The removal names one record, so it can only ever remove that one: a command
     * accepted *while* this one was being handled is a different key with a newer
     * sequence - see the class docs on the executing-command race - and is left exactly
     * where it is for the rest of this pass or the next one.
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
     *
     * What a pass never does is offer a **superseded** command to either step. The head
     * is read through [nextLiveHead], which durably removes every command that a newer
     * command in its own [PlaybackDomain] has replaced - so a stale Play is not promoted
     * to the foreground, its handler does not run, and the pass walks on to the command
     * the listener actually wants. That is the one way a critical command leaves this
     * queue without being handled, and it is not a discard: the command it gives way to
     * is the same choice, made later.
     */
    fun drain(prepare: (Entry) -> Boolean = { true }, handler: (Entry) -> Unit) {
        while (true) {
            val entry = nextLiveHead() ?: return
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
     * The oldest command that is still something the listener is waiting for, with every
     * superseded command in front of it removed.
     *
     * The decision is made here rather than in the service because it is a property of
     * the queue's own order and its own durable generations, and because it has to be
     * made *before* the head is handed to anything - a superseded Play must not be
     * promoted to the foreground and must not reach a handler, and what the caller gets
     * back has to be a command with both of those still ahead of it.
     *
     * Three answers, and each one is a distinct fact:
     *
     *  - a command: the oldest live one, and the caller's work;
     *  - `null` with an empty queue: there is nothing to do, which is the normal end of
     *    a pass;
     *  - `null` with a **superseded record that could not be removed**: the record is
     *    still on disk, so it cannot be skipped - the pass ends with it in place and the
     *    next start recognises it as stale again by the same comparison. Reporting the
     *    removal as done when the disk refused it would be the same lie [ack] refuses to
     *    tell.
     *
     * A command in no [PlaybackDomain] is never stale: nothing supersedes it, and it
     * supersedes nothing.
     */
    private fun nextLiveHead(): Entry? = synchronized(LOCK) { liveHeadUnderLock() }

    /** [nextLiveHead]'s body, which must run with [LOCK] held: it reads and writes the queue. */
    private fun liveHeadUnderLock(): Entry? {
        while (true) {
            val head = pending().firstOrNull() ?: return null

            val domain = PlaybackDomain.of(head.command.action) ?: return head
            val newest = generationOf(slot.read(), domain)
            if (head.generation >= newest) return head

            if (!slot.edit(mapOf(keyFor(head.id) to null))) {
                PlaybackLog.problem(
                    "COMMAND_SUPERSEDED_NOT_REMOVED",
                    "action" to head.command.action, "id" to head.id,
                    "generation" to head.generation, "newest" to newest,
                    "outcome" to "command_left_pending",
                )
                return null
            }

            PlaybackLog.event(
                "COMMAND_SUPERSEDED",
                "action" to head.command.action, "id" to head.id,
                "generation" to head.generation, "newest" to newest,
                "outcome" to "removed_superseded",
            )
        }
    }

    /**
     * The start never happened: take the command back out, unused.
     *
     * Not an acknowledgement - nothing ran. See the class docs for why a refused
     * start must leave nothing behind.
     *
     * "Nothing" includes the generation the command advanced when it was accepted. A
     * command whose start the platform refused was never delivered, so it must not have
     * superseded anything either: leaving the domain's newest generation pointing at a
     * record that has been taken back would let a gesture that never happened make an
     * older one stale - the listener's Play would then be dropped on behalf of a Stop
     * that never reached the service. [rollBackGeneration] does the part of that which
     * this record actually owns, in the same commit as the removal.
     *
     * @return [Withdraw.REMOVED_PENDING] when the command was still waiting and is now
     *   gone; [Withdraw.NOT_FOUND] when it was not there to take back, which is what a
     *   command another start has already consumed looks like; [Withdraw.NOT_REMOVED]
     *   when the removal could not be committed, which is neither of those facts and
     *   is reported as itself.
     */
    fun withdraw(id: String): Withdraw = synchronized(LOCK) {
        val key = keyFor(id)
        val stored = slot.read()

        // A store that cannot be trusted cannot answer the question this branch exists
        // for. "Not there" reads to the caller as "another start already consumed it",
        // which is a claim that the gesture was delivered - the one claim a queue in
        // the failure state must never make on evidence it does not have.
        if (!stored.containsKey(key)) {
            return@synchronized if (slot.healthy) Withdraw.NOT_FOUND else Withdraw.NOT_REMOVED
        }

        val changes = mutableMapOf<String, String?>(key to null)
        rollBackGeneration(stored, id, changes)

        if (slot.edit(changes)) {
            Withdraw.REMOVED_PENDING
        } else {
            PlaybackLog.problem(
                "COMMAND_NOT_WITHDRAWN", "id" to id,
                "outcome" to "command_still_pending",
            )
            Withdraw.NOT_REMOVED
        }
    }

    /**
     * Moves a domain's newest generation back to whatever is still waiting, when the
     * record being removed is the one that generation names.
     *
     * [withdraw]'s half of "a command that never reached the service has to leave nothing
     * behind". The generation is the sequence of the newest command accepted in the
     * domain, so:
     *
     *  - a record that is *not* that newest one owns nothing: a later command has already
     *    moved the domain on, and lowering the counter here would take that later
     *    command's own supersession away from it;
     *  - a record that *is* it hands the domain back to the newest command still waiting
     *    in the domain - or, when there is none, removes the key, which is the state a
     *    domain nothing has ever been accepted in is in.
     *
     * Either way the counter can only move back to a generation a waiting record actually
     * carries, so nothing that was superseded while this command was accepted becomes
     * live again by accident - and nothing is left pointing at a record that is gone.
     */
    private fun rollBackGeneration(
        stored: Map<String, String>,
        id: String,
        changes: MutableMap<String, String?>,
    ) {
        val sequence = id.toLongOrNull() ?: return
        val domain = stored[keyFor(id)]
            ?.let(PlaybackCommandCodec::decode)
            ?.command?.action
            ?.let(PlaybackDomain::of)
            ?: return

        if (generationOf(stored, domain) != sequence) return

        val newestRemaining = decode(stored).entries
            .filter { it.sequence != sequence && PlaybackDomain.of(it.command.action) == domain }
            .maxOfOrNull { it.sequence }

        changes[generationKey(domain)] = newestRemaining?.toString()
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
     *
     * The record comes back with the generation it was written under, so whether the
     * queue runs it is the same question it asks of any record: if the listener's
     * newest command in that domain is this one, it runs; if a newer one has been
     * accepted since, it is stale and a duplicate of a command that already happened is
     * the last thing anything should act on again.
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

        /**
         * The command is still in the inbox because taking it out did not commit - a
         * storage failure, and deliberately its own answer.
         *
         * It is not [REMOVED_PENDING] (the record is still there, and a later start will
         * run it) and it is not [NOT_FOUND] either, which is the answer that means
         * "another start already carried this out": claiming a delivery on a store that
         * could not be written is exactly the lie the rest of this class refuses to tell.
         */
        NOT_REMOVED,
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

    /**
     * The newest generation accepted in [domain], off the disk.
     *
     * Absent is zero, which is older than every sequence an [enqueue] can produce, so a
     * domain nothing has been accepted in supersedes nothing - and so does a counter this
     * code cannot read, which is the direction to fail in: an unreadable counter cannot
     * make a command stale, and a command is only ever removed here in favour of one that
     * is *newer*, never in favour of nothing.
     */
    private fun generationOf(stored: Map<String, String>, domain: PlaybackDomain): Long =
        stored[generationKey(domain)]?.toLongOrNull() ?: 0L

    private fun generationKey(domain: PlaybackDomain): String = "$KEY_DOMAIN_PREFIX${domain.key}"

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
         * One key per [PlaybackDomain], holding the sequence of the newest command
         * accepted in it. In this same file, deliberately: the generation a command
         * supersedes with has to commit with the command, and it has to survive the
         * process exactly as the command does.
         */
        private const val KEY_DOMAIN_PREFIX = "generation_"

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
            process ?: PlaybackCommandInbox(
                TransactionalSlot(PrefsSlot(context.applicationContext)),
            ).also { process = it }
        }

        /**
         * The real `SharedPreferences`, and only that: the raw [RawSlot] half, with the
         * memory-first behaviour it really has. Wrapping it in [TransactionalSlot] - which
         * [forContext] does, and nothing else does - is what turns its writes into
         * transactions the queue can rely on.
         *
         * `commit()`, deliberately: `apply()` returns before the file is written and is
         * lost if the process is killed in between, which is precisely the event this
         * inbox exists for - and, unlike the `edit {}` extension, calling it directly is
         * what makes its answer available. A command is written once, by a listener
         * pressing something, so the synchronous write costs nothing that matters - the
         * same trade `PlaybackIntentStore` makes.
         */
        private class PrefsSlot(private val context: Context) : RawSlot {

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
 *
 * The version did **not** move when supersession arrived, and that is not an oversight:
 * a command's generation is its sequence, which this format never stored because the
 * sequence is the preference key the record lives under, and the domain's newest
 * generation is its own key beside it. So there is no new field to write, no older
 * record to reinterpret, and a record written by an earlier build of this app stays
 * exactly as readable as it was - which is what a command outliving an app update
 * requires.
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
