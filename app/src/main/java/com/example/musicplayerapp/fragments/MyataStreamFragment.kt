package com.example.musicplayerapp.fragments

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.musicplayerapp.MainActivity
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.Reaction
import com.example.musicplayerapp.StreamsViewModel
import com.example.musicplayerapp.adapters.PlayerHistoryAdapter
import com.example.musicplayerapp.data.HistoryTrack
import com.example.musicplayerapp.data.PlayerState
import com.example.musicplayerapp.databinding.FragmentMyataStreamBinding
import com.example.musicplayerapp.service.MediaPlayerService
import com.example.musicplayerapp.ui.BroadcastHistoryState
import com.example.musicplayerapp.ui.PlayerControl
import com.example.musicplayerapp.ui.PlayerControlState
import kotlinx.coroutines.Job
import com.example.musicplayerapp.utils.ServiceUtils
import com.squareup.picasso.Picasso
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch


const val STREAM = "myata"

class MyataStreamFragment() : Fragment() {


    lateinit var vm: StreamsViewModel
    lateinit var binding: FragmentMyataStreamBinding
    private lateinit var playerControl: PlayerControl
    var stream: String = "myata"
    private var currentImageUrl: String? = null  // Track currently displayed image

    /** The frozen `Broadcast History Section`'s rows (Phase C). */
    private lateinit var historyAdapter: PlayerHistoryAdapter

    /**
     * How many history rows this page is showing. The only state the inline
     * section adds - the history itself stays in the ViewModel - and it is view
     * state, so it resets with the page and on a stream switch.
     */
    private var historyRevealed = BroadcastHistoryState.INITIAL_ROWS

    /** In-flight cover lookups, one per bound row, so a recycled row can cancel. */
    private val artworkJobs = mutableMapOf<HistoryTrack, Job>()

    /**
     * Keeps the page clear of the bottom navigation bar.
     *
     * The bar is the shell's, drawn over this page rather than beside it: the
     * pager is constrained to the bottom of the screen and the bar floats on top
     * of it. So the last ~76dp of the scrolled page has always been underneath the
     * bar, and the page's own 16dp bottom padding was never going to reach past
     * it. Nothing above the history noticed, because nothing above the history
     * ever scrolled to the end - but "Показать ещё" is the last thing on the page,
     * and it sits in exactly that band. See [applyBottomChromeInset].
     *
     * Held as a field so it can come off the bar again: the bar outlives this
     * fragment's view, and a listener left on it would hold the view forever.
     */
    private val bottomChromeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        applyBottomChromeInset()
    }

    /**
     * A row the reader is looking at, and where on screen it was.
     *
     * Captured before the history list changes and restored after it has laid out
     * again, so a track finishing while the reader is down among the older
     * entries does not shove them out from under the eye. See
     * [captureHistoryAnchor].
     */
    private data class HistoryAnchor(val identity: String, val offsetOnScreen: Int)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        vm = (activity as MainActivity).viewModel

        vm.currentFragmentLiveData.value = "player"
        vm.ifNeedToNavigateStraightToPlayer = false

        arguments?.takeIf { it.containsKey(STREAM) }?.apply {
            stream = getString(STREAM).toString()
        }

        binding = DataBindingUtil.inflate(
            inflater,
            R.layout.fragment_myata_stream, container, false
        )
        
        // Initialize FavoritesViewModel (No longer needed here for toggle, but maybe for history?)
        //favoritesViewModel = ViewModelProvider(this)[FavoritesViewModel::class.java]

        // The status bar inset is applied once, by PlayerFragment, on the shell that
        // holds the header and the swipe dots. The page starts below both, so
        // applying it again here would inset it twice.

        binding.mainAuthor.text = ""

        binding.mainAuthor.setOnClickListener { copyTrackInfoToClipboard() }
        binding.mainSong.setOnClickListener { copyTrackInfoToClipboard() }

        // One control for all three streams, with three faces. The frozen design
        // tints play/pause by role - `primary` on the surface, `player_play_glyph`
        // on the glyph - not by station, so the six per-stream drawables this
        // replaces have no canonical counterpart.
        //
        // Both inputs feed one projection instead of one observer owning the glyph
        // and the other owning visibility. That is what the two used to do, and it
        // is how connecting ended up hiding the whole control: the surface belongs
        // to no single input, so neither observer could be responsible for keeping
        // it on screen. PlayerControlState decides the face, PlayerControl paints
        // it, and the 80x80 surface is simply never taken away.
        //
        // The tap goes on first: setOnClickListener makes a view clickable, and
        // the observers below fire on registration, so attaching it afterwards
        // would undo a CONNECTING render that had just made the control inert.
        binding.btnPlay.setOnClickListener { vm.togglePlayPause() }
        playerControl = PlayerControl(binding.btnPlay, binding.loadingSpinner)
        vm.isPlaying.observe(viewLifecycleOwner, Observer { renderPlayerControl() })
        vm.isBuffering.observe(viewLifecycleOwner, Observer { renderPlayerControl() })

        // Sync state logic removed - handled by improved observer

        // The frozen PLAYER has no full-bleed stream artwork and no per-stream
        // accent: the screen is a flat `background` fill and every control takes a
        // semantic colour. So this only picks which stream's metadata to follow.
        when(stream){
            "myata"->{
                vm.currentMyataState.observe(viewLifecycleOwner, Observer {
                    if (it != null) {
                        updateUI(it)
                    }
                })
            }
            "gold"-> {
                vm.currentGoldState.observe(viewLifecycleOwner, Observer {
                    if (it != null) {
                        updateUI(it)
                    }
                })
            }
            "myata_hits"->{
                vm.currentXtraState.observe(viewLifecycleOwner, Observer {
                    if (it != null) {
                        updateUI(it)
                    }
                })
            }
        }

        vm.isInSplitMode.observe(viewLifecycleOwner, Observer {
            if (vm.isInSplitMode.value!!){
                binding.photo.visibility = View.GONE
                (activity as MainActivity).binding.bottomNavView.visibility = View.GONE
            }
        })

        // Remove previous sync fix as it is handled by the improved observer
        
        vm.currentStreamLive.observe(viewLifecycleOwner, Observer {

            // Show buffering indicator ONLY when switching to a DIFFERENT stream if already playing
            if (vm.isPlaying.value == true && vm.lastObservedStream != it && vm.lastObservedStream != null) {
                vm.isBuffering.value = true
            }
            vm.lastObservedStream = it

            // Nothing to re-skin per stream any more - the controls are semantic -
            // but the control still has to follow the player across a switch.
            renderPlayerControl()

            // Broadcast History is a single state in the ViewModel, keyed to
            // whichever stream is current, and all three pages of the pager hold
            // an observer on it. Only the page that IS the current stream asks
            // for a load, so a swipe costs one request rather than three; the
            // other two pages are off screen and will ask when their turn comes.
            // PlayerFragment's page callback switches the stream, so this fires
            // on every swipe as well as on the first bind.
            if (it == stream) {
                historyRevealed = BroadcastHistoryState.INITIAL_ROWS
                vm.loadHistory()
            }
        })

        setUpBroadcastHistory()

        // The bar is the Activity's and outlives this view, so the listener is
        // added here and taken off in onDestroyView.
        (activity as? MainActivity)?.binding?.bottomNavView?.let { nav ->
            nav.addOnLayoutChangeListener(bottomChromeListener)
            applyBottomChromeInset()
        }

        // Navigation listeners are now handled in MainActivity

        // The frozen `like` and `dislike`, both now real reactions.
        binding.btnFavorite.setOnClickListener {
            vm.toggleCurrentFavorite()
        }
        binding.btnDislike.setOnClickListener {
            vm.toggleCurrentDislike()
        }

        // One value drives both controls, so they cannot both read active.
        vm.currentReaction.observe(viewLifecycleOwner) { reaction ->
            updateReactionControls(reaction)
        }

        return binding.root
    }
    
    
    /**
     * The frozen `like` and `dislike`. One glyph each, always the same 24.5x23.33
     * in the same place: the frozen frame records a single visual per slot and no
     * states, so the reaction is carried by the tint and nothing moves.
     *
     * The active tint is `primary` on either side. That is the app's existing
     * "this is on" on this row, and the frozen frame gives no second one; painting
     * a dislike in some other colour would be inventing design language for a
     * screen that has been given none. They cannot both be on, because one
     * [Reaction] decides both.
     */
    private fun updateReactionControls(reaction: Reaction) {
        val liked = reaction == Reaction.LIKED
        val disliked = reaction == Reaction.DISLIKED

        ImageViewCompat.setImageTintList(
            binding.btnFavorite,
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    requireContext(),
                    if (liked) R.color.primary else R.color.player_like,
                )
            )
        )
        binding.btnFavorite.contentDescription = getString(
            if (liked) R.string.player_favorite_remove else R.string.player_favorite_add
        )

        ImageViewCompat.setImageTintList(
            binding.btnDislike,
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    requireContext(),
                    if (disliked) R.color.primary else R.color.player_control_action,
                )
            )
        )
        binding.btnDislike.contentDescription = getString(
            if (disliked) R.string.player_dislike_remove else R.string.player_dislike_add
        )
    }

    /**
     * The frozen `Broadcast History Section`, inline on the page (Phase C).
     *
     * It reads `StreamsViewModel.historyTracks` and `historyLoading` - the same
     * two the History bottom sheet reads, unchanged - and adds no state of its own
     * beyond [historyRevealed], which is how many rows this reader has asked for.
     * There is one history in the app; this is a view of it.
     */
    private fun setUpBroadcastHistory() {
        historyAdapter = PlayerHistoryAdapter(
            artworkFor = ::requestHistoryArtwork,
            cancelArtwork = ::cancelHistoryArtwork,
        )
        binding.historyList.layoutManager = LinearLayoutManager(requireContext())
        binding.historyList.adapter = historyAdapter
        // Every row it holds is measured - the list does not scroll, the page
        // does - so recycling would only churn views that all stay on screen.
        binding.historyList.setHasFixedSize(false)

        binding.historyShowMore.setOnClickListener {
            historyRevealed = BroadcastHistoryState.reveal(historyRevealed)
            renderBroadcastHistory()
        }

        vm.historyTracks.observe(viewLifecycleOwner) { renderBroadcastHistory() }
        vm.historyLoading.observe(viewLifecycleOwner) { renderBroadcastHistory() }
    }

    /**
     * Reserves the bottom navigation bar's height at the end of the page.
     *
     * Read off the bar itself rather than written down as a dimen, because its
     * height is content-sized: it grows with the font scale and with whatever
     * bottom inset the device applies, and a constant would be wrong on exactly
     * the configurations where being wrong hides the button again.
     *
     * `clipToPadding=false` is already on the scroll view, so this only extends
     * how far the page can scroll - it draws nothing and moves nothing.
     */
    private fun applyBottomChromeInset() {
        val nav = (activity as? MainActivity)?.binding?.bottomNavView ?: return
        val inset = if (nav.isVisible) nav.height else 0
        if (binding.streamScroll.paddingBottom != inset) {
            binding.streamScroll.setPadding(
                binding.streamScroll.paddingLeft,
                binding.streamScroll.paddingTop,
                binding.streamScroll.paddingRight,
                inset,
            )
        }
    }

    /**
     * Draws whatever [BroadcastHistoryState] says, from the ViewModel's history
     * and this page's reveal count. The rows themselves are the real list cut to
     * the revealed length, so "Показать ещё" moves real history and nothing else.
     */
    private fun renderBroadcastHistory() {
        val tracks = vm.historyTracks.value.orEmpty()
        val state = BroadcastHistoryState.of(
            total = tracks.size,
            isLoading = vm.historyLoading.value == true,
            revealed = historyRevealed,
        )

        binding.historyList.isVisible = state.mode == BroadcastHistoryState.Mode.POPULATED
        binding.historyEmpty.isVisible = state.mode == BroadcastHistoryState.Mode.EMPTY
        binding.historyLoading.isVisible = state.mode == BroadcastHistoryState.Mode.LOADING
        binding.historyShowMore.isVisible = state.isShowMoreVisible

        // The list is a ListAdapter over a stable identity, so a track change is
        // an insert at 0 and a drop off the tail, not a rebuild - the rows that
        // stay are the same views and nothing flashes. What DiffUtil cannot do is
        // keep them under the reader's eye: this list does not scroll, the page
        // does, so an inserted row moves everything below it down the page. The
        // anchor gives the page back the scroll the insert cost it.
        val anchor = captureHistoryAnchor()
        historyAdapter.submitList(tracks.take(state.visibleCount)) {
            if (anchor != null && view != null) {
                binding.historyList.doOnPreDraw { restoreHistoryAnchor(anchor) }
            }
        }
    }

    /**
     * Where the topmost row the reader can actually see is, or null if there is
     * nothing to hold still.
     *
     * Null when the reader is at or above the top of the list, which is the case
     * the brief calls out: from there a newly finished track is *supposed* to
     * arrive at row 1 and push the rest down, because the reader is watching the
     * head of the history and that is the event they are watching for.
     */
    private fun captureHistoryAnchor(): HistoryAnchor? {
        val list = binding.historyList
        if (!list.isVisible || list.childCount == 0) return null

        val listTop = topInScroll(list) - binding.streamScroll.scrollY
        if (listTop >= 0) return null

        for (index in 0 until list.childCount) {
            val child = list.getChildAt(index)
            val childTop = listTop + child.top
            if (childTop + child.height <= 0) continue
            val position = list.getChildAdapterPosition(child)
            val identity = historyAdapter.currentList.getOrNull(position)
                ?.let(::historyIdentity)
                ?: return null
            return HistoryAnchor(identity, childTop)
        }
        return null
    }

    /** Scrolls the page by whatever the update moved [anchor]'s row by. */
    private fun restoreHistoryAnchor(anchor: HistoryAnchor) {
        val list = binding.historyList
        val position = historyAdapter.currentList
            .indexOfFirst { historyIdentity(it) == anchor.identity }
        if (position < 0) return
        val row = list.findViewHolderForAdapterPosition(position)?.itemView ?: return

        val nowOnScreen = topInScroll(list) + row.top - binding.streamScroll.scrollY
        val delta = nowOnScreen - anchor.offsetOnScreen
        // Appending below the reader ("Показать ещё") moves nothing above it, so
        // the delta is zero and this does nothing - which is the point of
        // anchoring on a row rather than on the list's height.
        if (delta != 0) binding.streamScroll.scrollBy(0, delta)
    }

    /** [PlayerHistoryAdapter]'s own row identity, as a value this can compare. */
    private fun historyIdentity(track: HistoryTrack): String =
        "${track.playedAt} ${track.artist}"

    /** [view]'s top in the scrolling content's coordinates. */
    private fun topInScroll(view: View): Int {
        var top = 0
        var current: View = view
        while (current !== binding.streamScroll) {
            top += current.top
            current = current.parent as? View ?: return top
        }
        return top
    }

    /**
     * A cover for one history row.
     *
     * [com.example.musicplayerapp.data.HistoryTrack] has no artwork of its own, so
     * this goes through the ViewModel to ArtworkRepository, which derives one from
     * the artist and track and caches it. Only bound rows ask, so the reveal step
     * is what bounds how many lookups a single tap can start.
     *
     * The job is held so a recycled row can withdraw its request, and the whole
     * map is scoped to the view: a page that goes away takes its lookups with it.
     */
    private fun requestHistoryArtwork(track: HistoryTrack, onResult: (String?) -> Unit) {
        artworkJobs.remove(track)?.cancel()
        artworkJobs[track] = viewLifecycleOwner.lifecycleScope.launch {
            val url = vm.historyArtworkUrl(track)
            artworkJobs.remove(track)
            onResult(url)
        }
    }

    private fun cancelHistoryArtwork(track: HistoryTrack) {
        artworkJobs.remove(track)?.cancel()
    }

    /**
     * The frozen play/pause: one 80x80 surface that stays put, and one of three
     * faces inside it. Both inputs are the service's own, read through the
     * MediaController that [StreamsViewModel] already holds.
     */
    private fun renderPlayerControl() {
        playerControl.render(
            PlayerControlState.of(
                isPlaying = vm.isPlaying.value == true,
                isBuffering = vm.isBuffering.value == true,
            )
        )
    }

    override fun onResume() {
        vm.currentFragmentLiveData.value = "player"

        // Removed updatePlayer() - syncing is now handled by MediaController

        when(stream){
            "myata"->{
                vm.currentMyataState.value?.let { updateUI(it) }
            }
            "gold"-> {
                vm.currentGoldState.value?.let { updateUI(it) }
            }
            "myata_hits"->{
                vm.currentXtraState.value?.let { updateUI(it) }
            }
        }

        if (!vm.isInSplitMode.value!!){
            binding.photo.visibility = View.VISIBLE
            (activity as MainActivity).binding.bottomNavView.visibility = View.VISIBLE
        }

        // The line above can have just turned the bar back on, and split mode can
        // have turned it off while this page was away.
        applyBottomChromeInset()

        Log.d("PLAYER", "resume")
        super.onResume()
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.binding?.bottomNavView
            ?.removeOnLayoutChangeListener(bottomChromeListener)
        super.onDestroyView()
    }

    fun updatePlayer(){
        val streamToSync = vm.currentStreamLive.value
        val artistToSync: String
        val songToSync: String
        
        when(streamToSync) {
            "myata" -> {
                artistToSync = vm.currentMyataState.value?.artist ?: getString(R.string.slogan_placeholder)
                songToSync = vm.currentMyataState.value?.song ?: getString(R.string.brand_name)
            }
            "gold" -> {
                artistToSync = vm.currentGoldState.value?.artist ?: getString(R.string.slogan_placeholder)
                songToSync = vm.currentGoldState.value?.song ?: getString(R.string.brand_name)
            }
            "myata_hits" -> {
                artistToSync = vm.currentXtraState.value?.artist ?: getString(R.string.slogan_placeholder)
                songToSync = vm.currentXtraState.value?.song ?: getString(R.string.brand_name)
            }
            else -> {
                artistToSync = getString(R.string.slogan_placeholder)
                songToSync = getString(R.string.brand_name)
            }
        }
        
        ServiceUtils.safeStartService(requireContext(), "switch", streamToSync, artistToSync, songToSync)
    }

    fun updateUI(it: PlayerState){
        if (it != null) {
            if(it.artist!=null) {
                if(!it.artist!!.isBlank()) {
                    if (it.img != null && !it.img!!.isBlank() && it.img != "NO_IMAGE") {
                        // Only reload image if URL has changed
                    if (currentImageUrl != it.img) {
                        currentImageUrl = it.img
                        
                        // Only animate if we haven't animated this URL yet
                        if (vm.lastAnimatedImageUrl != it.img) {
                            vm.lastAnimatedImageUrl = it.img
                            // Load new image directly without placeholder to keep old image visible
                            binding.photo.alpha = 1f
                            Picasso.get()
                                .load(Uri.parse(it.img))
                                .noPlaceholder()
                                .error(R.drawable.zaglushka_logo)
                                .fit()
                                .centerCrop()
                                .into(binding.photo, object : com.squareup.picasso.Callback {
                                    override fun onSuccess() {
                                        Log.d("Picasso", "Image loaded successfully: ${it.img}")
                                    }
                                    override fun onError(e: Exception?) {
                                        currentImageUrl = "NO_IMAGE"
                                        binding.photo.setImageResource(R.drawable.zaglushka_logo)
                                        Log.e("Picasso", "Error loading image: ${it.img}", e)
                                    }
                                })
                        } else {
                            // Already animated this URL - just load without placeholder
                            binding.photo.alpha = 1f
                            Picasso.get()
                                .load(Uri.parse(it.img))
                                .noPlaceholder()
                                .error(R.drawable.zaglushka_logo)
                                .fit()
                                .centerCrop()
                                .into(binding.photo, object : com.squareup.picasso.Callback {
                                    override fun onError(e: Exception?) {
                                        currentImageUrl = "NO_IMAGE"
                                        binding.photo.setImageResource(R.drawable.zaglushka_logo)
                                        Log.e("Picasso", "Error loading image: ${it.img}", e)
                                    }

                                    override fun onSuccess() {
                                        // Image loaded successfully
                                    }
                                })
                        }
                    } else {
                        // Same image - ensure alpha is 1f (in case it was changed)
                        binding.photo.alpha = 1f
                    }
                    } else if (it.img == "NO_IMAGE") {
                         // No image found by API - show logo placeholder
                         // Always force update if current is not NO_IMAGE or if it's null
                         if (currentImageUrl != "NO_IMAGE") {
                             currentImageUrl = "NO_IMAGE"
                             binding.photo.setImageResource(R.drawable.zaglushka_logo)
                             binding.photo.alpha = 1f
                         }
                    }
                    // For null/blank img during track transitions, keep existing image
                    // Picasso will handle placeholder during loading

                    binding.mainSong.text = it.song
                    binding.mainAuthor.text = it.artist
                    
                }
                else{
                    currentImageUrl = null
                    binding.mainAuthor.text = getString(R.string.slogan_placeholder)
                    binding.mainSong.text = getString(R.string.brand_name)
                    // Show logo placeholder immediately without animation
                    binding.photo.setImageResource(R.drawable.zaglushka_logo)
                    binding.photo.alpha = 1f
                }
            }
        }
        else {
            currentImageUrl = null
            binding.mainAuthor.text = getString(R.string.slogan_placeholder)
            binding.mainSong.text = getString(R.string.brand_name)
            binding.photo.setImageResource(R.drawable.zaglushka_logo)
            binding.photo.alpha = 1f
        }
    }

    private fun copyTrackInfoToClipboard() {
        val artist = binding.mainAuthor.text.toString()
        val song = binding.mainSong.text.toString()
        val slogan = getString(R.string.slogan_placeholder)
        val brand = getString(R.string.brand_name)

        if (artist.isNotBlank() && song.isNotBlank() && artist != slogan && song != brand) {
            val textToCopy = "$artist - $song"
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.track_info_clip), textToCopy)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.copy_toast, textToCopy), Toast.LENGTH_SHORT).show()
        }
    }

}