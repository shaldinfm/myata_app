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
 */
object SleepTimerStore {

    private const val PREFS = "myata_sleep_timer"
    private const val KEY_DEADLINE = "deadline_elapsed_ms"
    private const val KEY_BOOT = "boot_id"
    private const val KEY_DURATION = "duration_minutes"
    private const val KEY_CUSTOM = "is_custom"
    private const val KEY_GENERATION = "generation"

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

    /** Test-only: return this install to the state a fresh one is in. */
    fun clearForTest(context: Context) {
        prefs(context).edit(commit = true) { clear() }
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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
