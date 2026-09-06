package com.example.musicplayerapp.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.musicplayerapp.service.MediaPlayerService
import com.example.musicplayerapp.service.PlaybackLog
import com.example.musicplayerapp.service.SleepTimerContract

object ServiceUtils {
    
    /**
     * Starts the MediaPlayerService, handling Android 12+ background start restrictions.
     *
     * Returns whether the start was accepted. Failures used to be swallowed with
     * only a log line, so a blocked start looked to the user exactly like a Play
     * press that did nothing (issue #14). Callers can now see it.
     */
    fun safeStartService(context: Context, action: String, stream: String? = null, artist: String? = null, song: String? = null, forcePlay: Boolean = false): Boolean {
        val intent = Intent(context, MediaPlayerService::class.java).apply {
            putExtra("ACTION", action)
            stream?.let { putExtra("STREAM", it) }
            artist?.let { putExtra("ARTIST", it) }
            song?.let { putExtra("SONG", it) }
            putExtra("force_play", forcePlay)
        }
        
        // Actions that are guaranteed to call startForeground in MediaPlayerService
        val isForegroundAction = action == "startStop" || action == "play" || action == "switch"

        PlaybackLog.event(
            "SERVICE_START_REQUEST",
            "action" to action,
            "stream" to (stream ?: "none"),
            "forcePlay" to forcePlay,
            "foreground" to isForegroundAction
        )

        try {
            if (isForegroundAction) {
                // For actions that start playback, we MUST use startForegroundService 
                // to work in background, and the service MUST call startForeground().
                intent.putExtra("FOREGROUND_START", true)
                ContextCompat.startForegroundService(context, intent)
            } else {
                // For commands like get_status or switch_track, normal startService is fine.
                // These only work when the service is already running.
                // Do NOT fallback to startForegroundService — it would crash because
                // these actions don't trigger playback/notification.
                context.startService(intent)
            }
            return true
        } catch (e: Exception) {
            Log.e("ServiceUtils", "Failed to start service (action: $action): ${e.message}")
            // Android 12+ refuses a foreground start from the background. That is a
            // platform rule, not something to retry in a loop - record exactly what
            // happened and let the caller decide.
            val blockedByPlatform = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
            PlaybackLog.problem(
                "SERVICE_START_FAILED",
                "action" to action,
                "cause" to e.javaClass.simpleName,
                "blockedByPlatform" to blockedByPlatform,
                "outcome" to "start_refused"
            )
            return false
        }
    }

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
    ): Boolean {
        val intent = Intent(context, MediaPlayerService::class.java).apply {
            putExtra("ACTION", action)
            putExtra(SleepTimerContract.EXTRA_MINUTES, minutes)
            putExtra(SleepTimerContract.EXTRA_IS_CUSTOM, isCustom)
        }
        return try {
            context.startService(intent)
            true
        } catch (e: Exception) {
            PlaybackLog.problem(
                "SERVICE_START_FAILED",
                "action" to action,
                "cause" to e.javaClass.simpleName,
                "outcome" to "sleep_timer_command_dropped",
            )
            false
        }
    }
}
