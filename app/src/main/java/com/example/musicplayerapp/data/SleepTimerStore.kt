package com.example.musicplayerapp.data

import android.content.Context
import androidx.core.content.edit
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerState

/**
 * The armed sleep timer, durably.
 *
 * `ThemeStore`'s shape: one file, its own keys, `apply()`, and an absent record is
 * the answer rather than a state anybody has to initialise. It is deliberately not
 * in any identity store - a sleep timer belongs to the device and to nobody's
 * account, so `AccountDeletionCleanup` does not touch it and signing in or out
 * leaves it exactly where it was.
 *
 * ## One writer
 *
 * `MediaPlayerService` is the only thing that writes here. The UI reads through
 * [restore] for a cold value before the service has answered, and is otherwise
 * told what the state is. Two writers would race over `generation`, which is the
 * one field whose whole job is to be monotonic.
 *
 * ## What is stored, and what is not
 *
 * A monotonic deadline, the boot it belongs to, and enough metadata to draw the
 * sheet. **No wall-clock deadline.** The time of day the sheets show is derived
 * when it is drawn - see `SleepTimerState` - so a timezone change, a DST step or
 * an NTP correction changes «остановится в HH:mm» and does not change the moment
 * playback actually stops.
 *
 * ## The second file: what `Вернуть` would put back
 *
 * `Отключить таймер` keeps the timer it cancelled so `Вернуть` can put back the
 * deadline it had, and that snapshot has to survive a process death too - it is the
 * state of a durable command (`sleep_timer_undo`), and a durable command whose state
 * lived in RAM is a command that does nothing after the kill it was meant to survive.
 * It lives here, in [UNDO_FILE], rather than in the keys above for two reasons:
 *
 *  - **the armed record is one thing and the affordance is another.** The keys above
 *    are the timer that is going to fire; the snapshot is a one-gesture offer to put
 *    a cancelled one back. Mixing them would make `restore` answer two questions.
 *  - **backup granularity is per file.** The armed record keeps its current backup
 *    story; the snapshot is excluded from cloud backup and device transfer in both
 *    rule files, because a `Вернуть` on a phone that never cancelled anything is an
 *    offer nobody made there.
 *
 * The snapshot is stamped with the boot its deadline was measured on, the same way
 * the armed record is, so a snapshot that outlives a reboot is inert rather than a
 * deadline reinterpreted in a new `elapsedRealtime` epoch.
 */
object SleepTimerStore {

    private const val PREFS = "myata_sleep_timer"

    /** Kept public so the backup-rule test can assert the excluded path is this one. */
    const val UNDO_FILE = "myata_sleep_timer_undo"

    private const val KEY_DEADLINE = "deadline_elapsed_ms"
    private const val KEY_BOOT = "boot_id"
    private const val KEY_DURATION = "duration_minutes"
    private const val KEY_CUSTOM = "is_custom"
    private const val KEY_GENERATION = "generation"

    private const val UNDO_KEY_DEADLINE = "cancelled_deadline_elapsed_ms"
    private const val UNDO_KEY_BOOT = "cancelled_boot_id"
    private const val UNDO_KEY_DURATION = "cancelled_duration_minutes"
    private const val UNDO_KEY_CUSTOM = "cancelled_is_custom"
    private const val UNDO_KEY_CANCELLED_BY = "cancelled_by"
    private const val UNDO_KEY_CONSUMED_BY = "undo_consumed_by"

    /** What [restore] found. The service acts on all four; the UI treats every non-[Armed] as off. */
    sealed class Restored {
        /** Nothing on disk. */
        object None : Restored()

        /** A record from another boot, or one that cannot prove which boot it is from. */
        object ForeignBoot : Restored()

        /** This boot's record, but the deadline is already behind us. */
        data class Expired(val timer: SleepTimerState.Armed) : Restored()

        /** This boot's record, still to run. */
        data class Armed(val timer: SleepTimerState.Armed) : Restored()
    }

    fun write(context: Context, timer: SleepTimerState.Armed, bootId: Int?) {
        prefs(context).edit {
            putLong(KEY_DEADLINE, timer.deadlineElapsedMs)
            putInt(KEY_BOOT, BootIdentity.toStored(bootId))
            putInt(KEY_DURATION, timer.durationMinutes)
            putBoolean(KEY_CUSTOM, timer.isCustom)
            putLong(KEY_GENERATION, timer.generation)
        }
    }

    fun clear(context: Context) {
        prefs(context).edit {
            remove(KEY_DEADLINE)
            remove(KEY_BOOT)
            remove(KEY_DURATION)
            remove(KEY_CUSTOM)
            remove(KEY_GENERATION)
        }
    }

    /**
     * What is on disk, judged against this boot and this instant.
     *
     * Reads only. Discarding a foreign-boot or expired record is the caller's
     * move, because the caller is also the thing that has to reconcile playback
     * with it, and doing half of that here would leave the two out of step.
     */
    fun restore(context: Context, currentBootId: Int?, nowElapsedMs: Long): Restored {
        val p = prefs(context)
        if (!p.contains(KEY_DEADLINE)) return Restored.None

        val storedBoot = p.getInt(KEY_BOOT, BootIdentity.UNKNOWN)
        if (!BootIdentity.matches(storedBoot, currentBootId)) return Restored.ForeignBoot

        val timer = SleepTimerState.Armed(
            deadlineElapsedMs = p.getLong(KEY_DEADLINE, 0L),
            durationMinutes = p.getInt(KEY_DURATION, 0),
            isCustom = p.getBoolean(KEY_CUSTOM, false),
            generation = p.getLong(KEY_GENERATION, 0L),
        )
        return if (timer.hasExpired(nowElapsedMs)) Restored.Expired(timer) else Restored.Armed(timer)
    }

    /**
     * The read-only view the UI uses before the service has said anything.
     *
     * Everything that is not a live timer of this boot is [SleepTimerState.Off] -
     * including an expired record, which the service will clear the moment it
     * reconciles. Nothing here writes, so a UI read can never race the one writer.
     */
    fun peek(context: Context, nowElapsedMs: Long): SleepTimerState =
        when (val r = restore(context, BootIdentity.read(context), nowElapsedMs)) {
            is Restored.Armed -> r.timer
            else -> SleepTimerState.Off
        }

    /** The highest generation this install has issued, so a restart cannot reuse one. */
    fun lastGeneration(context: Context): Long = prefs(context).getLong(KEY_GENERATION, 0L)

    // ==================== the cancel snapshot (`Вернуть`) ====================

    /**
     * The timer as it was when it was cancelled, and the cancel command that made it:
     * what `Вернуть` puts back, and the identity that keeps a replayed cancel from
     * wiping it.
     */
    data class Cancelled(val timer: SleepTimerState.Armed, val cancelledBy: String)

    /**
     * The two replay rules of the timer's identity-bearing commands, as decisions.
     *
     * Both exist because delivery is at-least-once: a handler may run, the process may
     * die before its acknowledgement, and the same durable command arrives again. A
     * second run of the *same* cancel must not clear the snapshot the first run wrote
     * (that would destroy `Вернуть`), and a second run of an undo must not consume a
     * *later* cancel's snapshot. Pure, so the JVM holds both without a device.
     */
    object Replay {

        /** True when [cancelId] is the cancel that produced [existing] and has run before. */
        fun cancelAlreadyRecorded(existing: Cancelled?, cancelId: String): Boolean =
            existing != null && existing.cancelledBy == cancelId

        /** True when [undoId] is the undo that already consumed the snapshot. */
        fun undoAlreadyConsumed(consumedBy: String?, undoId: String): Boolean =
            consumedBy != null && consumedBy == undoId
    }

    /**
     * Records what `Вернуть` may put back, or clears it when there was nothing worth
     * keeping (no timer, or one whose deadline had already passed).
     *
     * A re-run of the same cancel command leaves the snapshot exactly as the first run
     * left it: the second run finds no armed timer - cancel cleared it - and writing
     * that "nothing" over the snapshot would be the replay destroying the affordance
     * it created. Returns whether the write became durable; on failure the last
     * confirmed Undo state remains the logical answer.
     */
    fun recordCancelled(
        context: Context,
        timer: SleepTimerState.Armed?,
        bootId: Int?,
        cancelledBy: String,
    ): Boolean = undo(context).recordCancelled(timer, bootId, BootIdentity.read(context), cancelledBy)

    /**
     * The snapshot `Вернуть` may put back, or null when there is none **for this
     * boot**. Reads only.
     *
     * A snapshot from another boot - or one that cannot prove which boot it came from -
     * is answered as nothing: its deadline belongs to an `elapsedRealtime` epoch that is
     * over, and the app's rule is that a timer never resumes across a reboot.
     */
    fun readCancelled(context: Context, currentBootId: Int?): Cancelled? =
        undo(context).readCancelled(currentBootId)

    /**
     * `Вернуть` in one step: take the snapshot (whatever it turns out to be worth) and
     * record which undo took it.
     *
     * Both halves in one editor, and the undo's identity with them, because an undo
     * that ran must not be able to run again: a replayed undo finding a *newer*
     * snapshot would otherwise put back a timer the listener cancelled afterwards.
     * The snapshot is consumed even when it turns out to be unusable, which is what
     * makes a replay of the same command a no-op rather than a second attempt.
     *
     * The answer says whether that write reached the disk - and [Consumed.NotConsumed]
     * is the one the caller must not read as "there was nothing to put back": a
     * consumption that did not commit leaves the snapshot where it was, so the command
     * has not been delivered and will be replayed.
     */
    fun consumeCancelled(context: Context, currentBootId: Int?, consumedBy: String): Consumed =
        undo(context).consumeCancelled(currentBootId, consumedBy)

    /** What [consumeCancelled] found and did. */
    sealed class Consumed {

        /** Nothing to put back: no snapshot, or none that belongs to this boot. */
        object Nothing : Consumed()

        /** The snapshot is taken and its removal is durable. */
        data class Timer(val timer: SleepTimerState.Armed) : Consumed()

        /**
         * The consumption did not commit: the snapshot is still on disk, so `Вернуть` has
         * not been delivered and the next start has it again.
         */
        object NotConsumed : Consumed()
    }

    /** The id of the undo command that consumed the last snapshot, or null if none has. */
    fun lastUndo(context: Context): String? = undo(context).lastUndo()

    /**
     * Drops the snapshot: the offer to put a cancelled timer back.
     *
     * The record of which undo consumed the last one deliberately stays. It is what makes
     * a replayed undo a no-op, and an arming between the undo and its replay would
     * otherwise put that replay back to consuming a snapshot it never saw.
     */
    fun clearCancelled(context: Context): Boolean = undo(context).clearCancelled()

    /** Test-only: return this install to the state a fresh one is in. */
    fun clearForTest(context: Context) {
        prefs(context).edit(commit = true) { clear() }
        undo(context).clearForTest()
    }

    /** The Undo file's one process-local transaction boundary. */
    internal class Undo(private val prefs: TransactionalUndoPrefs) {
        fun recordCancelled(
            timer: SleepTimerState.Armed?, bootId: Int?, currentBootId: Int?, cancelledBy: String,
        ): Boolean {
            if (!prefs.healthy) return false
            if (Replay.cancelAlreadyRecorded(readCancelled(currentBootId), cancelledBy)) return true

            val changes = removeCancelled().toMutableMap()
            if (timer != null) {
                changes[UNDO_KEY_DEADLINE] = timer.deadlineElapsedMs
                changes[UNDO_KEY_BOOT] = BootIdentity.toStored(bootId)
                changes[UNDO_KEY_DURATION] = timer.durationMinutes
                changes[UNDO_KEY_CUSTOM] = timer.isCustom
            }
            changes[UNDO_KEY_CANCELLED_BY] = cancelledBy
            return prefs.edit(changes)
        }

        fun readCancelled(currentBootId: Int?): Cancelled? {
            val state = prefs.read()
            val deadline = state[UNDO_KEY_DEADLINE] as? Long ?: return null
            if (!BootIdentity.matches(state[UNDO_KEY_BOOT] as? Int ?: BootIdentity.UNKNOWN, currentBootId)) return null
            return Cancelled(
                timer = SleepTimerState.Armed(
                    deadlineElapsedMs = deadline,
                    durationMinutes = state[UNDO_KEY_DURATION] as? Int ?: 0,
                    isCustom = state[UNDO_KEY_CUSTOM] as? Boolean ?: false,
                    generation = 0L,
                ),
                cancelledBy = state[UNDO_KEY_CANCELLED_BY] as? String ?: "",
            )
        }

        fun consumeCancelled(currentBootId: Int?, consumedBy: String): Consumed {
            if (!prefs.healthy) return Consumed.NotConsumed
            val snapshot = readCancelled(currentBootId) ?: return Consumed.Nothing
            if (!prefs.edit(removeCancelled() + (UNDO_KEY_CONSUMED_BY to consumedBy))) {
                return Consumed.NotConsumed
            }
            return Consumed.Timer(snapshot.timer)
        }

        fun lastUndo(): String? = prefs.read()[UNDO_KEY_CONSUMED_BY] as? String

        fun clearCancelled(): Boolean = prefs.edit(removeCancelled())

        fun clearForTest(): Boolean = prefs.edit(removeCancelled() + (UNDO_KEY_CONSUMED_BY to null))

        private fun removeCancelled(): Map<String, Any?> = mapOf(
            UNDO_KEY_DEADLINE to null,
            UNDO_KEY_BOOT to null,
            UNDO_KEY_DURATION to null,
            UNDO_KEY_CUSTOM to null,
            UNDO_KEY_CANCELLED_BY to null,
        )
    }

    /** Test-only: put a record on disk directly, including one from another boot. */
    fun writeRawForTest(
        context: Context,
        deadlineElapsedMs: Long,
        bootId: Int,
        durationMinutes: Int = 30,
        isCustom: Boolean = false,
        generation: Long = 1L,
    ) {
        prefs(context).edit(commit = true) {
            putLong(KEY_DEADLINE, deadlineElapsedMs)
            putInt(KEY_BOOT, bootId)
            putInt(KEY_DURATION, durationMinutes)
            putBoolean(KEY_CUSTOM, isCustom)
            putLong(KEY_GENERATION, generation)
        }
    }

    /** Test-only: is there a record at all, whatever it says. */
    fun hasRecordForTest(context: Context): Boolean = prefs(context).contains(KEY_DEADLINE)

    @Volatile private var processUndo: Undo? = null

    private fun undo(context: Context): Undo = processUndo ?: synchronized(this) {
        processUndo ?: Undo(
            TransactionalUndoPrefs(TransactionalUndoPrefs.SharedPreferencesRaw(context)),
        ).also { processUndo = it }
    }

    /** Test-only injection at the raw device boundary; null restores the real file. */
    internal fun setUndoRawForTest(raw: TransactionalUndoPrefs.Raw?) = synchronized(this) {
        processUndo = raw?.let { Undo(TransactionalUndoPrefs(it)) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

}
