package com.example.musicplayerapp.ui

import android.view.View
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.lifecycle.LifecycleOwner
import com.example.musicplayerapp.R
import com.example.musicplayerapp.StreamsViewModel
import com.example.musicplayerapp.databinding.ViewMiniPlayerBinding
import com.squareup.picasso.Picasso

/**
 * Binds the frozen 3.6.6 Mini Player to the playback state the app already has.
 *
 * There is exactly one pill, it belongs to the shell, and it reads
 * `StreamsViewModel` - the same object the player screen reads and the only
 * holder of the MediaController. So the pill's title, artist and play/pause icon
 * cannot disagree with the player, the notification or the lock screen, and
 * navigating between screens neither rebuilds it nor touches playback: the
 * fragments change underneath a view that never goes away.
 *
 * Nothing here decides *what* plays. The button forwards to
 * [StreamsViewModel.togglePlayPause], which is the existing path, queueing
 * included. [onOpenPlayer] is the shell's own navigation to PLAYER - the same
 * call the bottom navigation makes, not a second route to the same screen.
 */
class MiniPlayer(
    private val views: ViewMiniPlayerBinding,
    private val vm: StreamsViewModel,
    private val onOpenPlayer: () -> Unit,
) {

    /** Last URL handed to Picasso, so a metadata tick does not reload the same art. */
    private var loadedArtworkUrl: String? = null

    fun bind(owner: LifecycleOwner) {
        views.miniPlayerPlayPause.setOnClickListener { vm.togglePlayPause() }

        // The body - artwork and metadata included - opens PLAYER. The button is a
        // child with its own listener, so it consumes its own taps and a
        // play/pause never reaches this one; that is the whole mechanism, and
        // MiniPlayerContractTest holds it in place.
        views.root.setOnClickListener { onOpenPlayer() }

        // TalkBack announces the pill's own click as "open the player" instead of
        // the generic "activate", while the button keeps its separate node and its
        // own play/pause description. Relabelling only: the null command leaves
        // the behaviour exactly as the listener above defines it.
        ViewCompat.replaceAccessibilityAction(
            views.root,
            AccessibilityActionCompat.ACTION_CLICK,
            views.root.context.getString(R.string.mini_player_open),
            null,
        )

        // Every input that can change what the pill shows. They are separate
        // LiveDatas, so each one recomputes the whole projection rather than
        // patching a field - there is no partial state to get wrong.
        vm.currentStreamLive.observe(owner) { render() }
        vm.currentMyataState.observe(owner) { render() }
        vm.currentGoldState.observe(owner) { render() }
        vm.currentXtraState.observe(owner) { render() }
        vm.isPlaying.observe(owner) { render() }
        // The connecting face. Media3's own STATE_BUFFERING, via the same
        // LiveData the PLAYER's central control reads - there is no timer here
        // and nothing that decides on its own how long "connecting" lasts.
        vm.isBuffering.observe(owner) { render() }

        vm.currentFragmentLiveData.observe(owner) { updateVisibility() }
        vm.isInSplitMode.observe(owner) { updateVisibility() }
        vm.hasPlaybackSession.observe(owner) { updateVisibility() }

        render()
        updateVisibility()
    }

    private fun render() {
        val context = views.root.context
        val state = MiniPlayerUiState.from(
            stream = vm.currentStreamLive.value,
            myata = vm.currentMyataState.value,
            gold = vm.currentGoldState.value,
            xtra = vm.currentXtraState.value,
            isPlaying = vm.isPlaying.value == true,
            fallbackTitle = context.getString(R.string.brand_name),
            fallbackArtist = context.getString(R.string.slogan_placeholder),
            isBuffering = vm.isBuffering.value == true,
        )

        views.miniPlayerTitle.text = state.title
        views.miniPlayerArtist.text = state.artist

        renderControl(state.control)
        loadArtwork(state.artworkUrl)
    }

    /**
     * The play/pause slot: one of three faces, in one place.
     *
     * Connecting swaps the glyph for the spinner rather than adding anything
     * beside it, so the 27x48 slot, the row's widths and the pill's height are
     * the same in all three states.
     *
     * The button stops firing while connecting, which is what keeps a repeated
     * tap from becoming a second start: `togglePlayPause` reads the controller's
     * `isPlaying`, which is still false mid-connect, so a second tap would take
     * the not-playing branch and `prepare()` a player that is already connecting.
     *
     * Disabled, and deliberately still *clickable*. A view that is disabled but
     * clickable consumes the touch and declines to act on it; one that is merely
     * not clickable does not consume it at all, and here the untaken touch would
     * carry straight up to the pill - whose listener opens PLAYER. Turning the
     * button off must not turn it into a navigation control.
     */
    private fun renderControl(state: PlayerControlState) {
        val context = views.root.context
        val connecting = state == PlayerControlState.CONNECTING

        views.miniPlayerPlayPause.isClickable = true
        views.miniPlayerPlayPause.isEnabled = !connecting
        views.miniPlayerSpinner.visibility = if (connecting) View.VISIBLE else View.GONE
        views.miniPlayerPlayPause.setImageDrawable(null)

        if (!connecting) {
            views.miniPlayerPlayPause.setImageResource(
                if (state == PlayerControlState.PAUSE) {
                    R.drawable.ic_mini_player_pause
                } else {
                    R.drawable.ic_mini_player_play
                }
            )
        }

        views.miniPlayerPlayPause.contentDescription = context.getString(
            when (state) {
                PlayerControlState.CONNECTING -> R.string.player_connecting
                PlayerControlState.PAUSE -> R.string.mini_player_pause
                PlayerControlState.PLAY -> R.string.mini_player_play
            }
        )
    }

    private fun loadArtwork(url: String?) {
        if (url == loadedArtworkUrl) return
        loadedArtworkUrl = url

        if (url == null) {
            views.miniPlayerArtwork.setImageResource(R.drawable.zaglushka_logo)
            return
        }
        // noPlaceholder so the previous cover stays put while the next one
        // decodes, which is what the player screen does and what stops the pill
        // flashing the logo on every track change.
        Picasso.get()
            .load(url.toUri())
            .noPlaceholder()
            .error(R.drawable.zaglushka_logo)
            .fit()
            .centerCrop()
            .into(views.miniPlayerArtwork)
    }

    /**
     * The contract lives in [MiniPlayerVisibility]; this only feeds it the three
     * live inputs. Split-screen is the one condition that comes from the app
     * rather than the design: the BottomNavBar hides itself there today, and a
     * pill floating above a bar that is not on screen would be a state nothing
     * draws.
     */
    private fun updateVisibility() {
        val show = MiniPlayerVisibility.shouldShow(
            screen = vm.currentFragmentLiveData.value,
            inSplitMode = vm.isInSplitMode.value == true,
            hasPlaybackSession = vm.hasPlaybackSession.value == true,
        )
        views.root.visibility = if (show) View.VISIBLE else View.GONE
    }
}
