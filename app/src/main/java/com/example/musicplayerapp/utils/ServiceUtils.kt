package com.example.musicplayerapp.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.musicplayerapp.service.MediaPlayerService
import com.example.musicplayerapp.service.PlaybackCommand
import com.example.musicplayerapp.service.PlaybackCommands
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
            PlaybackCommand(
                action = action,
                stream = stream,
                artist = artist,
                song = song,
                forcePlay = forcePlay,
                openForeground = foregroundStart,
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
        PlaybackCommand(
            action = action,
            stream = stream,
            artist = artist,
            song = song,
            forcePlay = forcePlay,
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
     */
    fun sendSleepTimerCommand(
        context: Context,
        action: String,
        minutes: Int = 0,
        isCustom: Boolean = false,
    ): Boolean = deliver(
        context,
        PlaybackCommand(action = action, minutes = minutes, isCustom = isCustom),
    )

    /**
     * The one place a command meets a start.
     *
     * ## The order is the contract
     *
     * The command goes into the queue **first**, and the start second. The service
     * already running drains in its own `onStartCommand`, so a start delivered
     * between the two would otherwise be answered by an empty queue and the command
     * would sit there until the next start of any kind - which is exactly the kind
     * of "Play did nothing, and then a minute later the radio came on" behaviour
     * issue #14 was about.
     *
     * ## The intent carries nothing
     *
     * Not an action, not an extra, not the station: [Intent] here is a wake-up for
     * a component and a lifecycle signal, and the only thing that has to be decided
     * with it is whether this was a `startForegroundService` call. Everything else
     * travels in process memory, where no other app can put it - see
     * [PlaybackCommand].
     */
    private fun deliver(context: Context, command: PlaybackCommand): Boolean {
        val queued = PlaybackCommands.enqueue(command)
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
            // The command never reached the service, so it must not be left in the
            // queue for an unrelated start to find later.
            PlaybackCommands.withdraw(queued)
            Log.e("ServiceUtils", "Failed to start service (action: ${command.action}): ${e.message}")
            // Android 12+ refuses a foreground start from the background. That is a
            // platform rule, not something to retry in a loop - record exactly what
            // happened and let the caller decide.
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
    }
}
