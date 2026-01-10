package com.example.musicplayerapp.service

import android.app.*
import android.app.PendingIntent
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerNotificationManager
import com.example.musicplayerapp.R
import com.example.musicplayerapp.UnsafeNetModule
import com.example.musicplayerapp.MainActivity
import com.google.gson.Gson
import com.squareup.picasso.Picasso
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit


class MediaPlayerService(): Service(){

    private lateinit var exoPlayer: ExoPlayer
    
    // MediaSession for lock screen / notification metadata
    private var mediaSession: MediaSessionCompat? = null
    
    // Coroutine scope for background metadata polling
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var metadataJob: Job? = null
    
    // WakeLock to prevent sleep on Android TV
    private var wakeLock: PowerManager.WakeLock? = null
    
    // OkHttp client for API requests (Unsafe for legacy device support)
    private val httpClient = UnsafeNetModule.getUnsafeOkHttpClient()
    
    // Spotify API (Removed)
    // private val spotifyClientId = "..."
    // private val spotifyClientSecret = "..."
    
    // Image cache for album art
    private val albumArtCache = mutableMapOf<String, Bitmap?>()
    private var currentAlbumArt: Bitmap? = null
    
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

    var playerNotificationManager: PlayerNotificationManager? = null
    var song: String = ""
    var artist: String = ""
    var stream: String = ""
    lateinit var notification: Notification


    val mediaDescriptionAdapter = object: PlayerNotificationManager.MediaDescriptionAdapter{
        override fun getCurrentContentTitle(player: Player): CharSequence {
            return artist
        }

        @android.annotation.SuppressLint("UnspecifiedImmutableFlag")
        override fun createCurrentContentIntent(player: Player): PendingIntent? {
            val intent = Intent(this@MediaPlayerService, MainActivity::class.java)
            intent.action = stream
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.getActivity(this@MediaPlayerService, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            } else {
                PendingIntent.getActivity(this@MediaPlayerService, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
            }
        }


        override fun getCurrentContentText(player: Player): CharSequence? {
            return song
        }

        override fun getCurrentSubText(player: Player): CharSequence? {
            return super.getCurrentSubText(player)
        }

        override fun getCurrentLargeIcon(
            player: Player,
            callback: PlayerNotificationManager.BitmapCallback
        ): Bitmap? {
            return currentAlbumArt
        }
    }


    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        super.onStartCommand(intent, flags, startId)

        if(intent != null) {
            when(intent?.getStringExtra("ACTION")){
                "startStop"->{
                    if(exoPlayer.isPlaying) {
                        exoPlayer.stop()
                        exoPlayer.clearMediaItems()
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
                        updateNotificationColor(stream)
                        // Update metadata from intent
                        song = intent.getStringExtra("SONG") ?: ""
                        artist = intent.getStringExtra("ARTIST") ?: ""
                        playerNotificationManager?.invalidate()
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
                        updateNotificationColor(stream)
                    }
                    if(!exoPlayer.isPlaying) {
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }
                }
                "switch"->{
                    val wasPlaying = exoPlayer.isPlaying
                    val intentStream = intent.getStringExtra("STREAM")
                    if (intentStream != null && stream != intentStream)
                    {
                        stream = intentStream
                        when(stream){
                            "myata"->{exoPlayer.setMediaItem(myataItem)}
                            "gold"->{exoPlayer.setMediaItem(goldItem)}
                            "myata_hits"->{exoPlayer.setMediaItem(xtraItem)}
                        }
                        updateNotificationColor(stream)
                        
                        // Update MediaSession immediately with new stream info
                        song = intent.getStringExtra("SONG") ?: ""
                        artist = intent.getStringExtra("ARTIST") ?: ""
                        updateMetadata(artist, song)
                        
                        // If was playing, restart playback on new stream
                        if (wasPlaying) {
                            exoPlayer.prepare()
                            exoPlayer.play()
                            Log.d("SWITCH", "Stream switched to $stream and playback resumed")
                        }
                    } else {
                        // Same stream - just update metadata
                        val newSong = intent.getStringExtra("SONG") ?: ""
                        val newArtist = intent.getStringExtra("ARTIST") ?: ""
                        updateMetadata(newArtist, newSong)
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
                    if (stream.isNotEmpty()) {
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
            }
        }

        return START_STICKY
    }

    override fun onCreate() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = Notification.Builder(this, createNotificationChannel("307","307"))
                .setSmallIcon(R.drawable.zaglushka_logo)
                .setContentTitle("Myata Radio")
                .setContentText("Playing in background")
                .build()
            try {
                // IMPORTANT: For TV devices, manual startForeground is required immediately 
                // because we disabled PlayerNotificationManager for them to avoid artwork crashes.
                // Without this, the service is background-only and gets killed on app exit.
                val isTv = isTvDevice()
                if (isTv) {
                    startForeground(307, notification)
                    Log.d("MediaPlayerService", "Started manual foreground service for TV")
                }
            } catch (e: Exception) {
                Log.e("Service", "Failed to start foreground: ${e.message}")
            }
        } else {
            notification = Notification.Builder(this)
                .setSmallIcon(R.drawable.zaglushka_logo)
                .setContentTitle("Myata Radio")
                .setContentText("Playing in background")
                .build()
            try {
                val isTv = isTvDevice()
                if (isTv) {
                    startForeground(307, notification)
                }
            } catch (e: Exception) {
                Log.e("Service", "Failed to start foreground: ${e.message}")
            }
        }

        Log.e("Service","Create")
        
        // Initialize WakeLock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyataRadio::PlaybackWakeLock")
        wakeLock?.setReferenceCounted(false)

        // Определяем тип устройства для оптимизации настроек
        val isTv = isTvDevice()
        
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

        // Configure HttpDataSource - Use OkHttp to allow SSL bypass for legacy devices
        val connectTimeout = if (isTv) 30000 else 15000
        val readTimeout = if (isTv) 30000 else 15000
        
        // Use the unsafe client that trusts all certs
        val unsafeCallFactory = UnsafeNetModule.getUnsafeOkHttpClient()
        
        val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(unsafeCallFactory)
            .setUserAgent(if (isTv) "MyataRadio/1.0 (Android TV)" else "MyataRadio/1.0 (Android)")
            // .setAllowCrossProtocolRedirects(true) // OkHttp handles redirects automatically
            // .setConnectTimeoutMs(connectTimeout) // Set in OkHttpClient builder
            // .setReadTimeoutMs(readTimeout)       // Set in OkHttpClient builder
            // .setKeepPostFor302Redirects(true) // Not supported/needed in OkHttpDataSource

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
                    val intent = Intent(action).apply {
                    }
                    LocalBroadcastManager.getInstance(this@MediaPlayerService)
                        .sendBroadcast(intent)
                    
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
                        // If buffering, we might want to keep it? 
                        // But onIsPlayingChanged(false) usually means paused or buffering ended (if it was true)
                        // Actually wait, onIsPlayingChanged is simpler than state change.
                        
                        // Check if we are incorrectly stopping polling during buffering?
                        // If STATE_BUFFERING, isPlaying is false in ExoPlayer terms? No, depends.
                        // "isPlaying" returns true if state is READY + playWhenReady=true OR buffering + playWhenReady=true.
                        
                        // So if isPlaying is false, we are truly paused or stopped (or error).
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
                    
                    // Auto-retry on network errors or transient issues
                    if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED) {
                        
                        // На ВСЕХ устройствах: если HTTPS не сработал — пробуем HTTP fallback
                        // Это спасает старые телефоны и проекторы с устаревшими корневыми сертификатами
                        if (!useHttpFallback) {
                            Log.d("MediaPlayerService", "HTTPS failed, switching to HTTP fallback...")
                            useHttpFallback = true
                            
                            // Broadcast buffering state to UI
                            LocalBroadcastManager.getInstance(this@MediaPlayerService).sendBroadcast(Intent("buffering"))
                            
                            // Перезагружаем стрим с HTTP URL
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
                            // Обычный retry (уже на HTTP или мобильное устройство)
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
                        // For other errors, broadcast pause so UI button resets
                        LocalBroadcastManager.getInstance(this@MediaPlayerService).sendBroadcast(Intent("pause"))
                    }
                }
            })
        }

        // Only create notification player for mobile devices (not TV)
        if (!isTvDevice()) {
            val notificationListener: PlayerNotificationManager.NotificationListener =
                object : PlayerNotificationManager.NotificationListener {

                    override fun onNotificationCancelled(
                        notificationId: Int,
                        dismissedByUser: Boolean
                    ) {
                        Log.d("DISMISS","onNotificationCancelled dismissedByUser $dismissedByUser")
                        stopSelf()
                    }

                    override fun onNotificationPosted(
                        notificationId: Int,
                        notification: Notification,
                        ongoing: Boolean
                    ) {
                        if(ongoing){
                            try {
                                startForeground(notificationId, notification)
                            } catch (e: Exception) {
                                Log.e("Service", "Failed to update foreground: ${e.message}")
                            }
                        }
                        else{
                            stopForeground(false)
                        }
                    }
                }

            playerNotificationManager = PlayerNotificationManager.Builder(
                this, 307, "307")
                .setNotificationListener(notificationListener)
                .setMediaDescriptionAdapter(mediaDescriptionAdapter)
                .build()

            playerNotificationManager!!.setPlayer(exoPlayer)
            playerNotificationManager?.setUseStopAction(true)
            
            Log.d("MediaPlayerService", "Notification player created for mobile device")
        } else {
            Log.d("MediaPlayerService", "Skipping notification player for TV device")
        }
        
        // Initialize MediaSession for background metadata sync
        initializeMediaSession()
        
        // Pre-warm not needed for iTunes
        // serviceScope.launch { ... }
        
        super.onCreate()
    }
    
    private fun initializeMediaSession() {
        mediaSession = MediaSessionCompat(this, "MyataRadioSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            
            // Set callback for media button events
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (!exoPlayer.isPlaying) {
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }
                }
                
                override fun onPause() {
                    if (exoPlayer.isPlaying) {
                        exoPlayer.pause()
                    }
                }
                
                override fun onStop() {
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                }
            })
            
            isActive = true
        }
        
        Log.d("MediaPlayerService", "MediaSession initialized")
    }

    private fun isTvDevice(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
        if (uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }
        // Fallback: Check for touchscreen. If missing, treat as TV/Projector.
        return !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
    }

    private fun updateNotificationColor(stream: String) {
        val color = when(stream) {
            "myata" -> 0xFF5FD9B4.toInt()  // Mint green
            "gold" -> 0xFF2FB56A.toInt()   // Green
            "myata_hits" -> 0xFF1C4771.toInt()  // Blue
            else -> 0xFF5FD9B4.toInt()
        }
        playerNotificationManager?.setColor(color)
    }


    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(channelId: String, channelName: String): String{
        val chan = NotificationChannel(channelId,
            "Радио Мята Плеер", NotificationManager.IMPORTANCE_LOW)
        chan.description = "Управление воспроизведением радио"
        chan.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // When user swipes app away from recents, stop playback and service
        Log.d("MediaPlayerService", "Task removed, stopping playback and service")
        
        // Stop playback
        if (exoPlayer.isPlaying) {
            exoPlayer.stop()
        }
        
        // Stop the service
        stopSelf()
        
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Stop metadata polling
        metadataJob?.cancel()
        serviceScope.cancel()
        
        // Release MediaSession
        mediaSession?.release()
        mediaSession = null
        
        LocalBroadcastManager.getInstance(this@MediaPlayerService)
            .sendBroadcast(Intent("Dismiss").apply {})
        playerNotificationManager?.setPlayer(null)
        stopForeground(true)
        stopSelf()
        Log.e("Service","Stopped")
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        
        exoPlayer.release()
        super.onDestroy()
    }
    
    // ============== SMART POLLING FOR METADATA ==============
    
    private fun startMetadataPolling() {
        metadataJob?.cancel()
        metadataJob = serviceScope.launch {
            Log.d("MetadataPolling", "Starting metadata polling for stream: $stream")
            
            while (isActive && stream.isNotEmpty()) {
                val delayMs = fetchMetadataAndGetDelay()
                Log.d("MetadataPolling", "Next metadata update in ${delayMs / 1000} seconds")
                delay(delayMs)
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
    
    // Job for canceling previous art fetch operations
    private var fetchJob: Job? = null

    private fun updateMetadata(artist: String, song: String) {
        // Cancel any pending start fetch from previous track
        fetchJob?.cancel()

        this.artist = artist
        this.song = song
        
        Log.d("MetadataPolling", "Updating metadata: $artist - $song")
        
        // Reset current art to placeholder IMMEDIATELY to prevent STALE art
        // This must happen before MediaSession updates or Notification invalidation
        currentAlbumArt = getPlaceholderBitmap()
        
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, song)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, getStreamDisplayName())
            
        // Explicitly set the album art to buffer/placeholder to FORCE system to clear old art
        if (currentAlbumArt != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentAlbumArt)
        }
            
        val metadata = metadataBuilder.build()
        
        // Update MediaSession metadata immediately (without art)
        // BLOCK FOR TV: Prevent ANY metadata from being sent to system to avoid Mini-Player display/crashes
        if (!isTvDevice()) {
            mediaSession?.setMetadata(metadata)
        }
        
        // Also update playback state to "wake up" Android TV Media Hub
        updatePlaybackState(exoPlayer.isPlaying)

        // Update notification
        playerNotificationManager?.invalidate()
        
        // Broadcast to UI
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("metadata_update").apply {
                putExtra("artist", artist)
                putExtra("song", song)
                putExtra("stream", stream)
            }
        )
        
        // Async: Fetch and set album art from Spotify
        // We capture currentArtist/currentSong to avoid race conditions
        val currentArtist = artist
        val currentSong = song
        
        fetchJob = serviceScope.launch {
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
                        
                        // Send broadcast with album art URL for UI
                        LocalBroadcastManager.getInstance(this@MediaPlayerService).sendBroadcast(
                            Intent("metadata_update").apply {
                                putExtra("artist", artist)
                                putExtra("song", song)
                                putExtra("stream", stream)
                                putExtra("albumArtUrl", albumArtUrl ?: "NO_IMAGE")
                            }
                        )
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
        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        
        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, 0, 1.0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP
            )
            .build()
        
        mediaSession?.setPlaybackState(playbackState)
    }
    
    private fun getStreamDisplayName(): String {
        return when(stream) {
            "myata" -> "Radio Myata"
            "gold" -> "Radio Myata Gold"
            "myata_hits" -> "Radio Myata XTRA"
            else -> "Radio Myata"
        }
    }
    
    // ============== SPOTIFY API FOR ALBUM ART ==============
    
    // Removed getSpotifyAccessToken as iTunes API does not require authentication

    private suspend fun fetchAlbumArtUrl(artist: String, track: String): String? {
        val cleanArtist = getCleanArtistName(artist)
        // Remove (...) and [...] content, and standalone "RMX"/"REMIX"
        val cleanTrack = track
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
            .replace(Regex("(?i)\\b(RMX|REMIX)\\b"), "")
            .trim()
        
        var resultUrl: String? = null
        
        try {
            // Stage 1: Clean Artist (replace & with space) + Clean Track
            // Replacing & with space allows matching "Artist A & Artist B" with "Artist A featuring Artist B"
            val queryArtist = cleanArtist.replace("&", " ")
            val query1 = "$queryArtist $cleanTrack"
            Log.d("iTunes", "Service: Stage 1: $query1")
            
            resultUrl = executeItunesSearch(query1, cleanArtist, cleanTrack)
            
            if (resultUrl == null) {
                // Stage 2: Full Artist + Clean Track
                if (artist != cleanArtist) {
                    val query2 = "$artist $cleanTrack"
                    Log.d("iTunes", "Service: Stage 2: $query2")
                    // Use FULL artist for validation to enable fuzzy matching overlap
                    resultUrl = executeItunesSearch(query2, artist, cleanTrack)
                }
            }
            
            if (resultUrl == null) {
                // Stage 3: Track Name Only (Fallback for compilations or strictly mismatched artists)
                // Use with caution: require longer track name to avoid noise
                if (cleanTrack.length >= 4) {
                    val query3 = cleanTrack
                    Log.d("iTunes", "Service: Stage 3 (Track only): $query3")
                    // Validate against FULL artist
                    resultUrl = executeItunesSearch(query3, artist, cleanTrack)
                }
            }
            
        } catch (e: Exception) {
            Log.e("iTunes", "Service: Search error", e)
        }
        
        // DEEZER FALLBACK (Artist Image)
        if (resultUrl == null) {
            Log.d("Service_Art", "No album art found, trying Deezer for Artist Image...")
            resultUrl = fetchArtistImageFromDeezer(cleanArtist)
            if (resultUrl != null) {
                Log.d("Service_Art", "Found artist image via Deezer: $resultUrl")
            }
        }
        
        // LAST.FM FALLBACK (Scrape)
        if (resultUrl == null) {
             Log.d("Service_Art", "Deezer failed/blocked. Trying Last.fm scrape...")
             resultUrl = fetchArtistImageFromLastFm(cleanArtist)
             if (resultUrl != null) {
                 Log.d("Service_Art", "Found artist image via Last.fm: $resultUrl")
             }
        }
        
        return resultUrl
    }
    
    // Using simple HTTP request for Deezer to avoid adding SDK
    private fun fetchArtistImageFromDeezer(artist: String): String? {
        try {
            val encodedTerm = java.net.URLEncoder.encode(artist, "UTF-8")
            val url = "https://api.deezer.com/search/artist?q=$encodedTerm"
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MyataRadio/1.0") // Good practice
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                
                val bodyContent = response.body?.string() ?: return null
                val json = Gson().fromJson(bodyContent, Map::class.java)
                val data = json["data"] as? List<Map<String, Any>>
                
                if (!data.isNullOrEmpty()) {
                    val simpleExpected = simplifyString(artist)
                    
                    for (item in data) {
                        val name = item["name"] as? String ?: continue
                        if (simplifyString(name) == simpleExpected) {
                            // Return biggest picture BUT verify it's not a placeholder
                            val pic = item["picture_xl"] as? String ?: item["picture_big"] as? String
                            
                            // Deezer placeholder URL usually has double slash: .../images/artist//...
                            if (pic != null && !pic.contains("/artist//")) {
                                return pic
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Deezer_Search", "Service Error: ${e.message}")
        }
        return null
    }

    private fun fetchArtistImageFromLastFm(artist: String): String? {
        try {
            val finalArtist = java.net.URLEncoder.encode(artist, "UTF-8")
            val url = "https://www.last.fm/music/$finalArtist"
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
                
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null
                
                val regex = Regex("property=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']|content=[\"']([^\"']+)[\"']\\s+property=[\"']og:image[\"']")
                val match = regex.find(html)
                
                if (match != null) {
                   val url1 = match.groups[1]?.value
                   val url2 = match.groups[2]?.value
                   val result = url1 ?: url2
                   
                   if (result != null && !result.contains("default_artist") && !result.contains("star_")) {
                       return result
                   }
                }
            }
        } catch (e: Exception) {
            Log.e("Service_LastFM", "Error: ${e.message}")
        }
        return null
    }



    private fun isFuzzyMatch(text1: String, text2: String): Boolean {
        // Stop words (english + common music connectors)
        val stopWords = setOf("the", "a", "an", "and", "or", "of", "feat", "ft", "vs", "featuring", "presents", "pres", "with", "&")
        
        fun getTokens(text: String): Set<String> {
             return text.lowercase()
                .split(Regex("[\\s\\p{Punct}]+")) // Split by whitespace and punctuation
                .filter { it.length > 1 && !stopWords.contains(it) }
                .toSet()
        }

        val tokens1 = getTokens(text1)
        val tokens2 = getTokens(text2)

        if (tokens1.isEmpty() || tokens2.isEmpty()) return false

        val intersection = tokens1.intersect(tokens2)
        
        val ratio1 = intersection.size.toDouble() / tokens1.size
        val ratio2 = intersection.size.toDouble() / tokens2.size
        
        // Threshold 0.66 implies 2/3 match, or 100% if distinct tokens are small
        return ratio1 >= 0.66 || ratio2 >= 0.66
    }

    private fun executeItunesSearch(term: String, expectedArtist: String, expectedTrack: String): String? {
        val encodedTerm = java.net.URLEncoder.encode(term, "UTF-8")
        // Increase limit to 20 to find tracks hidden in compilations
        val url = "https://itunes.apple.com/search?term=$encodedTerm&media=music&entity=song&limit=20"
        
        val request = Request.Builder().url(url).build()
        
        return httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val bodyContent = response.body?.string() ?: return@use null
                val json = Gson().fromJson(bodyContent, Map::class.java)
                val results = json["results"] as? List<Map<String, Any>>
                
                if (!results.isNullOrEmpty()) {
                    val simpleExpectedArtist = simplifyString(expectedArtist)
                    val simpleExpectedTrack = simplifyString(expectedTrack)
                    
                    val validMatches = mutableListOf<Map<String, Any>>()
                    
                    for (item in results) {
                        val trackName = item["trackName"] as? String // Using "trackName" property from iTunes JSON
                        val artistName = item["artistName"] as? String
                        val artworkUrl = item["artworkUrl100"] as? String 
                        
                        if (trackName != null && artistName != null && artworkUrl != null) {
                             // Validation
                            val simpleArtistName = simplifyString(artistName)
                            val simpleTrackName = simplifyString(trackName)
                            
                            // IMPROVED VALIDATION: Fuzzy Token Match
                            val matchArtist = isFuzzyMatch(simpleArtistName, simpleExpectedArtist) ||
                                              isWordMatch(simpleArtistName, simpleExpectedArtist) || 
                                              isWordMatch(simpleExpectedArtist, simpleArtistName) ||
                                              isWordMatch(simpleTrackName, simpleExpectedArtist)
                                              
                            val matchTrack = isWordMatch(simpleTrackName, simpleExpectedTrack)
                            
                            if (matchArtist && matchTrack) {
                                validMatches.add(item)
                            }
                        }
                    }
                    
                    if (validMatches.isNotEmpty()) {
                        // Priority Sort
                        val bestMatch = validMatches.maxByOrNull { item ->
                            val collectionName = item["collectionName"] as? String ?: ""
                            calculateAlbumPriority(collectionName)
                        }
                        return@use bestMatch?.get("artworkUrl100")?.toString()?.replace("100x100bb", "600x600bb")
                    }
                    null
                } else null
            } else null
        }
    }

    private fun calculateAlbumPriority(collectionName: String): Int {
        val lowerName = collectionName.lowercase()
        if (lowerName.contains("greatest hits") || 
            lowerName.contains("best of") || 
            lowerName.contains("essential") || 
            lowerName.contains("anthology") ||
            lowerName.contains("collection") || 
            lowerName.contains("compilation")) {
            return 0 
        }
        return 1 
    }

    private fun isWordMatch(text: String, word: String): Boolean {
        if (word.isEmpty()) return true
        return try {
             val pattern = "\\b${java.util.regex.Pattern.quote(word)}\\b".toRegex()
             pattern.containsMatchIn(text)
        } catch (e: Exception) {
             text.contains(word)
        }
    }

    private fun simplifyString(input: String): String {
        val nfd = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
        val pattern = java.util.regex.Pattern.compile("\\p{InCombiningDiacriticalMarks}+")
        var clean = pattern.matcher(nfd).replaceAll("")
        
        clean = clean.replace("Ø", "O", ignoreCase = true)
                     .replace("ø", "o", ignoreCase = true)
                     .replace("Æ", "AE", ignoreCase = true)
                     .replace("æ", "ae", ignoreCase = true)
        
        // Remove connectors
        val connectors = Regex("(?i)\\b(feat\\.|ft\\.|vs\\.|feat|ft|vs|and|featuring|presents|pres\\.)\\b|&")
        clean = connectors.replace(clean, " ")
        
        clean = clean.replace(Regex("[^a-zA-Z0-9]"), " ").lowercase()
        return clean.replace(Regex("\\s+"), " ").trim()
    }
    
    private suspend fun loadAlbumArtBitmap(imageUrl: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                // Using shared httpClient to avoid Picasso's potential SSL issues
                val request = Request.Builder().url(imageUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("Spotify", "Service: Failed to download bitmap: ${response.code}")
                        return@use null
                    }
                    val bytes = response.body?.bytes() ?: return@use null
                    
                    // Decode bounds only first
                    val options = android.graphics.BitmapFactory.Options()
                    options.inJustDecodeBounds = true
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    
                    // Calculate inSampleSize
                    val reqWidth = 300
                    val reqHeight = 300
                    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
                    
                    // Decode with scaling
                    options.inJustDecodeBounds = false
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }
            } catch (e: Exception) {
                Log.e("Spotify", "Service: Error loading bitmap: ${e.message}")
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
    
    private fun getCleanArtistName(artist: String): String {
        // Remove feat./ft./vs. parts and secondary artists (after comma)
        // BUT keep & as part of band name (e.g., "FITZ & THE TANTRUMS")
        return artist
            .split(Regex("[,]|feat\\.|ft\\.|vs\\.|Feat\\.|Ft\\.|Vs\\.|FT\\.|FEAT\\.|VS\\.|featuring|Featuring", RegexOption.IGNORE_CASE))[0]
            .replace(Regex("\\(.*?\\)"), "")
            .trim()
    }
    
    private fun updateMediaSessionWithArt(bitmap: Bitmap?, currentArtist: String, currentSong: String) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentSong)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentSong)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, getStreamDisplayName())
        
        // Optimize for IPC limits (TransactionTooLargeException)
        // 1. Notification gets the full 300x300 bitmap (via currentAlbumArt)
        currentAlbumArt = bitmap

        // 2. MediaSession (Bluetooth/Lockscreen) gets a smaller scaled version
        // This is crucial because MediaMetadata is sent to all controllers
        // BUT for TV devices, we skip this to prevent any "Mini Player" like crashes/artifacts if requested
        if (bitmap != null && !isTvDevice()) {
            try {
                // Scale down to 144x144 for metadata - sufficient for car displays/watches
                val scaledForMetadata = Bitmap.createScaledBitmap(bitmap, 144, 144, true)
                metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, scaledForMetadata)
                // Do NOT set ALBUM_ART key to save space (ART is preferred by modern Android)
                // metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, scaled)
            } catch (e: Exception) {
                Log.w("MediaPlayerService", "Failed to scale bitmap for metadata: ${e.message}")
                // Fallback: don't set bitmap in metadata if scaling fails
            }
        }
        
        // BLOCK FOR TV: Prevent ANY metadata from being sent to system
        if (!isTvDevice()) {
            mediaSession?.setMetadata(metadata.build())
        }
        
        // Update notification
        playerNotificationManager?.invalidate()
    }
}