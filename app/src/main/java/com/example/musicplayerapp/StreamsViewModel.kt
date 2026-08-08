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
import com.example.musicplayerapp.utils.ServiceUtils
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import com.example.musicplayerapp.data.*
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
    
    private val _historyTracks = MutableLiveData<List<HistoryTrack>>()
    val historyTracks: LiveData<List<HistoryTrack>> = _historyTracks
    private val _historyLoading = MutableLiveData<Boolean>(false)
    val historyLoading: LiveData<Boolean> = _historyLoading
    private var lastHistoryStream: String? = null

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

    private fun setupMediaController() {
        // Use context.packageName (applicationId) since it may differ from the source package
        val sessionToken = SessionToken(context, ComponentName(context.packageName, MediaPlayerService::class.java.name))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener({
            try {
                mediaController = controllerFuture.get()
                mediaController?.addListener(playerListener)
                // Initial sync
                isPlaying.postValue(mediaController?.isPlaying == true)
                isBuffering.postValue(mediaController?.playbackState == Player.STATE_BUFFERING)
            } catch (e: Exception) {
                Log.e("MediaController", "Failed to connect", e)
            }
        }, { it.run() }) // Executor
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@StreamsViewModel.isPlaying.postValue(isPlaying)
        }

        override fun onPlaybackStateChanged(state: Int) {
            isBuffering.postValue(state == Player.STATE_BUFFERING)
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

    private fun observeTrackForFavorites() {
        currentStreamLive.observeForever { stream ->
            updateFavoriteObservation()
        }
        currentMyataState.observeForever { if (currentStreamLive.value == "myata") updateFavoriteObservation() }
        currentGoldState.observeForever { if (currentStreamLive.value == "gold") updateFavoriteObservation() }
        currentXtraState.observeForever { if (currentStreamLive.value == "myata_hits") updateFavoriteObservation() }
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
                if (existing != null) {
                    favoriteDao.delete(existing)
                    feedbackRepository.reportFeedback(artist, song, stream, "DISLIKE")
                } else {
                    favoriteDao.insert(FavoriteTrack(
                        artist = artist,
                        track = song,
                        stream = stream
                    ))
                    feedbackRepository.reportFeedback(artist, song, stream, "LIKE")
                }
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
        mediaController?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
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
     */
    fun loadHistory() {
        val stream = currentStreamLive.value ?: "myata"
        
        if (lastHistoryStream != stream) {
            _historyTracks.value = emptyList()
            lastHistoryStream = stream
        }
        
        viewModelScope.launch {
            _historyLoading.value = true
            val history = historyRepository.getHistory(stream)
            _historyTracks.postValue(history.take(30))
            _historyLoading.value = false
        }
    }

    fun formUrl(songArtist: List<String>): String{
        return "https://last.fm/music/${songArtist.get(0)
            ?.lowercase()?.split(" ft.")?.get(0)!!.trim()
            .replace("/", "%2F")
            .replace(" ", "+")
        }/+images"
    }

    private companion object {
        /** Hard ceiling on the whole retry sequence for one playlist load attempt. */
        const val PLAYLISTS_LOAD_BUDGET_MS = 12_000L
        const val PLAYLISTS_RETRY_INITIAL_DELAY_MS = 1_000L
        const val PLAYLISTS_RETRY_MAX_DELAY_MS = 4_000L
    }
}

