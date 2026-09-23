package com.example.musicplayerapp.service

import android.os.SystemClock
import com.example.musicplayerapp.data.BootIdentity
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
 *
 * ## Which commands may be dropped, and which may not
 *
 * A handler can fail, and a command that fails every time must not block every later
 * gesture for the life of the install - so a bounded number of attempts ends in a
 * discard. That trade is only acceptable where losing the command cannot take
 * anything away from the listener, and those are exactly the commands that only
 * *restate* something the app derives anyway: [critical] is the list, and everything
 * that changes what the listener asked for is on it. A critical command is never
 * dropped by a retry count - it waits for the next legitimate start of the service.
 *
 * It does not wait forever in front of a newer one, though. A critical command is
 * exactly one that belongs to a [PlaybackDomain], and a newer command in the same
 * domain states what the listener wants *now*; the older one is then no longer anything
 * they are waiting for and the queue supersedes it. See [PlaybackDomain] for which
 * commands may supersede which, and `PlaybackCommandInbox` for how the decision is made
 * durable.
 *
 * ## The one field that is about *when* the command was made
 *
 * [deadlineBootId]: the boot an `elapsedRealtime` deadline was measured on. That clock
 * restarts at boot, so a deadline can look entirely plausible in a new boot's epoch -
 * see `BootIdentity`, whose semantics this reuses rather than reinventing.
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
    /**
     * The boot [deadlineElapsedMs] belongs to, as `BootIdentity` stores it
     * (`BootIdentity.UNKNOWN` when the platform would not say). Non-null exactly when
     * the command carries a deadline.
     */
    val deadlineBootId: Int? = null,
) {

    /**
     * Whether losing this command could take away something the listener asked for.
     *
     * The line is "does this command only restate state the app derives for itself, or
     * is it the state the listener chose?" - and only the second kind is critical,
     * because a retry counter is not a reason to discard a choice:
     *
     *  - **critical** - `play`, `startStop` ([desiredPlaying]), `switch` (a station),
     *    `stop`: which station and whether audio is wanted, which is what
     *    `PlaybackIntentStore` calls the listener's intent;
     *  - **critical** - the three sleep-timer commands that change the timer:
     *    `sleep_timer_set`, `sleep_timer_cancel` (which is also what creates the undo
     *    affordance) and `sleep_timer_undo`;
     *  - **not critical** - `switch_track`: a notification metadata refresh that the
     *    metadata poller re-derives from the same feed within a minute;
     *  - **not critical** - `get_status` and `sleep_timer_sync`: reads. Both only
     *    re-broadcast what is already there, and the surfaces that need them ask again
     *    whenever they are opened;
     *  - **not critical** - the two debug seams: they exist for tests, a release build
     *    refuses them before they do anything, and each only re-runs something the app
     *    reaches on its own path (a null-intent restart, a system broadcast).
     */
    val critical: Boolean get() = isCritical(action)

    companion object {

        /** The one action whose meaning used to be a question. See the class docs. */
        const val ACTION_START_STOP = "startStop"

        /** See [critical]: the commands whose loss can take away what a listener chose. */
        fun isCritical(action: String): Boolean = when (action) {
            "play",
            ACTION_START_STOP,
            "stop",
            "switch",
            SleepTimerContract.ACTION_SET,
            SleepTimerContract.ACTION_CANCEL,
            SleepTimerContract.ACTION_UNDO,
            -> true

            else -> false
        }

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
         *
         * [bootId] is the same clock's other half, read the same way - by
         * `ServiceUtils`, where the gesture is made - and is stored with the deadline it
         * belongs to. It is deliberately taken as a parameter rather than read here:
         * reading `Settings.Global` would need a `Context`, and this stays a pure
         * function of what the caller knew when the listener pressed something.
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
            bootId: Int? = null,
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
            deadlineBootId = if (action == SleepTimerContract.ACTION_SET) {
                BootIdentity.toStored(bootId)
            } else {
                null
            },
        )
    }
}

/**
 * The logical intent domain a command belongs to, and the only thing that may
 * supersede another command.
 *
 * ## Why supersession is per domain, and why it is needed at all
 *
 * The queue is FIFO and durable, and both of those are load-bearing: a record is a
 * listener's gesture, and the order they made the gestures in is part of what they
 * meant. FIFO on its own has one hole, and it is the one this enum closes. A command
 * at the head that can never be delivered - a Play whose handler throws every time, a
 * Play whose foreground promotion Android refuses - stands in front of every later
 * gesture for the life of the install. The listener presses Stop and the radio does not
 * stop, because an older Play is still stuck; pressing Stop again changes nothing,
 * because that command lands behind the same stuck record.
 *
 * Dropping critical commands after N failures is not the answer
 * ([PlaybackCommand.critical] says why), and neither is reordering the queue. What
 * makes the older command disposable is that a **newer command in the same logical
 * domain has replaced what it meant**: the listener's latest statement about what they
 * want playing beats their earlier one, so the earlier one is no longer anything they
 * are waiting for. A statement about another domain says nothing about it at all.
 *
 * ```
 *   newer stop          supersedes an older play / startStop / switch
 *   newer cancel        supersedes an older sleep_timer_set / undo
 *   newer play          supersedes an older stop
 *   nothing             supersedes switch_track / get_status / sleep_timer_sync / debug seams
 * ```
 *
 * The last line matters as much as the others: those commands only restate state the
 * app re-derives for itself, so a newer restatement is not a reason to throw an older
 * one away - and they are never a reason to throw a *choice* away either.
 */
internal enum class PlaybackDomain(val key: String) {

    /** What the listener wants playing: `play`, `startStop`, `stop`, `switch`. */
    PLAYBACK("playback"),

    /** What the listener wants the sleep timer to do: set, cancel, undo. */
    TIMER("timer"),
    ;

    companion object {

        /**
         * The domain [action] belongs to, or null when nothing about it is supersedable.
         *
         * That this list is exactly [PlaybackCommand.critical]'s is not a coincidence: a
         * command the listener chose is a command a newer choice can replace, and a
         * command that only restates derived state has no choice in it to replace. The
         * two are asserted equal in `PlaybackCommandSupersessionTest`, so neither can
         * drift away from the other without a test saying so.
         */
        fun of(action: String): PlaybackDomain? = when (action) {
            "play",
            PlaybackCommand.ACTION_START_STOP,
            "stop",
            "switch",
            -> PLAYBACK

            SleepTimerContract.ACTION_SET,
            SleepTimerContract.ACTION_CANCEL,
            SleepTimerContract.ACTION_UNDO,
            -> TIMER

            else -> null
        }
    }
}
