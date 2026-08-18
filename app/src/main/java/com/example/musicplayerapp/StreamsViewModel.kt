package com.example.musicplayerapp

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.musicplayerapp.service.MediaPlayerService
import com.example.musicplayerapp.service.PlaybackLog
import com.example.musicplayerapp.utils.ServiceUtils
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.select.Elements
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection




import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LiveData
import androidx.lifecycle.SavedStateHandle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.Player
import androidx.media3.common.MediaMetadata
import android.content.ComponentName
import com.example.musicplayerapp.data.HistoryRepository
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.FavoriteDao
import com.example.musicplayerapp.data.FavoriteTrack
import com.example.musicplayerapp.data.FeedbackRepository
import com.example.musicplayerapp.data.ReactionEvent
import com.example.musicplayerapp.data.*
import com.google.common.util.concurrent.ListenableFuture
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.TimeUnit

class StreamsViewModel(app: Application, private val savedStateHandle: SavedStateHandle):AndroidViewModel(app) {

    // Spotify API credentials
    // Spotify API (Removed) -> iTunes API (No auth required)
    // private val spotifyClientId = "..." 
    // private val spotifyClientSecret = "..."

    
    var currentMyataState = MutableLiveData<PlayerState?>()
    var currentGoldState = MutableLiveData<PlayerState?>()
    var currentXtraState = MutableLiveData<PlayerState?>()
    var isPlaying = MutableLiveData<Boolean>()
    var isBuffering = MutableLiveData<Boolean>()

    /**
     * Whether the playback service actually holds a stream session.
     *
     * Read from the player's own timeline - a session is "the service has a media
     * item loaded" - so it is the service's truth and not a flag the UI keeps.
     * That matters in both directions:
     *
     *  - on a genuinely cold start nothing has been selected yet, the player is
     *    empty, and this stays false however many times the app is opened;
     *  - when the UI is recreated while the service is still alive, the new
     *    controller reconnects to the existing timeline and this comes back true
     *    immediately, with no persisted state to restore and none to go stale.
     *
     * It is deliberately not `isPlaying`: a paused stream is still a session.
     */
    private val _hasPlaybackSession = MutableLiveData(false)
    val hasPlaybackSession: LiveData<Boolean> = _hasPlaybackSession
    var isInSplitMode = MutableLiveData<Boolean>()
    var playlistList = MutableLiveData<MutableList<MyataPlaylist>>()
    private val _playlistsState = MutableLiveData(PlaylistsState.LOADING)
    val playlistsState: LiveData<PlaylistsState> = _playlistsState
    private var playlistsJob: Job? = null
    var lastObservedStream: String? = null
    
    // Use SavedStateHandle for persistence
    var currentStreamLive = savedStateHandle.getLiveData<String>("stream_live", "myata")
    
    var currentFragmentLiveData = MutableLiveData<String>()
    @SuppressLint("StaticFieldLeak")
    private val context = getApplication<Application>().applicationContext
    var isUIActive = true
    var lastAnimatedImageUrl: String? = null  // Track URL of last animated cover art
    var cachedTopInset: Int? = null // Cache for window insets to prevent UI jumping
    
    // Favorites
    private val database = AppDatabase.getDatabase(app)
    private val favoriteDao = database.favoriteDao()
    private val _isCurrentFavorite = MutableLiveData<Boolean>(false)
    val isCurrentFavorite: LiveData<Boolean> = _isCurrentFavorite
    private var favoriteObservationJob: Job? = null

    private val client = SecureNetModule.getOkHttpClient(app)

    // Repositories
    private val historyRepository = HistoryRepository(client)
    private val feedbackRepository = FeedbackRepository(client)
    private val artworkRepository = ArtworkRepository(client)
    private val metadataRepository = MetadataRepository(client)

    private var mediaController: MediaController? = null

    /**
     * The connection this ViewModel asked for, kept until it is handed back.
     *
     * A controller is not owned by the object that holds the reference, it is a
     * binder connection to the session service, and it lives until somebody
     * releases it. The future - not the controller - is what [onCleared] releases,
     * because a UI can be torn down while the connection is still being made and
     * only the future covers that case as well as the connected one.
     */
    private var controllerFuture: ListenableFuture<MediaController>? = null

    /**
     * Set by [onCleared]. Nothing that connects, reconnects or registers a listener
     * may run after it: the connection callback below can still arrive after the UI
     * that owned this ViewModel is gone.
     *
     * Written and read on the application (main) thread - `onCleared` runs there,
     * and so does the controller callback, which Media3 delivers on the looper the
     * controller was built with.
     */
    private var isCleared = false

    /** A Play pressed before the controller connected, to be run once it does. */
    private var pendingPlayRequest = false
    private var controllerRetryAttempt = 0
    
    /**
     * The payload as the API returned it, before [BroadcastHistoryFeed] has had
     * anything to say about it.
     *
     * Held separately from [historyTracks] because the two answer different
     * questions: this is what the station broadcast, that is what the reader is
     * allowed to see given what is playing *now*. Keeping the raw list is what
     * makes a track change cost nothing - see [republishHistory].
     */
    private var historyRaw: List<HistoryTrack> = emptyList()

    private val _historyTracks = MutableLiveData<List<HistoryTrack>>()
    val historyTracks: LiveData<List<HistoryTrack>> = _historyTracks
    private val _historyLoading = MutableLiveData<Boolean>(false)
    val historyLoading: LiveData<Boolean> = _historyLoading
    private var lastHistoryStream: String? = null
    private var historyJob: Job? = null
    private var historyRefreshJob: Job? = null

    /**
     * The current track the published history was last projected against, so a
     * metadata tick that repeats the same track does no work. Both writers of the
     * now-playing state - the HTTP poll and the Media3 metadata callback - land
     * here, and they routinely report the same track one after the other.
     */
    private var lastHistoryTrackIdentity: String? = null

    //problem why we need this is service cannot launch fragment, it can only recreate activity
    var ifNeedToNavigateStraightToPlayer = false
    //To avoid reaction on swich stream pause
    var ifNeedToListenReciever = true



    init {
        isPlaying.value = false
        isBuffering.value = false
        isInSplitMode.value = false
        currentStreamLive.value = "myata"

        setupMediaController()

        // Use localized strings for initial state
        val slogan = app.getString(R.string.slogan_placeholder)
        val brand = app.getString(R.string.brand_name)

        currentMyataState.value = PlayerState(slogan, brand, null)
        currentGoldState.value = PlayerState(slogan, brand, null)
        currentXtraState.value = PlayerState(slogan, brand, null)

        startMetadataPolling()
        refreshPlaylists()

        // Observe current track state to update favorite status
        observeTrackForFavorites()
    }

    private fun setupMediaController(attempt: Int = 0) {
        if (isCleared) return
        // Use context.packageName (applicationId) since it may differ from the source package
        val sessionToken = SessionToken(context, ComponentName(context.packageName, MediaPlayerService::class.java.name))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        this.controllerFuture = controllerFuture
        PlaybackLog.event("CONTROLLER_CONNECT_REQUESTED", "attempt" to (attempt + 1))
        controllerFuture.addListener({
            // The UI can go away while this connection is in flight. onCleared has
            // already released the future by then, and the controller it produces
            // with it - so this callback must not adopt one, must not register a
            // listener on it, and must not treat the cancellation as a failure
            // worth retrying.
            if (isCleared) {
                PlaybackLog.event("CONTROLLER_CONNECT_ABANDONED", "reason" to "viewmodel_cleared")
                return@addListener
            }
            try {
                mediaController = controllerFuture.get()
                mediaController?.addListener(playerListener)
                PlaybackLog.event(
                    "CONTROLLER_CONNECTED",
                    "attempt" to (attempt + 1),
                    "isPlaying" to (mediaController?.isPlaying == true),
                    "state" to PlaybackLog.stateName(mediaController?.playbackState ?: Player.STATE_IDLE)
                )
                controllerRetryAttempt = 0
                // Initial sync
                isPlaying.postValue(mediaController?.isPlaying == true)
                isBuffering.postValue(mediaController?.playbackState == Player.STATE_BUFFERING)
                // Whatever the service was already doing before this controller
                // existed - this is the only place a recreated UI learns it.
                refreshPlaybackSession()
                // A Play pressed before the controller existed is honoured now.
                flushPendingPlayRequest()
            } catch (e: Exception) {
                Log.e("MediaController", "Failed to connect", e)
                PlaybackLog.problem(
                    "CONTROLLER_CONNECT_FAILED",
                    "attempt" to (attempt + 1),
                    "cause" to e.javaClass.simpleName
                )
                scheduleControllerReconnect(attempt)
            }
        }, { it.run() }) // Executor
    }

    /**
     * Bounded reconnect. Without this a single failed connection left the
     * controller null for the entire session and silently swallowed every
     * subsequent Play press (issue #14).
     */
    private fun scheduleControllerReconnect(failedAttempt: Int) {
        val next = failedAttempt + 1
        if (next >= CONTROLLER_MAX_ATTEMPTS) {
            PlaybackLog.problem(
                "CONTROLLER_RECONNECT_GAVE_UP", "attempts" to next,
                "outcome" to "play_falls_back_to_service_intent"
            )
            return
        }
        val delay = (CONTROLLER_RETRY_BASE_MS shl (failedAttempt.coerceAtMost(3)))
            .coerceAtMost(CONTROLLER_RETRY_MAX_MS)
        controllerRetryAttempt = next
        PlaybackLog.event("CONTROLLER_RECONNECT_SCHEDULED", "nextAttempt" to (next + 1), "delayMs" to delay)
        viewModelScope.launch {
            delay(delay)
            setupMediaController(next)
        }
    }

    /**
     * Hands the session connection back when the UI that owned it is gone.
     *
     * A [MediaController] is a binder connection to [MediaPlayerService], not a
     * plain object: holding one keeps the service bound and keeps this ViewModel -
     * and everything it references - alive for as long as the process lives.
     * Nothing released it before, so every ViewModel that was ever built left one
     * behind, and a UI rebuilt often enough (each finish-and-relaunch, each
     * `ActivityScenario` in a test run) piled up one more connected controller and
     * one more registered [playerListener] on the same session.
     *
     * What is given back here is exactly what this ViewModel took out:
     *
     *  - [playerListener], the listener this ViewModel registered;
     *  - the controller connection, through [MediaController.releaseFuture] rather
     *    than `release()`, because the connection may still be in flight. That call
     *    covers both states: a completed future's controller is released, and an
     *    incomplete one is cancelled, after which Media3 releases the controller as
     *    soon as it finishes being built. Either way nothing escapes.
     *
     * The polling and artwork coroutines need no line here: they run in
     * [viewModelScope], which `ViewModel.clear()` cancels before calling this.
     *
     * What is deliberately *not* touched is the service and its session. They are
     * not this ViewModel's to end - the service is started, holds the playback
     * session and the media notification, and outlives any UI on purpose. Releasing
     * a controller unbinds this client from it; it does not stop it, so playback
     * carries on across a recreation and the next controller reconnects to the
     * session that is still there.
     */
    override fun onCleared() {
        isCleared = true

        // Off the controller before it goes, so a listener registered by a dead
        // ViewModel cannot be called back while the release is being completed.
        mediaController?.removeListener(playerListener)
        mediaController = null
        pendingPlayRequest = false

        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null

        PlaybackLog.event("CONTROLLER_RELEASED", "reason" to "viewmodel_cleared")

        super.onCleared()
    }

    /**
     * The controller this ViewModel currently holds, or null if it has none.
     *
     * Exists for `MediaControllerLifecycleTest`, which asserts the release above
     * actually happened. That can only be seen on the controller object itself -
     * `isConnected` goes false when it is released - so the test needs the same
     * instance the ViewModel had. Nothing in the app reads this.
     */
    @VisibleForTesting
    internal val controllerForTest: MediaController?
        get() = mediaController

    /**
     * Runs a Play that arrived before the controller was ready. Guarded so a burst
     * of taps cannot turn into several Play commands.
     */
    private fun flushPendingPlayRequest() {
        if (!pendingPlayRequest) return
        pendingPlayRequest = false
        PlaybackLog.event("UI_PENDING_PLAY_FLUSHED")
        togglePlayPause()
    }

    /**
     * Re-reads whether the service holds a session. Cheap, and called from every
     * player callback that can change the answer rather than from a guess about
     * which one will.
     */
    private fun refreshPlaybackSession() {
        _hasPlaybackSession.postValue((mediaController?.mediaItemCount ?: 0) > 0)
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@StreamsViewModel.isPlaying.postValue(isPlaying)
        }

        override fun onPlaybackStateChanged(state: Int) {
            isBuffering.postValue(state == Player.STATE_BUFFERING)
            refreshPlaybackSession()
        }

        /**
         * The service loads its media item itself, in response to a start intent,
         * so the timeline is where the UI finds out a session now exists - not the
         * command that asked for it, which can be issued and then fail.
         */
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            refreshPlaybackSession()
        }

        override fun onMediaItemTransition(
            mediaItem: androidx.media3.common.MediaItem?,
            reason: Int,
        ) {
            refreshPlaybackSession()
        }

        override fun onMediaMetadataChanged(metadata: MediaMetadata) {
            val artist = metadata.artist?.toString()
            val song = metadata.title?.toString()
            val artUrl = metadata.artworkUri?.toString()
            
            if (artist != null && song != null) {
                // VALIDATION: Identify which stream this metadata belongs to based on the current media item's URI
                val currentUri = mediaController?.currentMediaItem?.localConfiguration?.uri?.toString() ?: ""
                val streamKey = when {
                    currentUri.contains("myata_hits") -> "myata_hits"
                    currentUri.contains("gold") -> "gold"
                    currentUri.contains("/myata") || currentUri.endsWith("/myata") -> "myata"
                    else -> currentStreamLive.value ?: "myata"
                }

                val newState = PlayerState(artist, song, artUrl)
                
                // DEDUPLICATION: Only update if anything actually changed to avoid flickering
                val currentState = when(streamKey) {
                    "myata" -> currentMyataState.value
                    "gold" -> currentGoldState.value
                    "myata_hits" -> currentXtraState.value
                    else -> null
                }

                if (currentState?.artist != artist || currentState?.song != song || currentState?.img != artUrl) {
                    when(streamKey) {
                        "myata" -> currentMyataState.postValue(newState)
                        "gold" -> currentGoldState.postValue(newState)
                        "myata_hits" -> currentXtraState.postValue(newState)
                    }
                }
            }
        }
    }

    /**
     * The one place the app notices that the current track has changed.
     *
     * It already existed for the collection heart; Broadcast History now hangs
     * off the same four observers rather than growing a set of its own. That is
     * the point - there is one current-track signal, and both the heart and the
     * history are readers of it.
     */
    private fun observeTrackForFavorites() {
        currentStreamLive.observeForever { stream ->
            updateFavoriteObservation()
            // A stream switch reloads the history from scratch (the page observer
            // asks for it), so this only re-seeds the identity the next track
            // change will be compared against - it must not schedule a second
            // request for the same switch.
            lastHistoryTrackIdentity = currentTrackIdentity()
            republishHistory()
        }
        currentMyataState.observeForever { if (currentStreamLive.value == "myata") onCurrentTrack() }
        currentGoldState.observeForever { if (currentStreamLive.value == "gold") onCurrentTrack() }
        currentXtraState.observeForever { if (currentStreamLive.value == "myata_hits") onCurrentTrack() }
    }

    private fun onCurrentTrack() {
        updateFavoriteObservation()
        onCurrentTrackChanged()
    }

    private fun updateFavoriteObservation() {
        val stream = currentStreamLive.value
        val state = when(stream) {
            "myata" -> currentMyataState.value
            "gold" -> currentGoldState.value
            "myata_hits" -> currentXtraState.value
            else -> null
        }

        val artist = state?.artist
        val song = state?.song

        favoriteObservationJob?.cancel()
        if (artist != null && song != null && artist != "YOUR MUSIC! YOUR STATION!") {
            favoriteObservationJob = viewModelScope.launch {
                favoriteDao.isFavorite(artist, song).collectLatest {
                    _isCurrentFavorite.postValue(it)
                }
            }
        } else {
            _isCurrentFavorite.value = false
        }
    }

    fun toggleCurrentFavorite() {
        val stream = currentStreamLive.value ?: "myata"
        val state = when(stream) {
            "myata" -> currentMyataState.value
            "gold" -> currentGoldState.value
            "myata_hits" -> currentXtraState.value
            else -> null
        }

        val artist = state?.artist
        val song = state?.song

        if (artist != null && song != null && artist != "YOUR MUSIC! YOUR STATION!") {
            viewModelScope.launch {
                val existing = favoriteDao.findByArtistAndTrack(artist, song)
                val event = if (existing != null) {
                    // Withdrawing a Like is UNLIKE - a return to neutral. It reported
                    // DISLIKE until now, which is how un-liking became a negative
                    // signal in the sheet.
                    ReactionEvent.afterDelete(favoriteDao.delete(existing))
                } else {
                    ReactionEvent.afterInsert(
                        favoriteDao.insert(
                            FavoriteTrack(
                                artist = artist,
                                track = song,
                                stream = stream
                            )
                        )
                    )
                }

                // Null when the write changed nothing - the row had already gone, or
                // the Collection screen added the same track while this ran. Nothing
                // changed, so there is nothing to report.
                event?.let { feedbackRepository.reportFeedback(artist, song, stream, it) }
            }
        }
    }




    /**
     * Loads the playlists, retrying with exponential backoff inside a bounded budget.
     * Ends in either READY or ERROR — never keeps the splash screen waiting forever
     * and never turns into an endless background poll (issue #9).
     *
     * Calling this while a load is already running is a no-op, so a burst of
     * connectivity callbacks cannot pile up requests.
     */
    fun refreshPlaylists() {
        if (playlistsJob?.isActive == true) return

        playlistsJob = viewModelScope.launch {
            _playlistsState.value = PlaylistsState.LOADING

            val loaded = withTimeoutOrNull(PLAYLISTS_LOAD_BUDGET_MS) {
                var backoffMs = PLAYLISTS_RETRY_INITIAL_DELAY_MS
                var playlists = metadataRepository.fetchPlaylists()
                while (playlists.isEmpty()) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(PLAYLISTS_RETRY_MAX_DELAY_MS)
                    playlists = metadataRepository.fetchPlaylists()
                }
                playlists
            }

            if (loaded != null) {
                playlistList.value = loaded.toMutableList()
                _playlistsState.value = PlaylistsState.READY
            } else {
                Log.w("StreamsViewModel", "Playlists not loaded within budget, showing error state")
                _playlistsState.value = PlaylistsState.ERROR
            }
        }
    }

    private fun startMetadataPolling() {
        viewModelScope.launch {
            metadataRepository.pollMetadata().collect { states ->
                states.forEach { (streamKey, newState) ->
                    val currentStateProp = when (streamKey) {
                        "myata" -> currentMyataState
                        "gold" -> currentGoldState
                        "myata_hits" -> currentXtraState
                        else -> null
                    }

                    val current = currentStateProp?.value
                    
                    // Update metadata if artist/song changed OR if we have no image and current is null
                    if (current == null || current.artist != newState.artist || current.song != newState.song) {
                        currentStateProp?.postValue(newState)
                        
                        // Fetch artwork asynchronously
                        viewModelScope.launch {
                            val artwork = artworkRepository.fetchArtwork(newState.artist ?: "", newState.song ?: "")
                            val latest = currentStateProp?.value
                            // Update ONLY if still on the same track
                            if (latest != null && latest.artist == newState.artist && latest.song == newState.song) {
                                currentStateProp.postValue(latest.copy(
                                    img = artwork.coverUrl ?: "NO_IMAGE",
                                    backgroundImg = artwork.backgroundUrl
                                ))
                            }
                        }

                        // Also notify service if this is the active stream to sync lock screen
                        if (currentStreamLive.value == streamKey && isPlaying.value == true) {
                            ServiceUtils.safeStartService(
                                getApplication(),
                                "switch_track",
                                streamKey,
                                newState.artist,
                                newState.song
                            )
                        }
                    }
                }
            }
        }
    }

    fun triggerMetadataUpdate() {
        // Handled by background polling or service broadcasts
    }
    
    fun togglePlayPause() {
        val controller = mediaController
        if (controller == null) {
            // Never drop the tap. Remember it and run it once the controller is
            // ready; a second tap while one is queued must not queue another,
            // otherwise a burst of taps becomes a burst of Play commands (#14).
            if (pendingPlayRequest) {
                PlaybackLog.event("UI_PLAY_REQUEST_COALESCED", "reason" to "already_queued")
            } else {
                pendingPlayRequest = true
                PlaybackLog.event(
                    "UI_PLAY_REQUEST_QUEUED",
                    "reason" to "controller_not_ready",
                    "stream" to (currentStreamLive.value ?: "none")
                )
            }
            return
        }
        controller.let {
            if (it.isPlaying) {
                PlaybackLog.event("UI_PAUSE_REQUEST", "state" to PlaybackLog.stateName(it.playbackState))
                it.pause()
            } else {
                PlaybackLog.event(
                    "UI_PLAY_REQUEST",
                    "state" to PlaybackLog.stateName(it.playbackState),
                    "mediaItemCount" to it.mediaItemCount,
                    "stream" to (currentStreamLive.value ?: "none")
                )
                // If player is empty, we must start a stream via the Service intent
                // because current stream selection logic (fallback etc) is still in Service.
                // In Phase 3 we can move all URI logic to a Repository.
                if (it.playbackState == Player.STATE_IDLE || it.mediaItemCount == 0) {
                    startServiceForCurrentStream("startStop")
                } else {
                    it.prepare()
                    it.play()
                }
            }
        }
    }

    fun switchStream(stream: String) {
        val isSameStream = currentStreamLive.value == stream
        PlaybackLog.event(
            "UI_STREAM_SWITCH",
            "to" to stream,
            "from" to (currentStreamLive.value ?: "none"),
            "sameStream" to isSameStream
        )

        if (isSameStream) {
            // Same stream clicked - only trigger playback if stream is NOT already playing
            // The isPlaying check is done in the service via forcePlay logic
            // We send forcePlay=true but the service will only start if not already playing
            ServiceUtils.safeStartService(getApplication(), "switch", stream, "", "", forcePlay = true)
            return
        }

        // DON'T reset to placeholders - metadata is preloaded by MetadataRepository polling
        // The preloaded data in currentMyataState/currentGoldState/currentXtraState 
        // will be displayed immediately on swipe

        currentStreamLive.value = stream
        startServiceForCurrentStream("switch", forcePlay = true)
    }

    fun refreshPlayerStatus() {
        // MediaController automatically syncs state
    }

    private fun startServiceForCurrentStream(action: String, forcePlay: Boolean = false) {
        // Never hand the service an unusable stream key: its lookups have no else
        // branch, so it would prepare a player with no media item (issue #14).
        val resolved = Streams.normalise(currentStreamLive.value)
        if (resolved == null) {
            PlaybackLog.problem(
                "STREAM_FALLBACK_APPLIED",
                "invalid" to (currentStreamLive.value ?: "null"),
                "usedInstead" to Streams.DEFAULT
            )
            currentStreamLive.value = Streams.DEFAULT
        } else if (resolved != currentStreamLive.value) {
            currentStreamLive.value = resolved
        }

        val artist = when(currentStreamLive.value) {
            "myata" -> currentMyataState.value?.artist
            "gold" -> currentGoldState.value?.artist
            "myata_hits" -> currentXtraState.value?.artist
            else -> null
        } ?: getApplication<Application>().getString(R.string.slogan_placeholder)

        val song = when(currentStreamLive.value) {
            "myata" -> currentMyataState.value?.song
            "gold" -> currentGoldState.value?.song
            "myata_hits" -> currentXtraState.value?.song
            else -> null
        } ?: getApplication<Application>().getString(R.string.brand_name)

        ServiceUtils.safeStartService(getApplication(), action, currentStreamLive.value, artist, song, forcePlay)
    }
    
    /**
     * Loads track history for the current stream.
     *
     * One request at a time. A swipe back and forth used to start a fetch per
     * page callback and let whichever finished last win, which with the
     * track-change refresh below would have turned a busy stream into a
     * self-inflicted poll. The in-flight job is cancelled rather than the new
     * call dropped, because the newer request is the one that reflects the
     * current stream.
     */
    fun loadHistory() {
        val stream = currentStreamLive.value ?: "myata"

        if (lastHistoryStream != stream) {
            // A different station's history is not this one's, so it goes rather
            // than sitting there stale under a spinner. Both halves are cleared:
            // the raw payload is the thing everything else is derived from.
            historyRaw = emptyList()
            _historyTracks.value = emptyList()
            lastHistoryStream = stream
        }

        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            _historyLoading.value = true
            val history = historyRepository.getHistory(stream, HISTORY_LIMIT)
            // Both on the main thread, in this order, and neither posted: the
            // observers see one consistent pair. `postValue` for the tracks with
            // `value` for the flag used to deliver "not loading, no tracks" first
            // and the tracks a tick later, so a finished load flashed the empty
            // state before drawing itself.
            historyRaw = history
            republishHistory()
            _historyLoading.value = false
        }
    }

    /**
     * Re-derives the published history from the payload already held.
     *
     * This is the whole of "seamless": on A -> B the raw payload still describes
     * A as its head, so projecting it against B moves A into position 0 with no
     * request, no spinner and no list to rebuild. The network refresh that
     * follows returns [B, A, ...], which projects to the identical list - so the
     * catch-up is invisible and the diff is empty.
     */
    private fun republishHistory() {
        val projected = BroadcastHistoryFeed.project(
            raw = historyRaw,
            currentIdentity = currentTrackIdentity(),
            limit = HISTORY_LIMIT,
        )
        if (_historyTracks.value != projected) {
            _historyTracks.value = projected
        }
    }

    /**
     * What is playing on the current stream, as a history identity, or null while
     * the app is still on the placeholder pair.
     *
     * The null case is not paranoia. The placeholder is "YOUR MUSIC! YOUR
     * STATION!" / "RADIO MYATA", and the station logs its own idents under
     * exactly that artist and track - they are real rows in the payload. Treating
     * the placeholder as a current track would drop a genuine ident from the head
     * of the history before the first metadata poll has even answered.
     */
    private fun currentTrackIdentity(): String? {
        val state = when (Streams.normalise(currentStreamLive.value) ?: Streams.DEFAULT) {
            Streams.GOLD -> currentGoldState.value
            Streams.XTRA -> currentXtraState.value
            else -> currentMyataState.value
        } ?: return null

        val app = getApplication<Application>()
        if (state.artist == app.getString(R.string.slogan_placeholder) &&
            state.song == app.getString(R.string.brand_name)
        ) {
            return null
        }
        return BroadcastHistoryFeed.identityOf(state.artist, state.song)
    }

    /**
     * The stream moved on to a new track.
     *
     * Called from the same two places that already write the now-playing state -
     * the metadata poll and the Media3 metadata callback - so this adds no second
     * source of truth and, in particular, no third poller (the duplicate-polling
     * debt in CLAUDE.md is about pollers, and this is edge-triggered off the
     * state those pollers already produce).
     *
     * Two steps, deliberately: the re-projection is immediate so the finished
     * track appears at row 0 the instant the new one is announced, and the
     * request is debounced so the poll and the callback reporting the same change
     * a moment apart cost one fetch rather than two. The delay is also what gives
     * the API time to write its own row for the new track; if it has not yet,
     * the projection above is already showing the right list anyway.
     */
    private fun onCurrentTrackChanged() {
        val identity = currentTrackIdentity()
        if (identity == lastHistoryTrackIdentity) return
        val hadTrack = lastHistoryTrackIdentity != null
        lastHistoryTrackIdentity = identity

        republishHistory()

        // Nothing to catch up with before a history has been loaded at all: the
        // first load is the stream observer's, and firing one here as well would
        // just be the same request twice on every cold open.
        if (!hadTrack || lastHistoryStream == null) return

        historyRefreshJob?.cancel()
        historyRefreshJob = viewModelScope.launch {
            delay(HISTORY_REFRESH_DEBOUNCE_MS)
            loadHistory()
        }
    }

    /**
     * A cover for one Broadcast History row, or null if none can be found.
     *
     * [HistoryTrack] carries artist, track and a timestamp and has nowhere to put
     * artwork, while the frozen inline section draws a cover per row, so it is
     * derived from the artist and track by [ArtworkRepository] - the app's single
     * source of truth for artwork, which the now-playing metadata above already
     * goes through and whose in-memory cache both therefore share. This adds a
     * view of the existing history, not a second store beside it.
     *
     * No dispatcher is stated here: `fetchArtwork` switches to IO itself around
     * its blocking body (#45), so a wrapper at this call site would only dispatch
     * to the dispatcher the callee is about to move to anyway. It would also cost
     * a cache hit a round trip the repository deliberately keeps it clear of, by
     * reading the cache ahead of its own switch.
     */
    suspend fun historyArtworkUrl(track: HistoryTrack): String? =
        runCatching { artworkRepository.fetchArtwork(track.artist, track.title).coverUrl }
            .getOrNull()

    fun formUrl(songArtist: List<String>): String{
        return "https://last.fm/music/${songArtist.get(0)
            ?.lowercase()?.split(" ft.")?.get(0)!!.trim()
            .replace("/", "%2F")
            .replace(" ", "+")
        }/+images"
    }

    private companion object {
        /** Bounded MediaController reconnect: 0.5s, 1s, 2s, 4s, then stop trying. */
        const val CONTROLLER_MAX_ATTEMPTS = 5
        const val CONTROLLER_RETRY_BASE_MS = 500L
        const val CONTROLLER_RETRY_MAX_MS = 4_000L

        /**
         * How much Broadcast History the app holds, for every view of it.
         *
         * This used to disagree with itself: the fetch took
         * `HistoryRepository.getHistory`'s default limit of 20 and the result was
         * then trimmed with `take(30)`, so the 30 could never be reached and the
         * real ceiling was the default nobody had written down here. Both ends
         * now read this, so the number the app asks the API for and the number it
         * keeps are the same number.
         */
        const val HISTORY_LIMIT = 30

        /**
         * How long a track change waits before the history is re-fetched.
         *
         * Not a poll interval - nothing repeats. It is the window in which the
         * two writers of the now-playing state (the HTTP metadata poll and
         * Media3's `onMediaMetadataChanged`) announce the same change, so that
         * one transition costs one request. It also lets the API write its own
         * row for the new track; the list on screen is already correct while it
         * does, because the projection moved the finished track up without
         * asking anyone.
         */
        const val HISTORY_REFRESH_DEBOUNCE_MS = 1_500L

        /** Hard ceiling on the whole retry sequence for one playlist load attempt. */
        const val PLAYLISTS_LOAD_BUDGET_MS = 12_000L
        const val PLAYLISTS_RETRY_INITIAL_DELAY_MS = 1_000L
        const val PLAYLISTS_RETRY_MAX_DELAY_MS = 4_000L
    }
}

