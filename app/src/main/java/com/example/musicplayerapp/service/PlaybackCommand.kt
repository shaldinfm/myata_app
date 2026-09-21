package com.example.musicplayerapp.service

import android.os.SystemClock
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerDuration

/**
 * One app-private playback command, and the only path one can travel.
 *
 * ## Why this is not an `Intent` any more
 *
 * These commands - `play`, `startStop`, `switch`, `switch_track`, `get_status`,
 * `stop`, the four sleep-timer actions and the two debug seams - used to reach
 * [MediaPlayerService] as `ACTION` / `STREAM` / `ARTIST` / `SONG` / `force_play`
 * extras on a start intent.
 *
 * The service is a `MediaSessionService` and it is `android:exported="true"`, so
 * *any* app on the device could send exactly that intent, with exactly those
 * extras, and have this app start a station, stop it, rewrite what the
 * notification says is playing, or arm a timer. That is the finding this file
 * closes, and it could not be closed by guarding the extras: `onStartCommand` is
 * handed no caller identity, so an exported component cannot authenticate a start
 * command at all. A start intent that carries instructions is a start intent any
 * app may send.
 *
 * So the instructions stopped being an intent. The start intent is a wake-up and a
 * lifecycle signal and nothing else: it carries no instruction, so no external app
 * can make one mean anything. The exported endpoint stays exported - it is the
 * Media3 session the platform and other media controllers discover, and it is
 * unchanged - but what it exposes is a session, not a remote control for the app's
 * own state.
 *
 * ## Why this is not process memory either
 *
 * The commands were process memory for one slice of this work, and that was a P1:
 *
 * ```
 *   caller:  enqueue in RAM, then request the service start
 *   system:  accepts the start, then kills the process before the queue is drained
 *   restart: the start request survives (START_STICKY), the RAM queue does not
 * ```
 *
 * A gesture the listener made - Play, Stop, a station switch, a sleep timer, and
 * the foreground obligation of a `startForegroundService` call - could be lost in
 * that window. Process memory is not a place correctness can live.
 *
 * So the command is written to the app's own storage *before* the start is
 * requested, and the service reads it from there. The boundary is no longer the
 * process, and it is not the start intent: it is **who can write this app's
 * private storage**, which is this app and nothing else on the device. See
 * [PlaybackCommandInbox] for what the record is and how it is acknowledged.
 *
 * ## What is deliberately not used
 *
 * No caller package or component check, no unguessable action name, no shared
 * secret: none of those is a boundary - the first two are trivially spoofed and
 * the third is a constant in a shipping APK - and each would have looked like
 * one. The boundary is app-private storage.
 *
 * ## The two requests that are resolved before they are stored
 *
 * A command that is replayed - because a process death landed between its handler
 * and its acknowledgement - has to mean the same thing the second time. Two of
 * these commands cannot say what they mean on their own:
 *
 *  - `startStop` used to be a **toggle**, and a replayed toggle does the opposite
 *    of what the listener asked for: Play arrives as Pause, Stop as Play. Every
 *    call site in this app means "start playback" by it, so that is what [of]
 *    stores - a resolved desire, not a question for whoever handles it.
 *  - a sleep timer was a **duration**, and a replayed duration arms a *new*
 *    deadline however long the process was away. The listener chose "stop in 30
 *    minutes" at an instant, so that instant is what [of] stores:
 *    [deadlineElapsedMs].
 *
 * Both are the same move: persist the resolved desired state rather than the
 * request that computes it. Nothing about the gesture changes; what changes is
 * that acting on the command twice cannot produce two different states.
 *
 * ## What still has to ride the start, and now rides the record
 *
 * Whether the start was a `startForegroundService` call, because Android gives the
 * service five seconds to call `startForeground` after one and the caller is the
 * only one who knows. It travels as [openForeground], stored with the command, so a
 * service recreated after a process death still knows it owes that promotion.
 */
internal class PlaybackCommand(
    val action: String,
    val stream: String? = null,
    val artist: String? = null,
    val song: String? = null,
    val forcePlay: Boolean = false,
    val minutes: Int = 0,
    val isCustom: Boolean = false,
    /** True when the caller used `startForegroundService`: see the class docs. */
    val openForeground: Boolean = false,
    /** Resolved `startStop`: true = play. Null for every other action. */
    val desiredPlaying: Boolean? = null,
    /** Resolved `sleep_timer_set`: the absolute monotonic deadline. Null otherwise. */
    val deadlineElapsedMs: Long? = null,
) {

    companion object {

        /** The one action whose meaning used to be a question. See the class docs. */
        const val ACTION_START_STOP = "startStop"

        /**
         * A command a caller is asking for, as it will be stored.
         *
         * The one place a request becomes a record, and therefore the one place the
         * two resolved fields above can be got right or wrong. Both are pure
         * functions of the request and of [nowElapsedMs], so both are pinned on the
         * JVM in `PlaybackCommandChannelTest`.
         *
         * [nowElapsedMs] is a parameter rather than a call inside, so the resolution
         * can be read without a device. Production leaves it at the one clock this
         * app measures deadlines on - `elapsedRealtime`, which survives a clock
         * change and does not survive a reboot - and the sleep timer's own store
         * already speaks it (see `SleepTimerStore`).
         */
        internal fun of(
            action: String,
            stream: String? = null,
            artist: String? = null,
            song: String? = null,
            forcePlay: Boolean = false,
            minutes: Int = 0,
            isCustom: Boolean = false,
            openForeground: Boolean = false,
            nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        ): PlaybackCommand = PlaybackCommand(
            action = action,
            stream = stream,
            artist = artist,
            song = song,
            forcePlay = forcePlay,
            minutes = minutes,
            isCustom = isCustom,
            openForeground = openForeground,
            desiredPlaying = if (action == ACTION_START_STOP) true else null,
            deadlineElapsedMs = if (action == SleepTimerContract.ACTION_SET) {
                nowElapsedMs + SleepTimerDuration.toMs(minutes)
            } else {
                null
            },
        )
    }
}
