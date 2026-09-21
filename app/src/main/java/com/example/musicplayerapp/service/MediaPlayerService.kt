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
import com.example.musicplayerapp.BuildConfig
import com.example.musicplayerapp.SecureNetModule
import com.example.musicplayerapp.MainActivity
import com.example.musicplayerapp.data.BootIdentity
import com.example.musicplayerapp.data.PlaybackIntentStore
import com.example.musicplayerapp.data.SleepTimerStore
import com.example.musicplayerapp.data.Streams
import com.example.musicplayerapp.data.lastfm.LastfmConfig
import com.example.musicplayerapp.data.lastfm.LastfmLink
import com.example.musicplayerapp.data.lastfm.PrefsLastfmSessionStore
import com.example.musicplayerapp.data.lastfm.nowplaying.NowPlayingSender
import com.example.musicplayerapp.data.lastfm.queue.ScrobbleQueue
import com.example.musicplayerapp.scrobble.FeedObservation
import com.example.musicplayerapp.scrobble.ScrobbleEmissions
import com.example.musicplayerapp.scrobble.ScrobbleGate
import com.example.musicplayerapp.scrobble.ScrobbleTracker
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerDuration
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerState
import com.google.gson.Gson
import com.squareup.picasso.Picasso
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import android.app.NotificationChannel
import android.app.NotificationManager


/**
 * A stream's media item, labelled with the stream key.
 *
 * The media id is the one part of a `MediaItem` that survives the session
 * boundary: Media3 strips `localConfiguration` - and with it the URI - from every
 * item it hands a `MediaController`, so a controller asking "which station is
 * this?" can read this and nothing else. That is what lets the UI take its answer
 * from the session rather than keeping a second copy of the selection.
 */
internal fun streamMediaItem(url: String, streamKey: String): MediaItem =
    MediaItem.Builder().setUri(url).setMediaId(streamKey).build()

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

    // G6b P4: decides when the track on air has been heard long enough to become a
    // Last.fm scrobble candidate. Fed only by the poller below and by
    // onIsPlayingChanged; it sends, queues and stores nothing. Main thread only.
    private val lastfmSessions by lazy { PrefsLastfmSessionStore(this) }
    private val scrobbleTracker by lazy {
        ScrobbleTracker(
            isEnabled = ::isScrobbleTrackingActive,
            // G6b P5: eligible candidates become durable rows, bound to the account
            // linked at emission. Written off the main thread on the queue's own
            // process-lifetime scope, so this service's destruction cannot cancel it.
            sink = ScrobbleQueue.forContext(this).sink(),
            // Shared by every service instance this process creates, so a candidate
            // already emitted stays emitted when the service is recreated.
            emissions = ScrobbleEmissions.process,
            log = { name, fields -> PlaybackLog.event(name, *fields) },
            // G6b P6b: Now Playing when a new airing opens while really playing.
            // The sender dedupes per account and airing, and is behind the write gate.
            onOccurrenceOpened = { NowPlayingSender.forContext(this).onOccurrenceOpened(it) },
        )
    }
    private val scrobbleHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val scrobbleCheck = Runnable {
        scheduleScrobbleCheck(scrobbleTracker.onTick(android.os.SystemClock.elapsedRealtime()))
    }
    
    // WakeLock to prevent sleep on Android TV
    private var wakeLock: PowerManager.WakeLock? = null
    
    // OkHttp client for API requests (full TLS validation, extra roots bundled)
    private val httpClient by lazy { SecureNetModule.getOkHttpClient(this) }
    
    // The application's one artwork resolver, shared with the ViewModels (G5c).
    // It used to be a third ArtworkRepository with a third private cache, so the
    // current track was looked up here as well as in the UI. Same answers, same
    // matching - only the ownership of the lookup and its cache changed.
    private val artworkResolver by lazy { com.example.musicplayerapp.data.ArtworkModule.resolver(this) }
    
    // Image cache for album art
    private val albumArtCache = mutableMapOf<String, Bitmap?>()
    private var currentAlbumArt: Bitmap? = null
    
    // Platform type for optimizations
    private var isTv: Boolean = false
    private var lastFetchedArtist: String? = null
    private var lastFetchedSong: String? = null
    private var currentAlbumArtUrl: String? = null
    // A cover lookup has finished (found or not) for lastFetchedArtist/lastFetchedSong.
    private var artworkSettled = false
    private var fetchJob: kotlinx.coroutines.Job? = null
    
    // HTTPS URLs (по умолчанию)
    val myataItemHttps = streamMediaItem("https://radio.dline-media.com/myata", Streams.MYATA)
    val xtraItemHttps = streamMediaItem("https://radio.dline-media.com/myata_hits", Streams.XTRA)
    val goldItemHttps = streamMediaItem("https://radio.dline-media.com/gold", Streams.GOLD)
    
    // HTTP URLs (fallback для проекторов с проблемами SSL)
    val myataItemHttp = streamMediaItem("http://radio.dline-media.com/myata", Streams.MYATA)
    val xtraItemHttp = streamMediaItem("http://radio.dline-media.com/myata_hits", Streams.XTRA)
    val goldItemHttp = streamMediaItem("http://radio.dline-media.com/gold", Streams.GOLD)
    
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

    /**
     * The reconnect episode in progress: how much of the fast budget is spent, and
     * whether the episode has moved on to its slow phase. The decisions live in
     * [RecoveryPolicy], which is where they are pinned by JVM tests - this is only
     * the service's copy of the answer.
     */
    private var recovery = RecoveryPolicy.Episode()

    /**
     * Whether this service instance has already acted on the durable playback
     * intent. A restarted service can be handed more than one start command, and
     * Media3 issues its own while playback runs - without this, a second one would
     * prepare and play on top of a player that is already going.
     */
    private var playbackIntentRestored = false

    /** At most one retry may be in flight, in either phase. */
    private var pendingRetry: Runnable? = null

    /** Which phase the armed attempt belongs to, so a cancellation can say which. */
    private var pendingRetryPhase: String? = null
    private val retryHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** When the current uninterrupted playback started, for the stability reset. */
    private var playingSinceMs = 0L

    // ============== SLEEP TIMER (G2) ==============

    /**
     * The armed timer, or null. This object is the authority: the store is its
     * durable copy and the UI is only ever told what it says.
     */
    private var sleepTimer: SleepTimerState.Armed? = null

    /** The scheduled expiry, on its own handler so nothing here can disturb recovery's. */
    private var sleepTimerRunnable: Runnable? = null
    private val sleepTimerHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * The most recent arming, counted. Every arm and every cancel bumps it, the
     * scheduled callback captures it, and the callback compares before doing
     * anything - so a replaced, cancelled or re-armed timer's outstanding
     * `Runnable` becomes a no-op rather than a second expiry.
     */
    private var sleepTimerGeneration = 0L

    /**
     * Whether the placeholder foreground notification is up - the one
     * [postPlaceholderForegroundNotification] posts to answer Android's
     * `startForegroundService` contract. Tracked so that a pass which could not deliver
     * its command can take it down again rather than leave the app foreground forever;
     * see [releasePlaceholderForeground].
     */
    private var placeholderForegroundUp = false

    var song: String = ""
    var artist: String = ""
    var stream: String = ""

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Everything the app itself asked for is read from the app's own durable
        // inbox rather than out of `intent`, and this whole path reads no extra.
        // That is the fix for the exported endpoint - any app may send this
        // component a start, and a start carries no instruction any more - and it is
        // also what carries a command across a process death, which process memory
        // did not: a start request survives a kill, so the command has to. See
        // [PlaybackCommand] and [PlaybackCommandInbox].
        val inbox = PlaybackCommandInbox.forContext(this)
        val commands = inbox.pending()

        if (intent == null) {
            // START_STICKY handed the service back to us without the original
            // intent: the process was killed and restarted rather than started.
            // Everything the listener had told us died with the old process, so this
            // is the one point where the durable copies have to speak for them - the
            // inbox read above, and the playback intent at the end of this method.
            PlaybackLog.problem(
                "SERVICE_RESTARTED_BY_SYSTEM",
                "startId" to startId, "flags" to flags, "pending" to commands.size
            )
        }

        // Media3 keeps the service alive with its own action-less start commands
        // during normal playback; those carry no command and would drown out the
        // interesting lines, so only ours are logged.
        for (entry in commands) {
            PlaybackLog.event(
                "START_COMMAND",
                "action" to entry.command.action,
                "id" to entry.id,
                "intentStream" to (entry.command.stream ?: "none"),
                "forcePlay" to entry.command.forcePlay,
                "foregroundStart" to entry.command.openForeground,
                "currentStream" to (stream.ifEmpty { "none" }),
                "startId" to startId
            )
        }

        // One command at a time, oldest first; the inbox owns the order, the
        // acknowledgement and what a failed handler means. What stays here is the one
        // per-command answer the service itself owns: whether this start should keep
        // the service sticky - and the platform obligation that belongs to a command,
        // answered by [prepareHeadCommand] before that command's handler and never
        // before an older one's.
        var keepSticky = true
        inbox.drain(prepare = ::prepareHeadCommand) { entry ->
            keepSticky = keepSticky && handleOneCommand(entry)
        }

        // Whatever the pass did not get through. A pass that emptied the inbox left
        // nothing here; anything still present is a command that was *not* delivered -
        // it is newer than the durable playback state below and it is what the listener
        // asked for since, so the two lines after this one are ordered by it.
        val stillPending = inbox.pending()

        if (intent == null) {
            if (stillPending.isEmpty()) {
                // The null-intent restart, on the durable state the pass above has just
                // updated: a pending Stop has already been applied, a pending switch has
                // already moved the station, and what is read here is the result rather
                // than the state the process died on. A kill between a gesture and the
                // service handling it can therefore never resurrect what the gesture
                // replaced.
                restorePlaybackIntent("sticky_restart")
            } else {
                // A command the pass could not deliver is still the listener's latest
                // word on the subject, and the durable playback state is the older one.
                // Restoring now would put older intent over newer intent - the exact
                // resurrection this ordering exists to prevent - so it waits for the
                // start that finally delivers the command.
                PlaybackLog.event(
                    "PLAYBACK_INTENT_DEFERRED", "at" to "sticky_restart",
                    "reason" to "pending_commands", "pending" to stillPending.size,
                )
            }
        }

        if (stillPending.isNotEmpty()) {
            releasePlaceholderForeground("commands_left_pending")
        }

        return if (keepSticky) START_STICKY else START_NOT_STICKY
    }

    /**
     * Runs one command from the durable inbox.
     *
     * Split out of `onStartCommand` so "one command" is a unit the start path can
     * acknowledge, retry or give up on, which is what the inbox's ordering and its
     * acknowledge-after-the-handler rule need. The whole [PlaybackCommandInbox.Entry]
     * rather than the command alone, because two of the sleep-timer commands are
     * identified by the record that carried them: a replayed cancel must not wipe the
     * snapshot it created, and a replayed undo must not consume a later one.
     *
     * The return value is the one thing the start path cannot work out for itself:
     * a `switch` that arrived without a station is the app's own answer that this
     * start should not be sticky. Everything else leaves the service sticky.
     */
    private fun handleOneCommand(entry: PlaybackCommandInbox.Entry): Boolean {
        val command = entry.command
        // A `switch` that arrived without a station is the one command that answers
        // its start with "do not keep me"; everything else leaves the service sticky.
        var keepSticky = true

        when(command.action){
            "startStop"->{
                // The desire is data here, not a question. `startStop` used to
                // be a toggle, and a toggle replayed after a process death does
                // the opposite of what the listener asked for - Play arriving
                // as Pause. [PlaybackCommand.of] resolves it where the listener
                // makes the gesture, to the one thing every call site in this
                // app means by it ("start playback"), so a second run of the
                // same command can only re-state the same desire.
                val wantPlaying = command.desiredPlaying ?: !exoPlayer.isPlaying
                if(!wantPlaying) {
                    PlaybackLog.event("PLAYER_STOP", "source" to "intent", "reason" to "startStop_toggle_off")
                    onPlaybackNoLongerWanted("startStop_toggle_off")
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    artist = ""
                    song = ""
                    updateMetadata("", "")
                }
                // Already playing is not a second start: the command says what
                // the listener wants, and that is what is already happening.
                else if(!exoPlayer.isPlaying){
                    val intentStream = command.stream
                    if (intentStream != null) {
                        stream = intentStream
                    }
                    // The station is assigned above, so the durable record
                    // this writes carries the one being asked for.
                    // onUserWantsPlayback canonicalises `stream` itself.
                    onUserWantsPlayback("startStop_toggle_on")
                    // Always set MediaItem (it may have been cleared by stop)
                    when(stream){
                        "myata"->{exoPlayer.setMediaItem(myataItem)}
                        "gold"->{exoPlayer.setMediaItem(goldItem)}
                        "myata_hits"->{exoPlayer.setMediaItem(xtraItem)}
                    }
                    logStreamSelection("startStop")

                    // Use updateMetadata to ensure art is reset, fetched, and notification updated
                    val startSong = command.song ?: ""
                    val startArtist = command.artist ?: ""
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
                val intentStream = command.stream
                val isStreamChange = intentStream != null && stream != intentStream
                // The station first, the intent second. onUserWantsPlayback is
                // what writes the durable record, and it has to carry the
                // station being asked for - not the one that was playing a
                // moment ago, which is what a process death would restore.
                if (isStreamChange) stream = intentStream!!
                onUserWantsPlayback("play_action")
                if (isStreamChange)
                {
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
                val intentStream = command.stream
                val forcePlay = command.forcePlay
                    
                // Without a station there is nothing to do with this command, and
                // it is the one action that leaves the service non-sticky - the
                // answer it has always given a station-less switch.
                if (intentStream == null) {
                    keepSticky = false
                }
                    
                val isStreamChange = stream != intentStream
                    
                if (intentStream != null && isStreamChange) {
                    // DIFFERENT stream - need to set up new media item
                    stream = intentStream
                    // A different station discards the partial listen (G6b P4, D1).
                    scheduleScrobbleCheck(
                        scrobbleTracker.onStreamSelected(stream, android.os.SystemClock.elapsedRealtime())
                    )
                    onUserWantsPlayback("stream_switch")
                        
                    val switchSong = command.song ?: ""
                    val switchArtist = command.artist ?: ""

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
                } else if (intentStream != null) {
                    // SAME stream - only start if forcePlay requested AND not already playing
                    if (forcePlay && !exoPlayer.isPlaying) {
                        onUserWantsPlayback("switch_forcePlay")
                        // A previous stop clears the playlist; restore it first.
                        if (exoPlayer.mediaItemCount == 0) {
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
                val newSong = command.song ?: ""
                val newArtist = command.artist ?: ""
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
            SleepTimerContract.ACTION_SET -> {
                // The deadline was resolved when the listener chose the
                // duration, so a command that outlived the process still stops
                // the radio at the time they asked for - see [PlaybackCommand] -
                // and it is checked against the boot it was measured on before it
                // is honoured.
                armSleepTimerFromCommand(command)
            }
            PlaybackIntentContract.ACTION_RESTORE -> {
                // The restart path, reachable. See PlaybackIntentContract for
                // why it exists and why a release build refuses it. The refusal
                // is the first thing here: nothing is read, nothing is written
                // and no field is touched before the policy has answered.
                if (!PlaybackIntentContract.isRestoreAllowed(BuildConfig.DEBUG)) {
                    PlaybackLog.problem(
                        "PLAYBACK_INTENT_RESTORE_REFUSED", "reason" to "not_a_debug_build"
                    )
                } else {
                    // A real sticky restart always lands on a brand new
                    // instance, where this is false. The simulation has to
                    // start from the same place or it would only ever be
                    // testing the already-restored guard.
                    playbackIntentRestored = false
                    restorePlaybackIntent("intent_restore")
                }
            }
            SystemPlaybackEventContract.ACTION_BECOMING_NOISY -> {
                // The audio-route path, reachable. See
                // SystemPlaybackEventContract for why it exists and why a release
                // build refuses it. The refusal is the first thing here: nothing
                // is read, nothing is written and no field is touched before the
                // policy has answered.
                if (!SystemPlaybackEventContract.isSimulationAllowed(BuildConfig.DEBUG)) {
                    PlaybackLog.problem(
                        "SYSTEM_EVENT_SIMULATION_REFUSED",
                        "event" to "audio_becoming_noisy", "reason" to "not_a_debug_build"
                    )
                } else {
                    simulateAudioBecomingNoisy()
                }
            }
            SleepTimerContract.ACTION_CANCEL -> cancelSleepTimer(entry.id)
            SleepTimerContract.ACTION_UNDO -> undoSleepTimerCancel(entry.id)
            SleepTimerContract.ACTION_SYNC -> {
                // Every read is a reconciliation. A Handler that was delayed
                // while nothing was playing, or a service that came back after
                // the deadline had passed, must not leave a dead timer looking
                // armed on a screen that has just been opened.
                reconcileSleepTimer("sync")
                broadcastSleepTimerState()
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
        return keepSticky
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
            // The stock renderers, plus a pass-through tap that publishes the
            // audio's loudness for the TV player's ambient field. It is the same
            // audio sink Media3 would have built, with one extra processor in
            // front of the chain; see AudioLevelRenderersFactory for why that is
            // safe and how it was checked against the pinned library.
            .setRenderersFactory(AudioLevelRenderersFactory(this))
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

                    // Only actual playing time counts toward a scrobble: buffering,
                    // pause, reconnect and focus suppression all arrive here as false.
                    scheduleScrobbleCheck(
                        scrobbleTracker.onPlaying(isPlaying, stream, android.os.SystemClock.elapsedRealtime())
                    )
                    
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
                            // How long that run lasted, handed to the episode: the next
                            // failure credits it once and spends it.
                            recovery = RecoveryPolicy.onPlaybackRunEnded(
                                episode = recovery,
                                runMs = android.os.SystemClock.elapsedRealtime() - playingSinceMs,
                            )
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

                    onSystemPlayWhenReadyChange(playWhenReady, reason)
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

                    // The same identity PlaybackLog has just written to logcat, kept
                    // in memory so `Сообщить о проблеме` can put it on the
                    // diagnostics card - logcat is where issue #15's evidence has
                    // been going to die. Observation only: nothing below reads it,
                    // no branch depends on it, and it holds a code and a timestamp
                    // rather than the exception. See LastPlaybackError.
                    LastPlaybackError.record(error)

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
                // A player with nothing in it is what a process death leaves
                // behind, and super.play() on an empty timeline is silent - the
                // notification Play button looked dead for exactly that reason.
                // Put the stream back first, so the prepare below has something
                // to prepare. Deliberately only the selection: the press itself
                // is the intent, and starting audio is what the rest of this does.
                if (mediaItemCount == 0) {
                    restoreStreamSelection("session_play")
                }
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

        // A timer that was running when this process died is re-adopted here, and
        // one that belongs to a previous boot or has already gone by is removed
        // here. START_STICKY brings the service back with a null intent, so this is
        // the only point that is guaranteed to run on that path.
        reconcileSleepTimer("service_create")
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

    /**
     * Called when the user explicitly asks for audio. Ends any recovery episode:
     * the budget starts fresh and the transport goes back to HTTPS.
     */
    private fun onUserWantsPlayback(reason: String) {
        // Canonicalise first: every caller has already assigned the station it is
        // about, so from here `stream` is the one the listener asked for.
        canonicaliseStream(reason)
        userWantsPlayback = true
        // The durable half of the same fact, and the station it belongs to, in one
        // editor. Two edits would leave an instant in which the record reads
        // "playback wanted" beside the *previous* station - and a process death in
        // that instant restores a radio nobody asked for. Written before the
        // prepare/play that follows every caller, so the kill cannot outrun it.
        PlaybackIntentStore.recordPlaybackWanted(this, stream)
        cancelPendingRetry("user_wants_playback")
        if (!recovery.untouched || useHttpFallback) {
            PlaybackLog.event(
                "RECOVERY_RESET", "reason" to reason,
                "wasAttempt" to recovery.fastAttempts, "wasDormant" to recovery.slow,
                "wasTransport" to (if (useHttpFallback) "http" else "https")
            )
        }
        recovery = RecoveryPolicy.onUserWantsPlayback()
        useHttpFallback = false
    }

    /** Called when the user - or the system on the user's behalf - stops playback. */
    private fun onPlaybackNoLongerWanted(reason: String) {
        // The durable write comes first, before the in-memory flag and before the
        // stop every caller performs next. Silence the listener asked for has to
        // outlive the process: a kill between the stop and the record would leave
        // a record that resurrects audio somebody had just ended. The station is
        // kept, so the notification's Play still knows which one it means.
        PlaybackIntentStore.recordPlaybackNotWanted(this)
        if (userWantsPlayback) {
            PlaybackLog.event("USER_INTENT_CLEARED", "reason" to reason)
        }
        userWantsPlayback = false
        cancelPendingRetry("intent_ended")
        recovery = RecoveryPolicy.onIntentEnded()
    }

    /**
     * Disarms the one deferred attempt, whatever phase it belongs to. Every explicit
     * stop ends here - pause, stop, a sleep-timer expiry, headphones out, audio focus
     * lost - which is what stops a slow retry from undoing a decision the listener
     * just made.
     */
    private fun cancelPendingRetry(reason: String) {
        pendingRetry?.let {
            retryHandler.removeCallbacks(it)
            PlaybackLog.event(
                "RECOVERY_CANCELLED",
                "reason" to reason, "phase" to (pendingRetryPhase ?: "none")
            )
        }
        pendingRetry = null
        pendingRetryPhase = null
    }

    /**
     * Single entry point for every failure - onPlayerError, a live stream reaching
     * STATE_ENDED, and the debug simulation seam - so none of them can schedule two
     * retries at once, and so what a failure means is decided in one place
     * ([RecoveryPolicy]).
     *
     * Three outcomes, and the third is the whole point of this slice: no connectivity
     * parks the episode (spending nothing), a budget with room left arms the next fast
     * attempt, and a budget that is spent arms one slow attempt minutes out instead of
     * ending the episode. None of them turns the listener's intent into permanent
     * silence.
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

        val wasDormant = recovery.slow
        val decision = RecoveryPolicy.onFailure(
            episode = recovery,
            networkAvailable = isNetworkAvailable(),
        )
        recovery = decision.episode

        // A long, healthy run means this is a new problem, not a continuing one - once.
        // The credit is spent by the failure that takes it, so the outage that follows a
        // healthy stretch cannot reset the budget it is already spending.
        decision.creditedRunMs?.let { creditedRunMs ->
            PlaybackLog.event(
                "RECOVERY_RESET", "reason" to "stable_playback",
                "stableForMs" to creditedRunMs, "wasDormant" to wasDormant
            )
        }

        when (val plan = decision.plan) {
            // Do not spend the budget on attempts that cannot possibly succeed.
            is RecoveryPolicy.Plan.WaitForNetwork -> {
                PlaybackLog.event(
                    "RECOVERY_WAITING_FOR_NETWORK", "trigger" to trigger,
                    "attempt" to recovery.fastAttempts, "dormant" to recovery.slow
                )
                LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("buffering"))
            }

            is RecoveryPolicy.Plan.FastRetry -> {
                // TV/projector only, and only when TLS itself failed - see issue #16.
                if (tlsFailure && isTv && !useHttpFallback) {
                    useHttpFallback = true
                    PlaybackLog.problem(
                        "TRANSPORT_FALLBACK", "from" to "https", "to" to "http",
                        "reason" to "tls_failure_on_tv", "scope" to "current_episode_only"
                    )
                }
                armRetry(trigger, plan.attempt, plan.delayMs, slow = false)
            }

            /**
             * The fast budget is spent and playback is already stopped; the listener
             * still wants the radio. Pause and keep exactly one attempt armed, minutes
             * out - the state the old code called giving up, now with a way back. The
             * "pause" broadcast is what the give-up path has always sent, and nothing
             * else changes for the UI: the attempt that succeeds reports itself the
             * normal way, through `onIsPlayingChanged`.
             */
            is RecoveryPolicy.Plan.SlowRetry -> {
                if (wasDormant) {
                    PlaybackLog.event(
                        "RECOVERY_DORMANT", "trigger" to trigger,
                        "attempts" to recovery.fastAttempts, "slowRetryInMs" to plan.delayMs
                    )
                } else {
                    PlaybackLog.problem(
                        "RECOVERY_GAVE_UP", "trigger" to trigger, "attempts" to recovery.fastAttempts,
                        "outcome" to "dormant_slow_retry",
                        "slowRetryInMs" to plan.delayMs, "wantsPlayback" to true
                    )
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("pause"))
                armRetry(trigger, recovery.fastAttempts, plan.delayMs, slow = true)
            }
        }
    }

    /**
     * The only place a retry is ever scheduled.
     *
     * One slot, [pendingRetry]: a second failure while an attempt is armed is
     * refused by [startRecovery] rather than queued, so there is never more than one
     * loop. The fast phase keeps its jitter (many clients, one server); the slow
     * phase does not need any - it fires when it fires, minutes out.
     */
    private fun armRetry(trigger: String, attempt: Int, delayMs: Long, slow: Boolean) {
        val phase = if (slow) "slow" else "fast"
        val delay = if (slow) delayMs else delayMs + (0..250).random()

        PlaybackLog.event(
            "RECOVERY_SCHEDULED", "trigger" to trigger, "attempt" to attempt,
            "maxAttempts" to RecoveryPolicy.MAX_FAST_ATTEMPTS, "delayMs" to delay,
            "phase" to phase, "transport" to (if (useHttpFallback) "http" else "https")
        )
        if (!slow) {
            // The slow phase announces nothing: the radio is paused for minutes, and
            // a buffering signal for an attempt that has not started would be a lie.
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("buffering"))
        }

        val task = Runnable {
            pendingRetry = null
            pendingRetryPhase = null
            if (!userWantsPlayback) {
                PlaybackLog.event("RECOVERY_ABORTED", "reason" to "user_stopped_while_pending")
                return@Runnable
            }
            PlaybackLog.event(
                "RECOVERY_ATTEMPT", "attempt" to attempt, "phase" to phase,
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
        pendingRetryPhase = phase
        retryHandler.postDelayed(task, delay)
    }

    /**
     * Resumes a parked recovery once, when connectivity actually comes back.
     *
     * Parked is the only state this wakes: an episode that is merely dormant (the
     * fast budget spent, connectivity fine, a slow attempt already armed) is paced
     * by that attempt, and letting a network event pull it forward as well would be
     * a second trigger for the same loop. A parked episode has no timer on purpose -
     * a timer cannot know when the network comes back - so this is its only way out,
     * in either phase.
     */
    private fun onNetworkRegained() {
        // Parked episodes only: one that is merely dormant has its own armed attempt,
        // and one the listener stopped was ended on the spot, so there is no
        // "waiting on a network nobody wants" state left to report.
        if (!recovery.awaitingNetwork) return
        PlaybackLog.event(
            "RECOVERY_RESUMED_ON_NETWORK",
            "attempt" to recovery.fastAttempts, "dormant" to recovery.slow
        )
        startRecovery("network_regained", tlsFailure = false)
    }

    /**
     * The system stopped the audio, and the listener has to ask again.
     *
     * Both reasons this acts on mean the *output* went away - headphones unplugged,
     * Bluetooth gone, another app taking the audio for good - so the episode is over
     * and the durable record says so: a restarting process must not put the radio
     * back on the phone speaker, and the reconnect loop must not re-open the stream
     * the listener has just lost the output for. Which reasons those are is pinned
     * by `SystemPlaybackStopPolicy`, and this is the only place they are acted on.
     */
    private fun onSystemPlayWhenReadyChange(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) return
        if (!SystemPlaybackStopPolicy.endsUserIntent(reason)) return
        onPlaybackNoLongerWanted(PlaybackLog.playWhenReadyReason(reason))
    }

    /**
     * The debug seam: deliver "the audio output went away" now.
     *
     * See [SystemPlaybackEventContract] for why the seam has to exist - the broadcast
     * is protected, so neither a test nor `adb shell` may send it, and an emulator
     * has no Bluetooth route to lose. This reproduces, in the two steps the platform
     * performs, what the system's broadcast does:
     *
     *  1. the player is silenced the way `AudioBecomingNoisyManager` silences it -
     *     `playWhenReady` cleared directly on the ExoPlayer, not through the session,
     *     so the buffer and the media item are kept and one Play press resumes;
     *  2. the reason that arrival carries is handed to the service's own listener
     *     handler, which is the code the real callback reaches.
     *
     * The one difference is deliberate and is the reason this is a debug-only seam:
     * step 2 is called directly rather than delivered by ExoPlayer, because the
     * broadcast that would deliver it cannot be sent by anything but the system.
     * Everything downstream of that call is the shipped path.
     */
    private fun simulateAudioBecomingNoisy() {
        PlaybackLog.problem("SYSTEM_EVENT_SIMULATED", "event" to "audio_becoming_noisy")
        exoPlayer.playWhenReady = false
        onSystemPlayWhenReadyChange(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        )
    }

    // ============== DURABLE PLAYBACK INTENT (process death) ==============
    //
    // Everything the listener had told this service - which station, and whether
    // they wanted it playing - lived in memory and in the intents that put it
    // there. A process death took all of it: START_STICKY brought the service
    // back with a null intent, no stream, no media item and userWantsPlayback
    // false, so the radio stayed silent and the notification Play button could
    // not fix it either. PlaybackIntentStore is the durable copy of those two
    // facts, and this section is the only thing that acts on it.

    /**
     * The null-intent START_STICKY restart: the system handed the service back
     * and nobody is watching.
     *
     * Audio starts only because the listener had already asked for it. A record
     * that says they had stopped restores the station and nothing else, and a
     * record that cannot be read produces silence rather than a guess - the wrong
     * station playing by itself in somebody pocket is worse than no station.
     */
    private fun restorePlaybackIntent(reason: String) {
        val decision = PlaybackIntentPolicy.onStickyRestart(
            stored = PlaybackIntentStore.read(this),
            alreadyRestored = playbackIntentRestored,
            playerHasMediaItem = exoPlayer.mediaItemCount > 0,
        )
        when (decision) {
            is PlaybackIntentPolicy.Restore.Skip -> PlaybackLog.event(
                "PLAYBACK_INTENT_SKIPPED", "at" to reason, "reason" to decision.reason
            )

            is PlaybackIntentPolicy.Restore.Discard -> {
                playbackIntentRestored = true
                PlaybackLog.problem(
                    "PLAYBACK_INTENT_DISCARDED", "at" to reason,
                    "invalid" to decision.rawStream, "outcome" to "nothing_restored"
                )
                PlaybackIntentStore.clear(this)
            }

            is PlaybackIntentPolicy.Restore.Adopt -> {
                playbackIntentRestored = true
                stream = decision.stream
                PlaybackLog.event(
                    "PLAYBACK_INTENT_ADOPTED", "at" to reason, "stream" to decision.stream,
                    "outcome" to "stream_only_playback_was_not_wanted"
                )
            }

            is PlaybackIntentPolicy.Restore.Resume -> {
                playbackIntentRestored = true
                PlaybackLog.event(
                    "PLAYBACK_INTENT_RESUMING", "at" to reason, "stream" to decision.stream
                )
                // The system restarted us with a plain start command, so nothing
                // has made this a foreground service yet and the five-second
                // startForeground contract of the normal Play path does not
                // apply. Post the placeholder anyway: Media3 replaces it the
                // moment playback begins, and until then a service that is about
                // to hold a wake lock should be visible.
                postPlaceholderForegroundNotification()
                stream = decision.stream
                // Ends any recovery episode and sets userWantsPlayback, which is
                // what the error and STATE_ENDED paths read: a stream restored
                // this way has to be as reconnectable as one the listener started.
                onUserWantsPlayback("sticky_restore")
                if (!installStreamMediaItem("sticky_restore")) return
                if (canPrepare("sticky_restore")) {
                    PlaybackLog.event("PLAYER_PREPARE", "source" to "restore", "reason" to reason)
                    exoPlayer.prepare()
                    PlaybackLog.event("PLAYER_PLAY", "source" to "restore", "reason" to reason)
                    exoPlayer.play()
                    // The wake lock and the metadata poller are not started here,
                    // and must not be: both hang off onIsPlayingChanged, so they
                    // come back when audio actually does, and a restore that never
                    // reaches the live edge leaves neither of them running.
                }
            }
        }
    }

    /**
     * Which stream a Play on an empty timeline means, put back on the player.
     *
     * Never starts audio - the caller is already doing that. Unlike the restart
     * above this always resolves to some stream, because the listener is pressing
     * Play right now and a Play that does nothing is issue #14.
     */
    private fun restoreStreamSelection(reason: String): Boolean {
        val selection = PlaybackIntentPolicy.onEmptyTimelinePlay(
            stored = PlaybackIntentStore.read(this),
            inMemoryStream = stream,
        )
        PlaybackLog.event(
            "PLAYBACK_INTENT_SELECTED", "at" to reason,
            "stream" to selection.stream, "source" to selection.source
        )
        stream = selection.stream
        return installStreamMediaItem(reason)
    }

    /**
     * The media item for the current [stream], with the placeholder title and
     * station name a fresh Play installs - so a restored stream reads as this app
     * in the notification rather than as the raw ICY title the stream sends.
     */
    private fun installStreamMediaItem(where: String): Boolean {
        if (!ensureValidStream(where)) return false
        when (stream) {
            Streams.MYATA -> exoPlayer.setMediaItem(myataItem)
            Streams.GOLD -> exoPlayer.setMediaItem(goldItem)
            Streams.XTRA -> exoPlayer.setMediaItem(xtraItem)
            else -> return false
        }
        logStreamSelection(where)
        currentAlbumArt = null
        // The same call the in-app Play makes before any metadata has arrived:
        // blank in, placeholders out, and no artwork lookup started for a track
        // we do not know yet. The poller fills it in once audio is running.
        updateMetadata("", "")
        return true
    }

    /**
     * The one thing that has to happen before a command is handled, and cannot be done
     * by its handler: the obligation Android attached to the *start*, which only the
     * command knows about.
     *
     * `startForegroundService` gives this service five seconds to call
     * `startForeground`, and the caller that chose that start is the only one who knew
     * it did - so the fact rides the command ([PlaybackCommand.openForeground]) and is
     * answered here, from the record on disk, for the command that is about to run.
     * The queue's head is read again for every command, so a foreground command
     * appended while a pass is running is promoted for too, before *its* handler: the
     * snapshot this replaced could be made stale by exactly that append, and the
     * command would have been handled without the promotion it promised.
     *
     * A promotion that fails means the command is not delivered: it is not handled, not
     * acknowledged, and the pass stops with it still pending, where a later start will
     * find it. Android's timing requirement is not weakened either way - the first
     * thing the pass does for such a command is post the placeholder, well inside the
     * five seconds - and it is the only obligation in play: an external bare start
     * cannot fabricate one, because the only place that flag exists is the record.
     */
    private fun prepareHeadCommand(entry: PlaybackCommandInbox.Entry): Boolean {
        if (!entry.command.openForeground) return true

        if (postPlaceholderForegroundNotification()) return true

        PlaybackLog.problem(
            "FOREGROUND_REQUIRED_BUT_REFUSED",
            "action" to entry.command.action, "id" to entry.id,
            "outcome" to "command_left_pending",
        )
        return false
    }

    /**
     * Takes the placeholder notification down when that is the only thing keeping this
     * service in the foreground.
     *
     * A pass that could not deliver its command leaves the record waiting for a later
     * start, and the placeholder its promotion posted would then be a service claiming
     * foreground work it is not doing: with nothing playing nothing replaces it - Media3
     * posts its own notification when audio actually starts - so it would sit there for
     * as long as this service lives. Nothing about the pending command changes: the
     * next start promotes again, and Media3 promotes again the moment playback begins.
     */
    private fun releasePlaceholderForeground(reason: String) {
        if (!placeholderForegroundUp) return
        // Audio that is running, or on its way, is the reason to be foreground. Only
        // the leftover placeholder of a command that never happened is released.
        if (exoPlayer.isPlaying || exoPlayer.playbackState == Player.STATE_BUFFERING) return

        placeholderForegroundUp = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            PlaybackLog.event("FOREGROUND_PLACEHOLDER_RELEASED", "reason" to reason)
        } catch (e: Exception) {
            PlaybackLog.problem(
                "FOREGROUND_PLACEHOLDER_RELEASE_FAILED", "cause" to e.javaClass.simpleName
            )
        }
    }

    /**
     * The minimal notification that makes this a foreground service.
     *
     * Media3 replaces it with the real one, with controls, moments later. It
     * exists because Android gives a startForegroundService five seconds to call
     * startForeground, and because the restart path above has to be foreground
     * before it touches the player.
     *
     * @return true when the service really was promoted. The caller is the head
     *   command's gate, which must not run a command whose promotion was refused.
     */
    private fun postPlaceholderForegroundNotification(): Boolean {
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
            placeholderForegroundUp = true
            return true
        } catch (e: Exception) {
            // Android 12+ can refuse a foreground start it did not ask for. Media3
            // makes its own attempt when playback begins; nothing here retries.
            Log.e("MediaPlayerService", "Failed to post foreground notification: ${e.message}")
            PlaybackLog.problem(
                "FOREGROUND_NOTIFICATION_REFUSED", "cause" to e.javaClass.simpleName
            )
            return false
        }
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
        canonicaliseStream(where)
        // The station on its own, with no claim about whether audio is wanted -
        // that is [onUserWantsPlayback]'s single atomic write to make. Splitting
        // the two is what lets this be called from places that only need a usable
        // media item without them accidentally asserting an intent.
        PlaybackIntentStore.recordSelectedStream(this, stream)
        return true
    }

    /**
     * Makes [stream] a canonical key, in memory only.
     *
     * The `when(stream)` blocks have no else branch, so an unrecognised key
     * silently leaves the player with no media item - worth a loud line, and worth
     * a default rather than nothing. Deliberately writes nothing: the callers
     * differ in what they are entitled to record.
     */
    private fun canonicaliseStream(where: String) {
        val normalised = Streams.normalise(stream)
        if (normalised == null) {
            PlaybackLog.problem(
                "STREAM_FALLBACK_APPLIED", "invalid" to (stream.ifEmpty { "<empty>" }),
                "usedInstead" to Streams.DEFAULT, "at" to where
            )
            stream = Streams.DEFAULT
        } else if (normalised != stream) {
            PlaybackLog.event("STREAM_NORMALISED", "from" to stream, "to" to normalised, "at" to where)
            stream = normalised
        }
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
        cancelPendingRetry("service_destroyed")

        // The scheduled expiry goes; the record on disk deliberately stays, so a
        // service that is recreated re-adopts the same deadline rather than losing
        // it. Only a reboot, a cancel or the expiry itself removes the record.
        cancelScheduledSleepTimer()

        metadataJob?.cancel()
        serviceScope.cancel()

        // A partial listen dies with the service; P4 persists nothing.
        scrobbleHandler.removeCallbacks(scrobbleCheck)
        scrobbleTracker.release()

        mediaSession?.release()
        mediaSession = null
        
        exoPlayer.release()
        
        LocalBroadcastManager.getInstance(this@MediaPlayerService)
            .sendBroadcast(Intent("Dismiss").apply {})
        
        super.onDestroy()
    }
    
    // ============== SLEEP TIMER (G2) ==============
    //
    // The service owns the timer because the service is what outlives everything
    // that could otherwise hold it. `onTaskRemoved` deliberately keeps playing
    // after the Activity is gone, so a timer held by a ViewModel, a Fragment or
    // the sheet itself would evaporate in exactly the case the feature exists for:
    // the phone face down, the app swiped away, the radio still on.
    //
    // Scheduling is a Handler, not an AlarmManager. The timer can only *do*
    // anything while playback is running, and while playback is running this is a
    // foreground service holding a PARTIAL_WAKE_LOCK, so the CPU is up and Doze
    // cannot defer the callback. An exact alarm would additionally need
    // USE_EXACT_ALARM on API 31+, which Play restricts to alarm-clock and calendar
    // apps - a radio sleep timer does not qualify, and would gain nothing here.

    /**
     * A `sleep_timer_set` as it arrived: a deadline, and the boot it was measured on.
     *
     * The deadline is an `elapsedRealtime` instant and that clock restarts at boot, so a
     * command that outlived a reboot can carry a number that looks like a perfectly
     * ordinary deadline in the new boot's epoch - and arming it would stop the radio at
     * a moment nobody chose. The boot travels with the command
     * ([PlaybackCommand.deadlineBootId], the same `BootIdentity` semantics
     * `SleepTimerStore` uses), and a deadline that cannot prove it belongs to this boot
     * is refused rather than reinterpreted: nothing is armed, nothing is written, and
     * the app's existing reboot rule - a timer never resumes across a reboot - is what
     * answers. A device whose boot counter cannot be read is the same case, on purpose:
     * "unknown" is not "same boot" anywhere else in this app either.
     */
    private fun armSleepTimerFromCommand(command: PlaybackCommand) {
        val currentBoot = BootIdentity.read(this)
        if (!BootIdentity.matches(command.deadlineBootId ?: BootIdentity.UNKNOWN, currentBoot)) {
            PlaybackLog.problem(
                "SLEEP_TIMER_REFUSED",
                "reason" to "deadline_from_another_boot", "minutes" to command.minutes,
            )
            broadcastSleepTimerState()
            return
        }
        armSleepTimer(
            minutes = command.minutes,
            isCustom = command.isCustom,
            resolvedDeadlineMs = command.deadlineElapsedMs,
        )
    }

    /**
     * Starts a timer, replacing whatever was running.
     *
     * Replacement is total: a new choice is a new deadline measured from now, not
     * an extension of the old one, and it discards anything `Вернуть` could have
     * put back.
     *
     * [resolvedDeadlineMs] is when the listener's choice stops being "in 30
     * minutes" and becomes an instant - resolved where the gesture is made, and
     * carried with the command so that handling it a second time after a process
     * death re-arms the *same* deadline rather than a later one. A caller that
     * resolved nothing falls back to now + [minutes], which is what this has always
     * done. See [PlaybackCommand].
     */
    private fun armSleepTimer(minutes: Int, isCustom: Boolean, resolvedDeadlineMs: Long? = null) {
        // Android TV has no way to reach this and must never acquire one. The
        // guard belongs in the service rather than in a UI that TV does not run: the
        // service owns the timer, so it is the only place that is true for every
        // caller.
        if (isTv) {
            PlaybackLog.problem("SLEEP_TIMER_REFUSED", "reason" to "tv_device", "minutes" to minutes)
            clearSleepTimerState()
            broadcastSleepTimerState()
            return
        }

        if (!SleepTimerDuration.isValid(minutes)) {
            PlaybackLog.problem("SLEEP_TIMER_REFUSED", "reason" to "invalid_duration", "minutes" to minutes)
            broadcastSleepTimerState()
            return
        }

        cancelScheduledSleepTimer()
        // A new choice is not the cancelled one, so the offer to put the cancelled one
        // back goes with it - durably, so it does not come back after a restart.
        if (!SleepTimerStore.clearCancelled(this)) {
            PlaybackLog.problem(
                "SLEEP_TIMER_UNDO_NOT_CLEARED", "reason" to "new_choice",
                "outcome" to "stale_snapshot_may_remain",
            )
        }
        sleepTimerGeneration += 1

        val timer = SleepTimerState.Armed(
            deadlineElapsedMs = resolvedDeadlineMs
                ?: (android.os.SystemClock.elapsedRealtime() + SleepTimerDuration.toMs(minutes)),
            durationMinutes = minutes,
            isCustom = isCustom,
            generation = sleepTimerGeneration,
        )
        sleepTimer = timer
        SleepTimerStore.write(this, timer, BootIdentity.read(this))
        scheduleSleepTimer(timer)

        PlaybackLog.event(
            "SLEEP_TIMER_ARMED",
            "minutes" to minutes, "custom" to isCustom, "generation" to timer.generation,
            "isPlaying" to exoPlayer.isPlaying,
        )
        broadcastSleepTimerState()
    }

    /**
     * `Отключить таймер`.
     *
     * Keeps the cancelled timer - durably - so `Вернуть` can put back the deadline it
     * had, not the duration it was created with: a 30-minute timer with 10 minutes left
     * is worth 10 minutes to an undo, and no more.
     *
     * [cancelledBy] is the inbox record's id, and it is what makes this command
     * replay-safe. Delivery is at-least-once: a process death between this handler and
     * its acknowledgement replays the same cancel, and the second run finds no armed
     * timer - this run disarmed it - so it would write "nothing to put back" over the
     * snapshot this run stored. A snapshot stamped with the cancel that created it
     * cannot be overwritten by that same cancel.
     */
    private fun cancelSleepTimer(cancelledBy: String) {
        val cancelled = sleepTimer
        val now = android.os.SystemClock.elapsedRealtime()

        cancelScheduledSleepTimer()
        sleepTimer = null
        sleepTimerGeneration += 1
        SleepTimerStore.clear(this)

        // Nothing to give back if the deadline had already passed. Undo must never
        // manufacture time that had already run out.
        val snapshot = cancelled?.takeIf { !it.hasExpired(now) }
        if (!SleepTimerStore.recordCancelled(this, snapshot, BootIdentity.read(this), cancelledBy)) {
            PlaybackLog.problem(
                "SLEEP_TIMER_CANCEL_NOT_STORED", "id" to cancelledBy,
                "outcome" to "undo_not_on_offer",
            )
        }

        PlaybackLog.event(
            "SLEEP_TIMER_CANCELLED",
            "hadTimer" to (cancelled != null),
            "canUndo" to (snapshot != null),
            "id" to cancelledBy,
        )
        broadcastSleepTimerState()
    }

    /**
     * `Вернуть` on the cancel Snackbar - the original absolute deadline, restored.
     *
     * If that deadline has gone by while the Snackbar was up, this does nothing at
     * all rather than pushing it into the future: a timer that would have fired
     * already is not a timer anybody can have back.
     *
     * [consumedBy] is the inbox record's id, and the snapshot is consumed together with
     * the record of which undo consumed it. That record is what stops a *replayed*
     * undo - the same at-least-once window a cancel has - from consuming a snapshot a
     * later cancel wrote, which would put back a timer the listener had cancelled
     * afterwards.
     */
    private fun undoSleepTimerCancel(consumedBy: String) {
        val now = android.os.SystemClock.elapsedRealtime()

        if (SleepTimerStore.Replay.undoAlreadyConsumed(SleepTimerStore.lastUndo(this), consumedBy)) {
            PlaybackLog.event("SLEEP_TIMER_UNDO_REPLAYED", "id" to consumedBy)
            broadcastSleepTimerState()
            return
        }

        val timer = when (val consumed = SleepTimerStore.consumeCancelled(this, BootIdentity.read(this), consumedBy)) {
            SleepTimerStore.Consumed.NotConsumed -> {
                // The snapshot could not be taken: the undo has not been delivered, and
                // the record stays for the next start to try again.
                PlaybackLog.problem(
                    "SLEEP_TIMER_UNDO_NOT_CONSUMED", "id" to consumedBy,
                    "outcome" to "command_left_pending",
                )
                broadcastSleepTimerState()
                return
            }

            SleepTimerStore.Consumed.Nothing -> {
                PlaybackLog.event("SLEEP_TIMER_UNDO_DECLINED", "reason" to "nothing_to_restore")
                broadcastSleepTimerState()
                return
            }

            is SleepTimerStore.Consumed.Timer -> consumed.timer
        }

        if (timer.hasExpired(now)) {
            PlaybackLog.event("SLEEP_TIMER_UNDO_DECLINED", "reason" to "deadline_already_passed")
            broadcastSleepTimerState()
            return
        }

        cancelScheduledSleepTimer()
        sleepTimerGeneration += 1
        // copy(), so the deadline is carried across untouched and only the
        // generation moves. This is what makes undo a restore rather than a re-arm.
        val restored = timer.copy(generation = sleepTimerGeneration)
        sleepTimer = restored
        SleepTimerStore.write(this, restored, BootIdentity.read(this))
        scheduleSleepTimer(restored)

        PlaybackLog.event(
            "SLEEP_TIMER_UNDONE",
            "remainingMs" to restored.remainingMs(now), "generation" to restored.generation,
        )
        broadcastSleepTimerState()
    }

    /**
     * Adopts whatever survived, and reconciles anything that did not.
     *
     * Called once when the service is created - including the `START_STICKY`
     * recreation after a process death - and again on every `sleep_timer_sync`,
     * which is what a screen asks for when it opens. Every path through it either
     * produces a live timer or removes the record, so nothing can be shown as armed
     * that is not going to fire.
     */
    private fun reconcileSleepTimer(reason: String) {
        val now = android.os.SystemClock.elapsedRealtime()

        sleepTimer?.let { live ->
            if (live.hasExpired(now)) {
                // The Handler has not run yet - it can be behind if the device was
                // in Doze with nothing playing. The read is what catches up.
                PlaybackLog.event("SLEEP_TIMER_RECONCILE_EXPIRED", "reason" to reason)
                expireSleepTimer(live)
            }
            return
        }

        when (val restored = SleepTimerStore.restore(this, BootIdentity.read(this), now)) {
            SleepTimerStore.Restored.None -> Unit

            SleepTimerStore.Restored.ForeignBoot -> {
                // A record from a previous boot, or one that cannot prove which boot
                // it came from. The frozen contract is that a timer never resumes
                // after a reboot, and there is no BOOT_COMPLETED receiver anywhere
                // in the app, so this is the only place that record can go.
                PlaybackLog.event("SLEEP_TIMER_DISCARDED", "reason" to "foreign_boot", "at" to reason)
                SleepTimerStore.clear(this)
            }

            is SleepTimerStore.Restored.Expired -> {
                PlaybackLog.event("SLEEP_TIMER_DISCARDED", "reason" to "already_expired", "at" to reason)
                sleepTimerGeneration = maxOf(sleepTimerGeneration, restored.timer.generation)
                expireSleepTimer(restored.timer)
            }

            is SleepTimerStore.Restored.Armed -> {
                sleepTimerGeneration = maxOf(sleepTimerGeneration, restored.timer.generation) + 1
                val adopted = restored.timer.copy(generation = sleepTimerGeneration)
                sleepTimer = adopted
                SleepTimerStore.write(this, adopted, BootIdentity.read(this))
                scheduleSleepTimer(adopted)
                PlaybackLog.event(
                    "SLEEP_TIMER_RESTORED",
                    "remainingMs" to adopted.remainingMs(now), "at" to reason,
                )
            }
        }
    }

    private fun scheduleSleepTimer(timer: SleepTimerState.Armed) {
        cancelScheduledSleepTimer()
        val generation = timer.generation
        val runnable = Runnable { onSleepTimerCallback(generation) }
        sleepTimerRunnable = runnable
        sleepTimerHandler.postDelayed(
            runnable,
            timer.remainingMs(android.os.SystemClock.elapsedRealtime()),
        )
    }

    /**
     * The scheduled callback. Guarded by the generation, which is the whole
     * duplicate-expiry defence: a callback whose arming has been replaced,
     * cancelled, undone or re-adopted is not the current one and does nothing.
     */
    private fun onSleepTimerCallback(generation: Long) {
        if (generation != sleepTimerGeneration) {
            PlaybackLog.event(
                "SLEEP_TIMER_CALLBACK_STALE",
                "callback" to generation, "current" to sleepTimerGeneration,
            )
            return
        }
        val timer = sleepTimer ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!timer.hasExpired(now)) {
            // Delivered early. Re-post for what is actually left rather than
            // stopping the stream ahead of the deadline the listener chose.
            scheduleSleepTimer(timer)
            return
        }
        expireSleepTimer(timer)
    }

    /**
     * Zero.
     *
     * The stop is the app's **existing** pause, not a new kind of stop: this app
     * has no true pause - `ForwardingPlayer.pause()` clears `userWantsPlayback` and
     * calls `stop()` without clearing the playlist, so the buffer is dropped, the
     * session survives, the Mini Player stays, and the next Play re-prepares to the
     * live edge. Expiry takes that same path with a different caller.
     *
     * `onPlaybackNoLongerWanted` before `stop()` is mandatory rather than tidy. It
     * is what tells the recovery machinery that this silence was asked for; without
     * it `STATE_ENDED` handling would treat the stop as a dropped connection and
     * reconnect within seconds, and the timer would look broken.
     */
    private fun expireSleepTimer(timer: SleepTimerState.Armed) {
        clearSleepTimerState()

        // Unconditional, and before the branch below. A timer that reached zero
        // means audio is not wanted, whether or not there was any to stop - so a
        // process death after the expiry must not be able to bring it back. The
        // wasWanted branch would clear it too, through onPlaybackNoLongerWanted,
        // but only in the case where something was playing - and the case where
        // the listener had already paused is where a stale true would be worst.
        PlaybackIntentStore.recordPlaybackNotWanted(this)

        // Owner decision D5: an explicit pause leaves the timer armed, so a timer
        // can and does reach zero with nothing playing. There is nothing to stop
        // and nothing to announce.
        val wasWanted = userWantsPlayback || exoPlayer.isPlaying
        if (wasWanted) {
            PlaybackLog.event(
                "PLAYER_STOP", "source" to "sleep_timer", "reason" to "sleep_timer_expired",
                "state" to PlaybackLog.stateName(exoPlayer.playbackState),
                "durationMinutes" to timer.durationMinutes,
            )
            onPlaybackNoLongerWanted("sleep_timer_expired")
            exoPlayer.stop()
        } else {
            PlaybackLog.event("SLEEP_TIMER_EXPIRED_IDLE", "durationMinutes" to timer.durationMinutes)
        }

        broadcastSleepTimerState(completed = wasWanted)
    }

    /** Handler, memory, store and the undo snapshot, all released together. */
    private fun clearSleepTimerState() {
        cancelScheduledSleepTimer()
        sleepTimer = null
        sleepTimerGeneration += 1
        SleepTimerStore.clear(this)
        // The affordance goes with the timer it would have put back: an expiry must not
        // leave `Вернуть` on offer for a deadline that has already been honoured.
        if (!SleepTimerStore.clearCancelled(this)) {
            PlaybackLog.problem(
                "SLEEP_TIMER_UNDO_NOT_CLEARED", "reason" to "timer_cleared",
                "outcome" to "stale_snapshot_may_remain",
            )
        }
    }

    private fun cancelScheduledSleepTimer() {
        sleepTimerRunnable?.let { sleepTimerHandler.removeCallbacks(it) }
        sleepTimerRunnable = null
    }

    /**
     * Tells every surface at once.
     *
     * [completed] is a one-shot: it is true only on the broadcast that carries an
     * expiry which actually stopped playback, so the `Таймер сна завершён` Snackbar
     * is shown once and is never re-shown by a later state read.
     */
    private fun broadcastSleepTimerState(completed: Boolean = false) {
        val timer = sleepTimer
        val intent = Intent(SleepTimerContract.BROADCAST_STATE).apply {
            putExtra(SleepTimerContract.STATE_ARMED, timer != null)
            putExtra(SleepTimerContract.STATE_DEADLINE_ELAPSED, timer?.deadlineElapsedMs ?: 0L)
            putExtra(SleepTimerContract.STATE_DURATION_MINUTES, timer?.durationMinutes ?: 0)
            putExtra(SleepTimerContract.STATE_IS_CUSTOM, timer?.isCustom ?: false)
            putExtra(SleepTimerContract.STATE_GENERATION, timer?.generation ?: 0L)
            // Read from the store rather than from a field here: the offer has to be the
            // same one after a restart, and a process that died between the cancel and
            // this broadcast must not be the only place it ever existed.
            putExtra(
                SleepTimerContract.STATE_CAN_UNDO,
                SleepTimerStore.readCancelled(
                    this@MediaPlayerService,
                    BootIdentity.read(this@MediaPlayerService),
                ) != null,
            )
            putExtra(SleepTimerContract.STATE_COMPLETED, completed)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // ============== SCROBBLE ELIGIBILITY (G6b P4) ==============

    /**
     * Tracking runs only in a build with Last.fm credentials, with an account
     * linked right now, and never on TV (owner decisions D2/D3). Read by the
     * tracker at every event and again just before it emits, so an unlink is seen
     * at the next poll, playback change or check - no observer needed.
     */
    private fun isScrobbleTrackingActive(): Boolean = ScrobbleGate.isActive(
        isTv = isTv,
        isConfigured = LastfmConfig.isConfigured,
        isLinked = LastfmLink.of(lastfmSessions.read(), System.currentTimeMillis()) is LastfmLink.Linked,
    )

    /** One pending eligibility check at most; [delayMs] null cancels it. */
    private fun scheduleScrobbleCheck(delayMs: Long?) {
        scrobbleHandler.removeCallbacks(scrobbleCheck)
        if (delayMs != null) scrobbleHandler.postDelayed(scrobbleCheck, delayMs)
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
                val polledStream = stream
                val streamData = data[polledStream] as? Map<String, Any> ?: return@use 15000L

                // The scrobble tracker reads its own copy of the timing - exactly as
                // sent, never the device-clock fallbacks used below to pace polling.
                val observation = FeedObservation.from(polledStream, streamData, apiResponse["server_time"])
                withContext(Dispatchers.Main) {
                    scheduleScrobbleCheck(
                        scrobbleTracker.onObservation(observation, android.os.SystemClock.elapsedRealtime())
                    )
                }
                
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
        // Use placeholders for empty metadata to keep UI clean
        val finalArtist = if (artist.isBlank()) getString(R.string.slogan_placeholder) else artist
        val finalSong = if (song.isBlank()) getString(R.string.brand_name) else song

        // Deduplicate updates EXCEPT when we are force-clearing metadata on pause/stop -
        // and only when the session really still shows this track (G5d): startStop
        // installs a bare MediaItem before calling here, and skipping then left the
        // notification on the raw stream title with no artist and no artwork.
        val isReset = artist.isBlank() && song.isBlank()
        if (!isReset && this.artist == artist && this.song == song &&
            SessionMetadataPolicy.isInstalled(installedSessionMetadata(), finalSong, finalArtist, currentAlbumArtUrl) &&
            (artworkSettled || fetchJob?.isActive == true)
        ) {
            Log.d("MetadataPolling", "Metadata unchanged, skipping update: $artist - $song")
            return
        }

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
            artworkSettled = false
            lastFetchedArtist = artist
            lastFetchedSong = song
            // Cancel any pending fetch for the previous track
            fetchJob?.cancel()
        } else {
            Log.d("MetadataPolling", "Same track, keeping current art: $artist - $song")
        }
        
        // Force Media3 metadata update. Preserve current artwork URL if available to prevent flickering
        applySessionMetadata(finalArtist, finalSong, currentAlbumArtUrl)

        // Async: Fetch and set album art - unless a lookup for this same track has
        // already finished or is still running (a refresh must not restart or lose it).
        if (!SessionMetadataPolicy.shouldFetchArtwork(
                isPlaceholder = isReset,
                trackChanged = trackChanged,
                artworkSettled = artworkSettled,
                fetchInFlight = fetchJob?.isActive == true,
            )
        ) {
            return
        }

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
                    // Only update if the track hasn't changed while we were fetching art.
                    // lastFetched* is the raw identity the lookup was made for.
                    if (lastFetchedArtist == currentArtist && lastFetchedSong == currentSong) {
                        currentAlbumArt = finalBitmap // UPDATE THE CACHED BITMAP FOR ADAPTER
                        updateMediaSessionWithArt(finalBitmap, currentArtist, currentSong)
                        Log.d("MetadataPolling", if (bitmap != null) "Album art set: $albumArtUrl" else "Using placeholder logo")

                        currentAlbumArtUrl = albumArtUrl // Persist the URL
                        artworkSettled = true
                        // Rebuilt from the service's own title/artist, not from
                        // exoPlayer.mediaMetadata: that merges the stream's ICY title,
                        // so on a bare item it would write the raw `ARTIST - TITLE` in.
                        applySessionMetadata(this@MediaPlayerService.artist, this@MediaPlayerService.song, albumArtUrl)
                        Log.d("MetadataPolling", "Updated player metadata with art URL")
                    } else {
                        Log.d("MetadataPolling", "Track changed, skipping art update for: $currentArtist - $currentSong")
                    }
                }
            } catch (e: Exception) {
                Log.e("MetadataPolling", "Failed to load album art: ${e.message}")
                // Set placeholder on error
                withContext(Dispatchers.Main) {
                    if (lastFetchedArtist == currentArtist && lastFetchedSong == currentSong) {
                        updateMediaSessionWithArt(getPlaceholderBitmap(), currentArtist, currentSong)
                    }
                }
            }
        }
    }
    
    /** What the player's current MediaItem itself carries - not the ICY-merged player metadata. */
    private fun installedSessionMetadata(): SessionMetadataPolicy.Installed? =
        exoPlayer.currentMediaItem?.mediaMetadata?.let {
            SessionMetadataPolicy.Installed(it.title?.toString(), it.artist?.toString(), it.artworkUri?.toString())
        }

    /** Puts title, artist and cover on the current MediaItem; Media3 updates the notification from it. */
    private fun applySessionMetadata(finalArtist: String, finalSong: String, artworkUrl: String?) {
        val metadataBuilder = MediaMetadata.Builder()
            .setArtist(finalArtist)
            .setTitle(finalSong)
            .setDisplayTitle(finalSong)
            .setSubtitle(finalArtist)
            .setAlbumTitle(getStreamDisplayName())
        artworkUrl?.let {
            metadataBuilder.setArtworkUri(android.net.Uri.parse(it))
        }
        val metadata = metadataBuilder.build()
        exoPlayer.currentMediaItem?.let {
            val newItem = it.buildUpon().setMediaMetadata(metadata).build()
            exoPlayer.replaceMediaItem(exoPlayer.currentMediaItemIndex, newItem)
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
        return artworkResolver.resolve(
            artist,
            track,
            com.example.musicplayerapp.data.ArtworkPriority.CURRENT_TRACK,
        ).coverUrl
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

    // The recovery numbers - the fast budget, its backoff, the stability window and
    // the slow phase's interval - all live in [RecoveryPolicy], which is where they
    // are pinned by JVM tests. Nothing about an episode is kept here that the policy
    // does not own: this class keeps the episode, the armed attempt and the
    // connectivity it retries on.
}
