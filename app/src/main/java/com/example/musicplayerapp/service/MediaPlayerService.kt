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


// Media3 marks most of ExoPlayer's configuration surface (LoadControl, DataSource
// factories, PlayerNotificationManager, ForwardingPlayer command sets) @UnstableApi.
// Media3 1.7 promotes using them without opt-in to a lint error, so declare it once
// for the whole service rather than annotating each call site.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
    
    // Cleartext fallback for legacy TV/projector devices whose TLS stack cannot
    // complete the handshake at all. Scoped to ONE recovery episode: any explicit
    // user Play or stream switch starts from HTTPS again. It is never a session-wide
    // state, and on phones it is never used at all (issue #16).
    private var useHttpFallback = false

    // Геттеры: HTTPS по умолчанию, HTTP как fallback на TV
    val myataItem: MediaItem get() = if (useHttpFallback) myataItemHttp else myataItemHttps
    val xtraItem: MediaItem get() = if (useHttpFallback) xtraItemHttp else xtraItemHttps
    val goldItem: MediaItem get() = if (useHttpFallback) goldItemHttp else goldItemHttps

    // ============== RECOVERY STATE (issues #15, #16) ==============

    /**
     * Does the user currently want audio? Recovery only ever runs when this is
     * true, so an intentional pause/stop, an audio-focus loss or headphones being
     * unplugged can never be undone by an automatic reconnect.
     */
    private var userWantsPlayback = false

    /** Consecutive failed attempts in the current episode; drives the backoff. */
    private var recoveryAttempt = 0

    /** At most one retry may be in flight. */
    private var pendingRetry: Runnable? = null
    private val retryHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Parked because there is no network; resumed by the connectivity callback. */
    private var waitingForNetwork = false

    /** When the current uninterrupted playback started, for the stability reset. */
    private var playingSinceMs = 0L

    /** How long the last uninterrupted stretch of playback lasted. */
    private var lastPlaybackRunMs = 0L

    var song: String = ""
    var artist: String = ""
    var stream: String = ""

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent == null) {
            // START_STICKY handed the service back to us without the original
            // intent: the process was killed and restarted rather than started.
            PlaybackLog.problem("SERVICE_RESTARTED_BY_SYSTEM", "startId" to startId, "flags" to flags)
        } else {
            // Media3 keeps the service alive with its own action-less start
            // commands during normal playback; those carry no information and
            // would drown out the interesting lines, so only ours are logged.
            val requestedAction = intent.getStringExtra("ACTION")
            if (requestedAction != null) {
                PlaybackLog.event(
                    "START_COMMAND",
                    "action" to requestedAction,
                    "intentStream" to (intent.getStringExtra("STREAM") ?: "none"),
                    "forcePlay" to intent.getBooleanExtra("force_play", false),
                    "foregroundStart" to intent.getBooleanExtra("FOREGROUND_START", false),
                    "currentStream" to (stream.ifEmpty { "none" }),
                    "startId" to startId
                )
            }
        }

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
                // NotificationCompat, not Notification.Builder: the platform builder
                // that takes a channel id requires API 26, and minSdk here is 24.
                val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Radio Myata")
                    .setContentText("Загрузка...")
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
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
                        PlaybackLog.event("PLAYER_STOP", "source" to "intent", "reason" to "startStop_toggle_off")
                        onPlaybackNoLongerWanted("startStop_toggle_off")
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
                        onUserWantsPlayback("startStop_toggle_on")
                        ensureValidStream("startStop")
                        // Always set MediaItem (it may have been cleared by stop)
                        when(stream){
                            "myata"->{exoPlayer.setMediaItem(myataItem)}
                            "gold"->{exoPlayer.setMediaItem(goldItem)}
                            "myata_hits"->{exoPlayer.setMediaItem(xtraItem)}
                        }
                        logStreamSelection("startStop")

                        // Use updateMetadata to ensure art is reset, fetched, and notification updated
                        val startSong = intent.getStringExtra("SONG") ?: ""
                        val startArtist = intent.getStringExtra("ARTIST") ?: ""
                        updateMetadata(startArtist, startSong)

                        if (canPrepare("startStop")) {
                            PlaybackLog.event("PLAYER_PREPARE", "source" to "intent", "reason" to "startStop_toggle_on")
                            exoPlayer.prepare()
                            PlaybackLog.event("PLAYER_PLAY", "source" to "intent", "reason" to "startStop_toggle_on")
                            exoPlayer.play()
                        }
                    }
                }
                "play"->{
                    onUserWantsPlayback("play_action")
                    val intentStream = intent.getStringExtra("STREAM")
                    if (intentStream != null && stream != intentStream)
                    {
                        stream = intentStream
                        ensureValidStream("play_streamChange")
                        when(stream){
                            "myata"->{exoPlayer.setMediaItem(myataItem)}
                            "gold"->{exoPlayer.setMediaItem(goldItem)}
                            "myata_hits"->{exoPlayer.setMediaItem(xtraItem)}
                        }
                        logStreamSelection("play_streamChange")
                        if (canPrepare("play_streamChange")) {
                            PlaybackLog.event("PLAYER_PREPARE", "source" to "intent", "reason" to "play_streamChange")
                            exoPlayer.prepare()
                        }
                    }
                    if(!exoPlayer.isPlaying) {
                        // The player can be empty here after a stop cleared it.
                        if (exoPlayer.mediaItemCount == 0) {
                            ensureValidStream("play_notPlaying")
                            when(stream){
                                "myata"->{exoPlayer.setMediaItem(myataItem)}
                                "gold"->{exoPlayer.setMediaItem(goldItem)}
                                "myata_hits"->{exoPlayer.setMediaItem(xtraItem)}
                            }
                            logStreamSelection("play_notPlaying")
                        }
                        if (canPrepare("play_notPlaying")) {
                            PlaybackLog.event("PLAYER_PREPARE", "source" to "intent", "reason" to "play_notPlaying")
                            exoPlayer.prepare()
                            PlaybackLog.event("PLAYER_PLAY", "source" to "intent", "reason" to "play_notPlaying")
                            exoPlayer.play()
                        }
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
                        onUserWantsPlayback("stream_switch")
                        ensureValidStream("switch_streamChange")
                        
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
                        logStreamSelection("switch_streamChange")
                        currentAlbumArt = null
                        updateMetadata(switchArtist, switchSong)

                        // Always start playback for stream changes
                        if (canPrepare("switch_streamChange")) {
                            PlaybackLog.event("PLAYER_PREPARE", "source" to "intent", "reason" to "switch_streamChange")
                            exoPlayer.prepare()
                            PlaybackLog.event("PLAYER_PLAY", "source" to "intent", "reason" to "switch_streamChange")
                            exoPlayer.play()
                            Log.d("SWITCH", "Stream switched to $stream and playback started")
                        }
                    } else {
                        // SAME stream - only start if forcePlay requested AND not already playing
                        if (forcePlay && !exoPlayer.isPlaying) {
                            onUserWantsPlayback("switch_forcePlay")
                            // A previous stop clears the playlist; restore it first.
                            if (exoPlayer.mediaItemCount == 0) {
                                ensureValidStream("switch_forcePlay")
                                when(stream){
                                    "myata"->{exoPlayer.setMediaItem(myataItem)}
                                    "gold"->{exoPlayer.setMediaItem(goldItem)}
                                    "myata_hits"->{exoPlayer.setMediaItem(xtraItem)}
                                }
                                logStreamSelection("switch_forcePlay")
                            }
                            if (canPrepare("switch_forcePlay")) {
                                PlaybackLog.event("PLAYER_PREPARE", "source" to "intent", "reason" to "switch_forcePlay")
                                exoPlayer.prepare()
                                PlaybackLog.event("PLAYER_PLAY", "source" to "intent", "reason" to "switch_forcePlay")
                                exoPlayer.play()
                                Log.d("SWITCH", "Same stream $stream - resuming playback")
                            }
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
                    PlaybackLog.event("PLAYER_STOP", "source" to "intent", "reason" to "stop_action_shutdown")
                    onPlaybackNoLongerWanted("stop_action")
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
        PlaybackLog.event("SERVICE_CREATE")
        registerNetworkLogging()

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
            // Pause when the active output goes away (Bluetooth drops, headphones
            // unplugged). Without this the system simply re-routes to the phone
            // speaker and the radio keeps playing out loud - issue #13. Audio focus
            // handling above does NOT cover this: focus is about other apps wanting
            // the output, this is about the output disappearing.
            //
            // ExoPlayer's own AudioBecomingNoisyManager clears playWhenReady with
            // reason PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY. It never
            // auto-resumes, and it does not route through the MediaSession, so the
            // ForwardingPlayer's pause()-as-stop() is not triggered and the buffer
            // is kept: the user resumes with a single Play press.
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK) // Prevent CPU sleep
            .build().apply {
            addListener(object: Player.Listener{
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    super.onIsPlayingChanged(isPlaying)

                    PlaybackLog.event(
                        "IS_PLAYING_CHANGED",
                        "isPlaying" to isPlaying,
                        "state" to PlaybackLog.stateName(this@apply.playbackState),
                        "stream" to (stream.ifEmpty { "none" })
                    )

                    val action = if(isPlaying) "play" else "pause"
                    LocalBroadcastManager.getInstance(this@MediaPlayerService)
                        .sendBroadcast(Intent(action))
                    
                    // Update MediaSession playback state
                    updatePlaybackState(isPlaying)
                    
                    // Start/stop metadata polling based on playback state
                    // AND manage WakeLock
                    if (isPlaying) {
                        // Track how long each uninterrupted run lasts; a long healthy
                        // run is what resets the recovery budget, not STATE_READY.
                        playingSinceMs = android.os.SystemClock.elapsedRealtime()
                        startMetadataPolling()
                        if (wakeLock?.isHeld == false) {
                            wakeLock?.acquire()
                            Log.d("MediaPlayerService", "WakeLock acquired")
                            PlaybackLog.event("WAKELOCK_ACQUIRED")
                        }
                    } else {
                        if (playingSinceMs > 0L) {
                            lastPlaybackRunMs = android.os.SystemClock.elapsedRealtime() - playingSinceMs
                            playingSinceMs = 0L
                        }
                        stopMetadataPolling()

                        if (wakeLock?.isHeld == true) {
                            wakeLock?.release()
                            Log.d("MediaPlayerService", "WakeLock released")
                            PlaybackLog.event("WAKELOCK_RELEASED")
                        }
                    }
                }
                
                /**
                 * Diagnostics only. This is the callback that distinguishes a user
                 * pause from audio-focus loss from AUDIO_BECOMING_NOISY — the app
                 * never read it before, which is why issue #15 has no evidence.
                 */
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    super.onPlayWhenReadyChanged(playWhenReady, reason)
                    PlaybackLog.event(
                        "PLAY_WHEN_READY_CHANGED",
                        "playWhenReady" to playWhenReady,
                        "reason" to PlaybackLog.playWhenReadyReason(reason),
                        "stream" to (stream.ifEmpty { "none" })
                    )

                    // Headphones pulled out or another app took audio focus: the user
                    // has to press Play again. Recovery must not undo that (#13).
                    if (!playWhenReady &&
                        (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ||
                                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
                    ) {
                        onPlaybackNoLongerWanted(PlaybackLog.playWhenReadyReason(reason))
                    }
                }

                /** Transient audio-focus loss shows up here rather than as a pause. */
                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                    super.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
                    PlaybackLog.event(
                        "PLAYBACK_SUPPRESSION_CHANGED",
                        "reason" to PlaybackLog.suppressionReason(playbackSuppressionReason)
                    )
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)

                    PlaybackLog.event(
                        "STATE_CHANGED",
                        "state" to PlaybackLog.stateName(playbackState),
                        "playWhenReady" to this@apply.playWhenReady,
                        "stream" to (stream.ifEmpty { "none" })
                    )

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
                        Player.STATE_ENDED -> {
                            // A live radio stream has no end. Reaching ENDED while the
                            // user still wants audio means the server closed the
                            // connection, so treat it as a disconnect (issue #15).
                            if (userWantsPlayback) {
                                PlaybackLog.problem(
                                    "LIVE_STREAM_ENDED", "stream" to (stream.ifEmpty { "none" }),
                                    "interpretation" to "server_closed_connection"
                                )
                                startRecovery("state_ended", tlsFailure = false)
                            } else {
                                PlaybackLog.event("STATE_ENDED_IGNORED", "reason" to "user_does_not_want_playback")
                            }
                        }
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    super.onPlayerError(error)
                    Log.e("MediaPlayerService", "Player Error: ${error.errorCodeName} (${error.errorCode})")

                    PlaybackLog.problem(
                        "PLAYER_ERROR",
                        *PlaybackLog.describe(error),
                        "stream" to (stream.ifEmpty { "none" }),
                        "transport" to (if (useHttpFallback) "http" else "https"),
                        "state" to PlaybackLog.stateName(this@apply.playbackState)
                    )

                    val recoverable = StreamErrorPolicy.isRecoverable(error)
                    val tlsFailure = StreamErrorPolicy.isTlsFailure(error)

                    if (recoverable) {
                        startRecovery("player_error:${error.errorCodeName}", tlsFailure)
                    } else {
                        PlaybackLog.problem(
                            "ERROR_NOT_RETRIED", "errorCodeName" to error.errorCodeName,
                            "reason" to "not_recoverable", "outcome" to "playback_stopped"
                        )
                        LocalBroadcastManager.getInstance(this@MediaPlayerService).sendBroadcast(Intent("pause"))
                    }
                }
            })
        }

        // Note: isTv is already initialized in onCreate()

        // Commands arriving here came through the MediaSession: the in-app button
        // (via MediaController), the Media3 notification, or a media button.
        val forwardingPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun pause() {
                // Unified behavior for all platforms (Mobile & TV):
                // Pause acts as Stop to clear buffer and ensure live edge on resume
                PlaybackLog.event(
                    "PLAYER_PAUSE", "source" to "session",
                    "reason" to "pause_as_stop", "state" to PlaybackLog.stateName(playbackState)
                )
                onPlaybackNoLongerWanted("user_pause")
                stop()
                Log.d("MediaPlayerService", "Pause action: Stream stopped (buffer cleared) for radio edge")
            }

            override fun stop() {
                PlaybackLog.event(
                    "PLAYER_STOP", "source" to "session",
                    "state" to PlaybackLog.stateName(playbackState)
                )
                super.stop()
            }

            override fun play() {
                PlaybackLog.event(
                    "PLAYER_PLAY", "source" to "session",
                    "state" to PlaybackLog.stateName(playbackState)
                )
                onUserWantsPlayback("session_play")
                // When resuming from pause, re-prepare to jump to live edge
                // This handles both STATE_READY (paused) and STATE_IDLE (stopped) cases
                if (playbackState == Player.STATE_READY && !playWhenReady) {
                    Log.d("MediaPlayerService", "Resuming from pause: Re-preparing for live edge")
                    PlaybackLog.event("PLAYER_PREPARE", "source" to "session", "reason" to "resume_from_pause")
                    prepare()
                } else if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                    Log.d("MediaPlayerService", "Resuming from stop: Re-preparing stream")
                    PlaybackLog.event("PLAYER_PREPARE", "source" to "session", "reason" to "resume_from_stop")
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
        PlaybackLog.event("TASK_REMOVED", "isPlaying" to exoPlayer.isPlaying, "outcome" to "playback_kept_alive")
        super.onTaskRemoved(rootIntent)
    }

    // ============== STREAM RECOVERY (issues #15, #16) ==============

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return true // Cannot tell - assume yes rather than refuse to try.
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Policy delay plus jitter, so many clients do not reconnect in lockstep. */
    private fun backoffDelayMs(attempt: Int): Long =
        StreamErrorPolicy.backoffDelayMs(attempt, RECOVERY_BASE_DELAY_MS, RECOVERY_MAX_DELAY_MS) +
                (0..250).random()

    /**
     * Called when the user explicitly asks for audio. Ends any recovery episode:
     * the budget starts fresh and the transport goes back to HTTPS.
     */
    private fun onUserWantsPlayback(reason: String) {
        userWantsPlayback = true
        cancelPendingRetry()
        waitingForNetwork = false
        if (recoveryAttempt != 0 || useHttpFallback) {
            PlaybackLog.event(
                "RECOVERY_RESET", "reason" to reason,
                "wasAttempt" to recoveryAttempt, "wasTransport" to (if (useHttpFallback) "http" else "https")
            )
        }
        recoveryAttempt = 0
        useHttpFallback = false
    }

    /** Called when the user - or the system on the user's behalf - stops playback. */
    private fun onPlaybackNoLongerWanted(reason: String) {
        if (userWantsPlayback) {
            PlaybackLog.event("USER_INTENT_CLEARED", "reason" to reason)
        }
        userWantsPlayback = false
        cancelPendingRetry()
        waitingForNetwork = false
        recoveryAttempt = 0
    }

    private fun cancelPendingRetry() {
        pendingRetry?.let {
            retryHandler.removeCallbacks(it)
            PlaybackLog.event("RECOVERY_CANCELLED", "reason" to "superseded_or_user_action")
        }
        pendingRetry = null
    }

    /**
     * Single entry point for both failure sources - onPlayerError and a live stream
     * reaching STATE_ENDED - so the two can never schedule two retries at once.
     */
    private fun startRecovery(trigger: String, tlsFailure: Boolean) {
        if (!userWantsPlayback) {
            PlaybackLog.event("RECOVERY_SKIPPED", "trigger" to trigger, "reason" to "user_does_not_want_playback")
            return
        }
        if (pendingRetry != null) {
            PlaybackLog.event("RECOVERY_SKIPPED", "trigger" to trigger, "reason" to "retry_already_pending")
            return
        }
        if (stream.isEmpty()) {
            PlaybackLog.problem("RECOVERY_SKIPPED", "trigger" to trigger, "reason" to "no_stream_selected")
            return
        }

        // A long, healthy run means this is a new problem, not a continuing one.
        if (lastPlaybackRunMs >= RECOVERY_STABILITY_RESET_MS && recoveryAttempt != 0) {
            PlaybackLog.event(
                "RECOVERY_RESET", "reason" to "stable_playback",
                "stableForMs" to lastPlaybackRunMs, "wasAttempt" to recoveryAttempt
            )
            recoveryAttempt = 0
        }

        if (!isNetworkAvailable()) {
            // Do not spend the budget on attempts that cannot possibly succeed.
            waitingForNetwork = true
            PlaybackLog.event(
                "RECOVERY_WAITING_FOR_NETWORK", "trigger" to trigger, "attempt" to recoveryAttempt
            )
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("buffering"))
            return
        }

        if (recoveryAttempt >= RECOVERY_MAX_ATTEMPTS) {
            PlaybackLog.problem(
                "RECOVERY_GAVE_UP", "trigger" to trigger, "attempts" to recoveryAttempt,
                "outcome" to "playback_stopped_until_user_acts"
            )
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("pause"))
            return
        }

        // TV/projector only, and only when TLS itself failed - see issue #16.
        if (tlsFailure && isTv && !useHttpFallback) {
            useHttpFallback = true
            PlaybackLog.problem(
                "TRANSPORT_FALLBACK", "from" to "https", "to" to "http",
                "reason" to "tls_failure_on_tv", "scope" to "current_episode_only"
            )
        }

        val attempt = recoveryAttempt
        val delay = backoffDelayMs(attempt)
        recoveryAttempt++

        PlaybackLog.event(
            "RECOVERY_SCHEDULED", "trigger" to trigger, "attempt" to (attempt + 1),
            "maxAttempts" to RECOVERY_MAX_ATTEMPTS, "delayMs" to delay,
            "transport" to (if (useHttpFallback) "http" else "https")
        )
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("buffering"))

        val task = Runnable {
            pendingRetry = null
            if (!userWantsPlayback) {
                PlaybackLog.event("RECOVERY_ABORTED", "reason" to "user_stopped_while_pending")
                return@Runnable
            }
            PlaybackLog.event(
                "RECOVERY_ATTEMPT", "attempt" to (attempt + 1),
                "transport" to (if (useHttpFallback) "http" else "https"), "stream" to stream
            )
            // Re-set the item so a transport change actually takes effect.
            when (stream) {
                "myata" -> exoPlayer.setMediaItem(myataItem)
                "gold" -> exoPlayer.setMediaItem(goldItem)
                "myata_hits" -> exoPlayer.setMediaItem(xtraItem)
            }
            exoPlayer.prepare()
            exoPlayer.play()
        }
        pendingRetry = task
        retryHandler.postDelayed(task, delay)
    }

    /** Resumes a parked recovery once, when connectivity actually comes back. */
    private fun onNetworkRegained() {
        if (!waitingForNetwork) return
        waitingForNetwork = false
        if (!userWantsPlayback) {
            PlaybackLog.event("RECOVERY_NOT_RESUMED", "reason" to "user_does_not_want_playback")
            return
        }
        PlaybackLog.event("RECOVERY_RESUMED_ON_NETWORK", "attempt" to recoveryAttempt)
        startRecovery("network_regained", tlsFailure = false)
    }

    // ============== DIAGNOSTICS (logging only, no playback behaviour) ==============

    /**
     * Records which stream/transport the player was actually pointed at. The
     * `when(stream)` blocks above have no else branch, so an unrecognised key
     * silently leaves the player with no media item — worth a loud line.
     */
    /**
     * Resolves [stream] to a usable key, falling back to the default rather than
     * leaving the player with no media item. Returns true if a valid stream is set.
     */
    private fun ensureValidStream(where: String): Boolean {
        val normalised = com.example.musicplayerapp.data.Streams.normalise(stream)
        if (normalised == null) {
            PlaybackLog.problem(
                "STREAM_FALLBACK_APPLIED", "invalid" to (stream.ifEmpty { "<empty>" }),
                "usedInstead" to com.example.musicplayerapp.data.Streams.DEFAULT, "at" to where
            )
            stream = com.example.musicplayerapp.data.Streams.DEFAULT
        } else if (normalised != stream) {
            PlaybackLog.event("STREAM_NORMALISED", "from" to stream, "to" to normalised, "at" to where)
            stream = normalised
        }
        return true
    }

    /**
     * prepare() on an empty playlist is a silent no-op that looks exactly like a
     * dead Play button, so refuse it loudly instead (issue #14).
     */
    private fun canPrepare(where: String): Boolean {
        if (exoPlayer.mediaItemCount == 0) {
            PlaybackLog.problem(
                "PREPARE_REFUSED", "at" to where, "reason" to "no_media_item",
                "stream" to (stream.ifEmpty { "<empty>" })
            )
            return false
        }
        return true
    }

    private fun logStreamSelection(where: String) {
        val known = com.example.musicplayerapp.data.Streams.isKnown(stream)
        if (known) {
            PlaybackLog.event(
                "MEDIA_ITEM_SET", "stream" to stream, "at" to where,
                "transport" to (if (useHttpFallback) "http" else "https"),
                "mediaItemCount" to exoPlayer.mediaItemCount
            )
        } else {
            PlaybackLog.problem(
                "STREAM_UNRECOGNISED", "stream" to (stream.ifEmpty { "<empty>" }), "at" to where,
                "outcome" to "no_media_item_set", "mediaItemCount" to exoPlayer.mediaItemCount
            )
        }
    }

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /** Log-only: records connectivity transitions so they can be lined up with player events. */
    private fun registerNetworkLogging() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                PlaybackLog.event("NETWORK_AVAILABLE")
                // Callbacks arrive off the main thread; recovery touches the player.
                retryHandler.post { onNetworkRegained() }
            }

            override fun onLost(network: android.net.Network) {
                PlaybackLog.problem("NETWORK_LOST")
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            PlaybackLog.problem("NETWORK_CALLBACK_UNAVAILABLE", "cause" to e.javaClass.simpleName)
        }
    }

    private fun unregisterNetworkLogging() {
        val callback = networkCallback ?: return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        try {
            cm?.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
        networkCallback = null
    }

    override fun onDestroy() {
        PlaybackLog.event(
            "SERVICE_DESTROY",
            "wasPlaying" to exoPlayer.isPlaying,
            "state" to PlaybackLog.stateName(exoPlayer.playbackState),
            "stream" to (stream.ifEmpty { "none" })
        )
        unregisterNetworkLogging()
        cancelPendingRetry()

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

    private companion object {
        /** First backoff step; doubles per attempt up to the cap. */
        const val RECOVERY_BASE_DELAY_MS = 1_000L
        const val RECOVERY_MAX_DELAY_MS = 30_000L

        /** Consecutive attempts before giving up: 1+2+4+8+16+30 is about a minute of trying. */
        const val RECOVERY_MAX_ATTEMPTS = 6

        /**
         * How long playback must run uninterrupted for the next failure to count as
         * a fresh problem. Resetting on STATE_READY instead would let a stream that
         * plays for two seconds and dies retry forever.
         */
        const val RECOVERY_STABILITY_RESET_MS = 60_000L
    }
}