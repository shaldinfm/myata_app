package com.example.musicplayerapp.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.musicplayerapp.service.MediaPlayerService

object ServiceUtils {
    
    /**
     * Safely starts the MediaPlayerService, handling Android 12+ background start restrictions.
     */
    fun safeStartService(context: Context, action: String, stream: String? = null, artist: String? = null, song: String? = null, forcePlay: Boolean = false) {
        val intent = Intent(context, MediaPlayerService::class.java).apply {
            putExtra("ACTION", action)
            stream?.let { putExtra("STREAM", it) }
            artist?.let { putExtra("ARTIST", it) }
            song?.let { putExtra("SONG", it) }
            putExtra("force_play", forcePlay)
        }
        
        // Actions that are guaranteed to call startForeground in MediaPlayerService
        val isForegroundAction = action == "startStop" || action == "play" || action == "switch"

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
        } catch (e: Exception) {
            Log.e("ServiceUtils", "Failed to start service (action: $action): ${e.message}")
        }
    }
}
