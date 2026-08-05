package com.example.musicplayerapp.service

import android.app.*
import android.app.PendingIntent
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.content.pm.ServiceInfo
import androidx.media3.session.* 
import androidx.media3.common.MediaMetadata
import androidx.media3.common.ForwardingPlayer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerNotificationManager
import com.example.musicplayerapp.R
import com.example.musicplayerapp.SecureNetModule
import com.example.musicplayerapp.MainActivity
import com.google.gson.Gson
import com.squareup.picasso.Picasso
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import android.app.NotificationChannel
import android.app.NotificationManager


class MediaPlayerService(): MediaSessionService(){

    private lateinit var exoPlayer: ExoPlayer
    
    // MediaSession for lock screen / notification metadata
    private var mediaSession: MediaSession? = null
    
    // Coroutine scope for background metadata polling
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var metadataJob: Job? = null
    
    // WakeLock to prevent sleep on Android TV
    private var wakeLock: PowerManager.WakeLock? = null
    
    // OkHttp client for API requests (full TLS validation, extra roots bundled)
    private val httpClient by lazy { SecureNetModule.getOkHttpClient(this) }
    
    // Artwork Repository (single source of truth for album art)
    private val artworkRepository by lazy { com.example.musicplayerapp.data.ArtworkRepository(httpClient) }
    
    // Image cache for album art
    private val albumArtCache = mutableMapOf<String, Bitmap?>()
    private var currentAlbumArt: Bitmap? = null
    
    // Platform type for optimizations
    private var isTv: Boolean = false
    private var lastFetchedArtist: String? = null
    private var lastFetchedSong: String? = null
    private var currentAlbumArtUrl: String? = null
    private var fetchJob: kotlinx.coroutines.Job? = null
    
    // HTTPS URLs (по умолчанию)
    val myataItemHttps = MediaItem.fromUri("https://radio.dline-media.com/myata")
    val xtraItemHttps = MediaItem.fromUri("https://radio.dline-media.com/myata_hits")
    val goldItemHttps = MediaItem.fromUri("https://radio.dline-media.com/gold")
    
    // HTTP URLs (fallback для проекторов с проблемами SSL)
    val myataItemHttp = MediaItem.fromUri("http://radio.dline-media.com/myata")
    val xtraItemHttp = MediaItem.fromUri("http://radio.dline-media.com/myata_hits")
    val goldItemHttp = MediaItem.fromUri("http://radio.dline-media.com/gold")
    
    // Флаг: использовать HTTP fallback (включается при сетевой ошибке на TV)
    private var useHttpFallback = false
    
    // Геттеры: HTTPS по умолчанию, HTTP как fallback на TV
    val myataItem: MediaItem get() = if (useHttpFallback) myataItemHttp else myataItemHttps
    val xtraItem: MediaItem get() = if (useHttpFallback) xtraItemHttp else xtraItemHttps
    val goldItem: MediaItem get() = if (useHttpFallback) goldItemHttp else goldItemHttps

    var song: String = ""
    var artist: String = ""
    var stream: String = ""

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // CRITICAL: Android requires startForeground() within 5s of startForegroundService().
        // Post a minimal notification immediately to satisfy the contract.
        // Media3 will replace it with the real notification (with controls) moments later.
        val isForegroundStart = intent?.getBooleanExtra("FOREGROUND_START", false) == true
        if (isForegroundStart) {
            try {
                val channelId = "playback_channel"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(channelId, "Playback", NotificationManager.IMPORTANCE_LOW)
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
                }
                val notification = android.app.Notification.Builder(this, channelId)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Radio Myata")
                    .setContentText("Загрузка...")
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(1, notification)
                }
                Log.d("MediaPlayerService", "Foreground notification posted immediately")
            } catch (e: Exception) {
                Log.e("MediaPlayerService", "Failed to post foreground notification: ${e.message}")
            }
        }

        if(intent != null) {
            val action = intent.getStringExtra("ACTION")
            
            when(action){
                "startStop"->{
                    if(exoPlayer.isPlaying) {
                        exoPlayer.stop()
                        exoPlayer.clearMediaItems()
                        artist = ""
                        song = ""
                        updateMetadata("", "")
                    }
                    else{
                        val intentStream = intent.getStringExtra("STREAM")
                        if (intentStream != null) {
                            stream = intentStream
                        }
                        // Always set MediaItem (it may have been cleared by stop)
                        when(stream){
                            "myata"->{exoPlayer.setMediaItem(myataItem)}
                            "gold"->{exoPlayer.setMediaItem(goldItem)}
                            "myata_hits"->{exoPlayer.setMediaItem(xtraItem)}
                        }
                        
                        // Use updateMetadata to ensure art is reset, fetched, and notification updated
                        val startSong = intent.getStringExtra("SONG") ?: ""
                        val startArtist = intent.getStringExtra("ARTIST") ?: ""
                        updateMetadata(startArtist, startSong)
                        
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }
                }
                "play"->{
                    val intentStream = intent.getStringExtra("STREAM")
                    if (intentStream != null && stream != intentStream)
                    {
                        stream = intentStream
                        when(stream){
                            "myata"->{exoPlayer.setMediaItem(myataItem)}
                            "gold"->{exoPlayer.setMediaItem(goldItem)}
                            "myata_hits"->{exoPlayer.setMediaItem(xtraItem)}
                        }
                        exoPlayer.prepare()
                    }
                    if(!exoPlayer.isPlaying) {
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }
                }
                "switch"->{
                    val intentStream = intent.getStringExtra("STREAM")
                    val forcePlay = intent.getBooleanExtra("force_play", false)
                    
                    if (intentStream == null) return START_NOT_STICKY
                    
                    val isStreamChange = stream != intentStream
                    
                    if (isStreamChange) {
                        // DIFFERENT stream - need to set up new media item
                        stream = intentStream
                        
                        val switchSong = intent.getStringExtra("SONG") ?: ""
                        val switchArtist = intent.getStringExtra("ARTIST") ?: ""

                        val initialMetadata = MediaMetadata.Builder()
                            .setArtist(switchArtist)
                            .setTitle(switchSong)
                            .setAlbumTitle(getStreamDisplayName())
                            .build()

                        val mediaItem = when(stream){
                            "myata"->myataItem
                            "gold"->goldItem
                            "myata_hits"->xtraItem
                            else -> myataItem
                        }.buildUpon().setMediaMetadata(initialMetadata).build()
                        
                        exoPlayer.setMediaItem(mediaItem)
                        currentAlbumArt = null
                        updateMetadata(switchArtist, switchSong)
                        
                        // Always start playback for stream changes
                        exoPlayer.prepare()
                        exoPlayer.play()
                        Log.d("SWITCH", "Stream switched to $stream and playback started")
                    } else {
                        // SAME stream - only start if forcePlay requested AND not already playing
                        if (forcePlay && !exoPlayer.isPlaying) {
                            exoPlayer.prepare()
                            exoPlayer.play()
                            Log.d("SWITCH", "Same stream $stream - resuming playback")
                        } else {
                            Log.d("SWITCH", "Same stream $stream - already playing, no action needed")
                        }
                    }
                }

                "switch_track"->{
                    val newSong = intent.getStringExtra("SONG") ?: ""
                    val newArtist = intent.getStringExtra("ARTIST") ?: ""
                    // Use updateMetadata to ensure art is reset, fetched, and notification updated
                    updateMetadata(newArtist, newSong)
                    Log.d("SWITCH", "Track metadata updated: $newArtist - $newSong")
                }
                "get_status" -> {
                    // Broadcast current state to sync UI
                    val action = if(exoPlayer.isPlaying) "play" else "pause"
                    LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(action))
                    if (exoPlayer.playbackState == Player.STATE_BUFFERING) {
                        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("buffering"))
                    }
                    
                    // Also broadcast current metadata so UI can update immediately
                    // ONLY if playing or buffering to avoid overriding fresh API metadata with stale service data
                    if (stream.isNotEmpty() && (exoPlayer.isPlaying || exoPlayer.playbackState == Player.STATE_BUFFERING)) {
                        LocalBroadcastManager.getInstance(this).sendBroadcast(
                            Intent("metadata_update").apply {
                                putExtra("artist", artist)
                                putExtra("song", song)
                                putExtra("stream", stream)
                                // Send album art URL if we have one for this track
                                val currentCacheKey = "$artist:$song"
                                // We don't have direct access to the URL map here easily without refactoring, 
                                // but the UI will fetch if missing or using the "metadata_update" receiver in VM 
                                // can trigger a fetch if needed. 
                                // Actually, let's trigger a fresh broadcast from updateMetadata logic if possible 
                                // or just send what we have.
                            }
                        )
                    }
                    Log.d("MediaPlayerService", "Status requested: $action")
                }
                "stop" -> {
                    Log.d("MediaPlayerService", "Stop action received - shutting down")
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()

        Log.d("Service","Create")
        
        // Initialize WakeLock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyataRadio::PlaybackWakeLock")
        wakeLock?.setReferenceCounted(false)

        // Определяем тип устройства для оптимизации плеера
        isTv = isTvDevice()
        
        // Configure LoadControl - разные настройки для мобильных и TV
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (isTv) 5000 else 3000,   // minBufferMs - увеличено для TV/проекторов
                if (isTv) 15000 else 5000,  // maxBufferMs - увеличено для стабильности на TV
                if (isTv) 2500 else 1500,   // bufferForPlaybackMs - больше буфер на TV
                if (isTv) 3000 else 2000    // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Configure AudioAttributes for proper audio focus handling
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()

        // Configure HttpDataSource - OkHttp, so the stream uses the same validated
        // trust anchors as the rest of the app (see SecureNetModule).
        val callFactory = SecureNetModule.getOkHttpClient(this)

        val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(callFactory)
            .setUserAgent(if (isTv) "MyataRadio/1.0 (Android TV)" else "MyataRadio/1.0 (Android)")

        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, httpDataSourceFactory)

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                .setDataSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true) // true = automatic audio focus handling
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK) // Prevent CPU sleep
            .build().apply {
            addListener(object: Player.Listener{
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    super.onIsPlayingChanged(isPlaying)
                    
                    val action = if(isPlaying) "play" else "pause"
                    LocalBroadcastManager.getInstance(this@MediaPlayerService)
                        .sendBroadcast(Intent(action))
                    
                    // Update MediaSession playback state
                    updatePlaybackState(isPlaying)
                    
                    // Start/stop metadata polling based on playback state
                    // AND manage WakeLock
                    if (isPlaying) {
                        startMetadataPolling()
                        if (wakeLock?.isHeld == false) {
                            wakeLock?.acquire()
                            Log.d("MediaPlayerService", "WakeLock acquired")
                        }
                    } else {
                        stopMetadataPolling()
                        
                        if (wakeLock?.isHeld == true) {
                            wakeLock?.release()
                            Log.d("MediaPlayerService", "WakeLock released")
                        }
                    }
                }
                
                override fun onPlaybackStateChanged(playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)
                    
                    when(playbackState) {
                        Player.STATE_BUFFERING -> {
                            // Broadcast buffering state
                            val intent = Intent("buffering")
                            LocalBroadcastManager.getInstance(this@MediaPlayerService)
                                .sendBroadcast(intent)
                        }
                        Player.STATE_READY -> {
                            // When ready and playing, ensure play state is broadcast
                            if (this@apply.isPlaying) {
                                val intent = Intent("play")
                                LocalBroadcastManager.getInstance(this@MediaPlayerService)
                                    .sendBroadcast(intent)
                            }
                        }
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    super.onPlayerError(error)
                    Log.e("MediaPlayerService", "Player Error: ${error.errorCodeName} (${error.errorCode})")
                    
                    // Auto-retry logic remains same
                    if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED) {
                        
                        if (!useHttpFallback) {
                            Log.d("MediaPlayerService", "HTTPS failed, switching to HTTP fallback...")
                            useHttpFallback = true
                            LocalBroadcastManager.getInstance(this@MediaPlayerService).sendBroadcast(Intent("buffering"))
                            
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (stream.isNotEmpty()) {
                                    when(stream) {
                                        "myata" -> setMediaItem(myataItem)
                                        "gold" -> setMediaItem(goldItem)
                                        "myata_hits" -> setMediaItem(xtraItem)
                                    }
                                    prepare()
                                    play()
                                }
                            }, 1000)
                        } else {
                            Log.d("MediaPlayerService", "Network error, attempting to reconnect...")
                            LocalBroadcastManager.getInstance(this@MediaPlayerService).sendBroadcast(Intent("buffering"))
                            
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (stream.isNotEmpty()) {
                                    prepare()
                                    play()
                                }
                            }, 2000)
                        }
                    } else {
                        LocalBroadcastManager.getInstance(this@MediaPlayerService).sendBroadcast(Intent("pause"))
                    }
                }
            })
        }

        // Note: isTv is already initialized in onCreate()

        val forwardingPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun pause() {
                // Unified behavior for all platforms (Mobile & TV): 
                // Pause acts as Stop to clear buffer and ensure live edge on resume
                stop()
                Log.d("MediaPlayerService", "Pause action: Stream stopped (buffer cleared) for radio edge")
            }

            override fun play() {
                // When resuming from pause, re-prepare to jump to live edge
                // This handles both STATE_READY (paused) and STATE_IDLE (stopped) cases
                if (playbackState == Player.STATE_READY && !playWhenReady) {
                    Log.d("MediaPlayerService", "Resuming from pause: Re-preparing for live edge")
                    prepare()
                } else if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                    Log.d("MediaPlayerService", "Resuming from stop: Re-preparing stream")
                    prepare()
                }
                super.play()
            }

            override fun getAvailableCommands(): Player.Commands {
                // Support both Pause and Stop for maximum system compatibility
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_STOP)
                    .build()
            }
        }

        // Initialize MediaSession (Media3 will handle notification based on this)
        initializeMediaSession(forwardingPlayer)
    }
    
    private fun initializeMediaSession(player: Player) {
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            ))
            .build()
        
        Log.d("MediaPlayerService", "Media3 MediaSession initialized with Stop-as-Pause behavior")
    }

    private fun isTvDevice(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
        if (uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }
        return !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // IMPORTANT: Do NOT stop playback when task is removed!
        // This allows radio to continue playing when:
        // - User swipes app from recents
        // - System kills app in Doze mode
        // - Phone goes to sleep while listening (the main user complaint!)
        // 
        // The foreground service with notification will keep running.
        // User can stop playback via the notification controls.
        Log.d("MediaPlayerService", "Task removed - keeping playback alive (foreground service continues)")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        metadataJob?.cancel()
        serviceScope.cancel()
        
        mediaSession?.release()
        mediaSession = null
        
        exoPlayer.release()
        
        LocalBroadcastManager.getInstance(this@MediaPlayerService)
            .sendBroadcast(Intent("Dismiss").apply {})
        
        super.onDestroy()
    }
    
    // ============== SMART POLLING FOR METADATA ==============
    
    private fun startMetadataPolling() {
        metadataJob?.cancel()
        metadataJob = serviceScope.launch {
            Log.d("MetadataPolling", "Starting metadata polling for stream: $stream")
            
            // Fetch metadata IMMEDIATELY on start (don't wait for first loop iteration)
            fetchMetadataAndGetDelay()
            
            while (isActive && stream.isNotEmpty()) {
                val delayMs = fetchMetadataAndGetDelay()
                // Force minimum 10 seconds delay to prevent spamming notifications/system
                val safeDelay = delayMs.coerceAtLeast(10000L)
                Log.d("MetadataPolling", "Next metadata update in ${safeDelay / 1000} seconds")
                delay(safeDelay)
            }
        }
    }
    
    private fun stopMetadataPolling() {
        metadataJob?.cancel()
        metadataJob = null
        Log.d("MetadataPolling", "Stopped metadata polling")
    }
    
    private suspend fun fetchMetadataAndGetDelay(): Long {
        return try {
            val url = "https://radiomyata.ru/api_all_tracks.php?v=${System.currentTimeMillis()}"
            val request = Request.Builder().url(url).build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("MetadataPolling", "API failed: ${response.code}")
                    return@use 15000L // Retry in 15 seconds
                }
                
                val jsonBody = response.body?.string() ?: return@use 15000L
                val apiResponse = Gson().fromJson(jsonBody, Map::class.java)
                val data = apiResponse["data"] as? Map<String, Any> ?: return@use 15000L
                val serverTime = (apiResponse["server_time"] as? Double)?.toLong() ?: System.currentTimeMillis() / 1000
                
                // Get current stream data
                val streamData = data[stream] as? Map<String, Any> ?: return@use 15000L
                
                // Check STATUS if available (to ignore updates when stopped/off-air)
                val status = streamData["status"] as? String ?: "playing" // Default to playing if missing
                if (status.equals("stopped", ignoreCase = true) || status.equals("off", ignoreCase = true)) {
                     Log.d("MetadataPolling", "Stream status is $status, skipping update?")
                     // return@use 15000L // REVERTED: User reported art stopped loading. Maybe status is unreliable?
                }

                val newArtist = streamData["artist"] as? String ?: ""
                val newSong = streamData["track"] as? String ?: ""
                val endsAt = (streamData["ends_at"] as? Double)?.toLong() ?: (serverTime + 15)
                
                // Check if metadata changed
                val trackChanged = newArtist != artist || newSong != song
                
                if (trackChanged) {
                    withContext(Dispatchers.Main) {
                        updateMetadata(newArtist, newSong)
                    }
                    // Track just changed - wait until ends_at + buffer
                    // CAP AT 40 SECONDS to avoid getting stuck if ends_at is wrong, but save battery/traffic
                    val delaySeconds = (endsAt - serverTime + 3).coerceAtLeast(5).coerceAtMost(40)
                    Log.d("MetadataPolling", "Track changed, waiting ${delaySeconds}s")
                    delaySeconds * 1000L
                } else {
                    // Track hasn't changed - might be during jingle/promo (up to 50s)
                    // Check every 10 seconds until new track appears
                    val secondsSinceEnd = serverTime - endsAt
                    if (secondsSinceEnd > 0) {
                        // Already past ends_at, likely jingle/promo playing
                        Log.d("MetadataPolling", "Past ends_at by ${secondsSinceEnd}s, checking again in 10s")
                        10000L
                    } else {
                        // Still waiting for current track to end
                        // CAP AT 40 SECONDS to ensure we don't miss short tracks or updates
                        val delaySeconds = (endsAt - serverTime + 3).coerceAtLeast(5).coerceAtMost(40)
                        Log.d("MetadataPolling", "Waiting ${delaySeconds}s")
                        delaySeconds * 1000L
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MetadataPolling", "Error fetching metadata: ${e.message}")
            15000L // Retry in 15 seconds on error
        }
    }
    
    // fetchJob declared at class level (line 63)

    private fun updateMetadata(artist: String, song: String) {
        // Cancel any pending start fetch from previous track
        fetchJob?.cancel()

        // Deduplicate updates EXCEPT when we are force-clearing metadata on pause/stop
        val isReset = artist.isBlank() && song.isBlank()
        if (!isReset && this.artist == artist && this.song == song) {
            Log.d("MetadataPolling", "Metadata unchanged, skipping update: $artist - $song")
            return
        }

        // Use placeholders for empty metadata to keep UI clean
        val finalArtist = if (artist.isBlank()) getString(R.string.slogan_placeholder) else artist
        val finalSong = if (song.isBlank()) getString(R.string.brand_name) else song

        // Fix: Don't overwrite valid metadata with empty strings during playback
        if (artist.isBlank() && song.isBlank() && (this.artist.isNotBlank() || this.song.isNotBlank()) && exoPlayer.isPlaying) {
             Log.d("MetadataPolling", "Ignoring empty metadata update during playback")
             return
        }

        this.artist = finalArtist
        this.song = finalSong
        
        // ONLY reset art and metadata if the track has ACTUALLY changed.
        // This prevents flickering during simple polling cycles.
        val trackChanged = artist != lastFetchedArtist || song != lastFetchedSong
        
        if (trackChanged) {
            Log.d("MetadataPolling", "Track changed, resetting art: $artist - $song")
            currentAlbumArt = getPlaceholderBitmap()
            currentAlbumArtUrl = null // Reset URL for new track
            lastFetchedArtist = artist
            lastFetchedSong = song
            // Cancel any pending fetch for the previous track
            fetchJob?.cancel()
        } else {
            Log.d("MetadataPolling", "Same track, keeping current art: $artist - $song")
        }
        
        val metadataBuilder = MediaMetadata.Builder()
            .setArtist(finalArtist)
            .setTitle(finalSong)
            .setDisplayTitle(finalSong)
            .setSubtitle(finalArtist)
            .setAlbumTitle(getStreamDisplayName())
            
        // Preserve current artwork URL if available to prevent flickering
        currentAlbumArtUrl?.let {
            metadataBuilder.setArtworkUri(android.net.Uri.parse(it))
        }
            
        // Force Media3 metadata update
        val metadata = metadataBuilder.build()
        exoPlayer.currentMediaItem?.let {
            val newItem = it.buildUpon().setMediaMetadata(metadata).build()
            exoPlayer.replaceMediaItem(exoPlayer.currentMediaItemIndex, newItem)
        }
        
        // Async: Fetch and set album art
        // We capture currentArtist/currentSong to avoid race conditions
        val currentArtist = artist
        val currentSong = song
        
        fetchJob = serviceScope.launch {
            // Don't fetch artwork for placeholders/resets
            if (currentArtist.isBlank() && currentSong.isBlank()) {
                return@launch
            }

            // If it's the same track and we already have a real image, don't re-fetch!
            if (!trackChanged && currentAlbumArt != null && currentAlbumArt != getPlaceholderBitmap()) {
                Log.d("MetadataPolling", "Already have art for $artist - $song, skipping fetch.")
                return@launch
            }

            try {
                val albumArtUrl = fetchAlbumArtUrl(currentArtist, currentSong)
                val bitmap = if (albumArtUrl != null) {
                    loadAlbumArtBitmap(albumArtUrl)
                } else {
                    null
                }
                
                // Use fallback logo if no album art found
                val finalBitmap = bitmap ?: getPlaceholderBitmap()
                
                withContext(Dispatchers.Main) {
                    // Only update if the track hasn't changed while we were fetching art
                    if (artist == currentArtist && song == currentSong) {
                        currentAlbumArt = finalBitmap // UPDATE THE CACHED BITMAP FOR ADAPTER
                        updateMediaSessionWithArt(finalBitmap, currentArtist, currentSong)
                        Log.d("MetadataPolling", if (bitmap != null) "Album art set: $albumArtUrl" else "Using placeholder logo")
                        
                        // Update player metadata with art URL and bitmap
                        val updatedMetadata = exoPlayer.mediaMetadata.buildUpon()
                        if (albumArtUrl != null) {
                            updatedMetadata.setArtworkUri(android.net.Uri.parse(albumArtUrl))
                        }
                        
                        currentAlbumArtUrl = albumArtUrl // Persist the URL
                        val metadata = updatedMetadata.build()
                        exoPlayer.currentMediaItem?.let {
                            val newItem = it.buildUpon().setMediaMetadata(metadata).build()
                            exoPlayer.replaceMediaItem(exoPlayer.currentMediaItemIndex, newItem)
                        }
                        Log.d("MetadataPolling", "Updated player metadata with art URL")
                    } else {
                        Log.d("MetadataPolling", "Track changed, skipping art update for: $currentArtist - $currentSong")
                    }
                }
            } catch (e: Exception) {
                Log.e("MetadataPolling", "Failed to load album art: ${e.message}")
                // Set placeholder on error
                withContext(Dispatchers.Main) {
                    if (artist == currentArtist && song == currentSong) {
                        updateMediaSessionWithArt(getPlaceholderBitmap(), currentArtist, currentSong)
                    }
                }
            }
        }
    }
    
    private fun getPlaceholderBitmap(): Bitmap? {
        return try {
            android.graphics.BitmapFactory.decodeResource(resources, R.drawable.zaglushka_logo)
        } catch (e: Exception) {
            Log.e("MetadataPolling", "Failed to load placeholder: ${e.message}")
            null
        }
    }
    
    private fun updatePlaybackState(isPlaying: Boolean) {
        // Handled automatically by Media3 Session
    }
    
    private fun getStreamDisplayName(): String {
        return when(stream) {
            "myata" -> "Radio Myata"
            "gold" -> "Radio Myata Gold"
            "myata_hits" -> "Radio Myata XTRA"
            else -> "Radio Myata"
        }
    }
    
    // ============== ARTWORK FETCHING (Delegated to ArtworkRepository) ==============
    
    private suspend fun fetchAlbumArtUrl(artist: String, track: String): String? {
        return artworkRepository.fetchArtwork(artist, track).coverUrl
    }
    
    // ============== BITMAP LOADING ==============
    
    private suspend fun loadAlbumArtBitmap(imageUrl: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(imageUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("Service", "Failed to download bitmap: ${response.code}")
                        return@use null
                    }
                    val bytes = response.body?.bytes() ?: return@use null
                    
                    val options = android.graphics.BitmapFactory.Options()
                    options.inJustDecodeBounds = true
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    
                    val reqWidth = 300
                    val reqHeight = 300
                    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
                    
                    options.inJustDecodeBounds = false
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }
            } catch (e: Exception) {
                Log.e("Service", "Error loading bitmap: ${e.message}")
                null
            }
        }
    }
    
    private fun calculateInSampleSize(options: android.graphics.BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
    
    private fun updateMediaSessionWithArt(bitmap: Bitmap?, currentArtist: String, currentSong: String) {
        currentAlbumArt = bitmap
        // Notification is automatically updated by Media3 when metadata changes
    }
}