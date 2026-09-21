package com.example.musicplayerapp.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.musicplayerapp.data.BootIdentity
import com.example.musicplayerapp.service.MediaPlayerService
import com.example.musicplayerapp.service.PlaybackCommand
import com.example.musicplayerapp.service.PlaybackCommandInbox
import com.example.musicplayerapp.service.PlaybackLog

object ServiceUtils {

    /**
     * Actions that are guaranteed to call startForeground in MediaPlayerService.
     * Kept here rather than in the service because the service no longer learns the
     * action from the start intent - it is the caller that has to choose the start.
     */
    private fun isForegroundAction(action: String): Boolean =
        action == "startStop" || action == "play" || action == "switch"

    /**
     * Starts the MediaPlayerService, handling Android 12+ background start restrictions.
     *
     * Returns whether the start was accepted. Failures used to be swallowed with
     * only a log line, so a blocked start looked to the user exactly like a Play
     * press that did nothing (issue #14). Callers can now see it.
     *
     * No longer carries the command in the intent: see [PlaybackCommand] for why an
     * exported service must not be handed instructions, and [deliver] for how the
     * command and the start are kept together.
     */
    fun safeStartService(context: Context, action: String, stream: String? = null, artist: String? = null, song: String? = null, forcePlay: Boolean = false): Boolean {
        val foregroundStart = isForegroundAction(action)

        PlaybackLog.event(
            "SERVICE_START_REQUEST",
            "action" to action,
            "stream" to (stream ?: "none"),
            "forcePlay" to forcePlay,
            "foreground" to foregroundStart
        )

        return deliver(
            context,
            PlaybackCommand.of(
                action = action,
                stream = stream,
                artist = artist,
                song = song,
                forcePlay = forcePlay,
                openForeground = foregroundStart,
                bootId = BootIdentity.read(context),
            ),
        )
    }

    /**
     * Sends one app-private command from a screen the listener is looking at.
     *
     * A plain `startService`, which is how the TV screens have always started
     * playback. Playback actions elsewhere go through [safeStartService], which
     * knows which of them must be a foreground start; this one exists for the
     * callers that already know the answer for themselves.
     */
    internal fun sendUiCommand(
        context: Context,
        action: String,
        stream: String? = null,
        artist: String? = null,
        song: String? = null,
        forcePlay: Boolean = false,
    ): Boolean = deliver(
        context,
        PlaybackCommand.of(
            action = action,
            stream = stream,
            artist = artist,
            song = song,
            forcePlay = forcePlay,
            bootId = BootIdentity.read(context),
        ),
    )

    /**
     * Sends one sleep-timer command to the service.
     *
     * A plain `startService`, deliberately: none of these four actions starts
     * playback, so none of them promises a `startForeground` call, and
     * `startForegroundService` would leave the service on the hook for a
     * notification it has no reason to post. `startService` also *starts* the
     * service when it is not running, which is what lets a timer be armed with
     * nothing playing (owner decision D6) - the caller is a screen the listener is
     * looking at, so the background-start restriction does not apply.
     *
     * If that started service is later reclaimed while nothing is playing, the
     * durable record is what carries the deadline: the next time the service is
     * created it reconciles and re-adopts it.
     *
     * `ACTION_SET` arrives at the service as an **absolute deadline**, resolved
     * here, at the instant the listener chose. A duration would be re-armed from
     * whenever the command is finally handled, so a command that survived a process
     * death would stop the radio later than the listener asked. See
     * [PlaybackCommand].
     */
    fun sendSleepTimerCommand(
        context: Context,
        action: String,
        minutes: Int = 0,
        isCustom: Boolean = false,
    ): Boolean = deliver(
        context,
        PlaybackCommand.of(
            action = action,
            minutes = minutes,
            isCustom = isCustom,
            bootId = BootIdentity.read(context),
        ),
    )

    /**
     * The one place a command meets a start.
     *
     * ## The order is the contract
     *
     * The command is **committed to app-private storage first** ([enqueue] is
     * synchronous), and the start is requested second. The reverse - or a queue that
     * only exists in memory - loses the gesture whenever the process dies between
     * the two, while the start request itself survives. See [PlaybackCommandInbox].
     *
     * The other half of the same order: the service already running reads its inbox
     * in its own `onStartCommand`, so a start delivered between the two would
     * otherwise be answered by nothing and the command would sit there until the
     * next start of any kind - which is exactly the kind of "Play did nothing, and
     * then a minute later the radio came on" behaviour issue #14 was about. It does
     * not sit there for long now, but the order is still what keeps the two ends
     * from disagreeing.
     *
     * ## The intent carries nothing
     *
     * Not an action, not an extra, not the station: [Intent] here is a wake-up for
     * a component and a lifecycle signal, and the only input it could carry -
     * whether this was a `startForegroundService` call - travels with the command
     * instead, because a recreated service has to be able to answer it. Everything
     * else travels in app-private storage - see [PlaybackCommandInbox].
     *
     * ## What the start is allowed to mean
     *
     * The start is requested only for a command that is **already on disk**: an enqueue
     * that did not commit is not an accepted command, and starting the service for it
     * would ask the service to act on a gesture that only ever existed in RAM - the P1
     * this design is about, one layer down.
     *
     * A start that throws is the other half. [PlaybackCommandInbox.withdraw] says
     * whether this caller's command was still waiting (nothing ran, and the gesture is
     * taken back out) or was already gone (another start consumed it), and that answer
     * decides the return value: reporting a failure for a command the service has
     * already carried out would tell the listener their press did nothing while the
     * radio changes station.
     */
    private fun deliver(context: Context, command: PlaybackCommand): Boolean {
        val inbox = PlaybackCommandInbox.forContext(context)

        // Durably recorded, and only then is a start requested.
        val queued = inbox.enqueue(command)
        if (queued == null) {
            Log.e("ServiceUtils", "Command not stored (action: ${command.action}): no start requested")
            PlaybackLog.problem(
                "SERVICE_START_SKIPPED",
                "action" to command.action,
                "reason" to "command_not_stored",
                "outcome" to "command_not_accepted"
            )
            return false
        }

        val intent = Intent(context, MediaPlayerService::class.java)

        return try {
            if (command.openForeground) {
                // For actions that start playback, we MUST use startForegroundService
                // to work in background, and the service MUST call startForeground().
                ContextCompat.startForegroundService(context, intent)
            } else {
                // For commands like get_status or switch_track, normal startService is fine.
                // These only work when the service is already running.
                // Do NOT fallback to startForegroundService — it would crash because
                // these actions don't trigger playback/notification.
                context.startService(intent)
            }
            true
        } catch (e: Exception) {
            Log.e("ServiceUtils", "Failed to start service (action: ${command.action}): ${e.message}")
            when (inbox.withdraw(queued.id)) {
                PlaybackCommandInbox.Withdraw.REMOVED_PENDING -> {
                    // The command never reached the service, so it must not be left in
                    // the inbox for an unrelated start to find later. Android 12+ refuses
                    // a foreground start from the background: that is a platform rule,
                    // not something to retry in a loop - record exactly what happened and
                    // let the caller decide.
                    val blockedByPlatform = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            e is android.app.ForegroundServiceStartNotAllowedException
                    PlaybackLog.problem(
                        "SERVICE_START_FAILED",
                        "action" to command.action,
                        "cause" to e.javaClass.simpleName,
                        "blockedByPlatform" to blockedByPlatform,
                        "outcome" to "start_refused"
                    )
                    false
                }

                PlaybackCommandInbox.Withdraw.NOT_FOUND -> {
                    // Another start - another of this app's screens, or a wake this
                    // caller does not own - consumed the command before this one was
                    // refused. The gesture has been delivered, so this is not a
                    // failure: reporting one would tell the listener their press did
                    // nothing while the radio acts on it.
                    PlaybackLog.problem(
                        "SERVICE_START_FAILED_BUT_DELIVERED",
                        "action" to command.action,
                        "cause" to e.javaClass.simpleName,
                        "outcome" to "command_already_consumed"
                    )
                    true
                }
            }
        }
    }
}
