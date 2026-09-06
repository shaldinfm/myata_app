package com.example.musicplayerapp.service

import android.content.Intent
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerState

/**
 * The wire between the one owner of the sleep timer and everything that draws it.
 *
 * Commands go to [com.example.musicplayerapp.service.MediaPlayerService] as
 * `ACTION` extras on a service intent - the same idiom every other UI-to-service
 * command in this app already uses - and state comes back as one
 * `LocalBroadcastManager` broadcast, which is the idiom `play` / `pause` /
 * `buffering` / `metadata_update` already use. Neither direction is new
 * machinery.
 *
 * The commands add no capability the service did not already expose: it is an
 * exported `MediaSessionService` whose `stop` action has always been reachable, and
 * the most a timer command can do is stop playback. Arming is additionally refused
 * outright on TV, in the service, so the exported surface cannot give a television
 * a timer no TV screen can show or cancel.
 */
object SleepTimerContract {

    /** Set a timer. `MINUTES` (Int) and `IS_CUSTOM` (Boolean). */
    const val ACTION_SET = "sleep_timer_set"

    /** `Отключить таймер`. */
    const val ACTION_CANCEL = "sleep_timer_cancel"

    /** `Вернуть` - restore the cancelled timer's original deadline. */
    const val ACTION_UNDO = "sleep_timer_undo"

    /** Reconcile and re-broadcast. What a screen asks for when it opens. */
    const val ACTION_SYNC = "sleep_timer_sync"

    const val EXTRA_MINUTES = "MINUTES"
    const val EXTRA_IS_CUSTOM = "IS_CUSTOM"

    /** The one state broadcast. Every surface reads this and nothing else. */
    const val BROADCAST_STATE = "sleep_timer_state"

    const val STATE_ARMED = "armed"
    const val STATE_DEADLINE_ELAPSED = "deadline_elapsed_ms"
    const val STATE_DURATION_MINUTES = "duration_minutes"
    const val STATE_IS_CUSTOM = "is_custom"
    const val STATE_GENERATION = "generation"
    const val STATE_CAN_UNDO = "can_undo"

    /**
     * True on the single broadcast that carries an expiry which actually stopped
     * playback. A one-shot, so `Таймер сна завершён` is shown once and never
     * re-shown by a later state read.
     */
    const val STATE_COMPLETED = "completed"

    fun stateOf(intent: Intent): SleepTimerState =
        if (!intent.getBooleanExtra(STATE_ARMED, false)) {
            SleepTimerState.Off
        } else {
            SleepTimerState.Armed(
                deadlineElapsedMs = intent.getLongExtra(STATE_DEADLINE_ELAPSED, 0L),
                durationMinutes = intent.getIntExtra(STATE_DURATION_MINUTES, 0),
                isCustom = intent.getBooleanExtra(STATE_IS_CUSTOM, false),
                generation = intent.getLongExtra(STATE_GENERATION, 0L),
            )
        }
}
