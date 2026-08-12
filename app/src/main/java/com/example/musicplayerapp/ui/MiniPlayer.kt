package com.example.musicplayerapp.ui

import android.view.View
import androidx.core.net.toUri
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
 * included.
 */
class MiniPlayer(
    private val views: ViewMiniPlayerBinding,
    private val vm: StreamsViewModel,
) {

    /** Last URL handed to Picasso, so a metadata tick does not reload the same art. */
    private var loadedArtworkUrl: String? = null

    fun bind(owner: LifecycleOwner) {
        views.miniPlayerPlayPause.setOnClickListener { vm.togglePlayPause() }

        // Every input that can change what the pill shows. They are separate
        // LiveDatas, so each one recomputes the whole projection rather than
        // patching a field - there is no partial state to get wrong.
        vm.currentStreamLive.observe(owner) { render() }
        vm.currentMyataState.observe(owner) { render() }
        vm.currentGoldState.observe(owner) { render() }
        vm.currentXtraState.observe(owner) { render() }
        vm.isPlaying.observe(owner) { render() }

        vm.currentFragmentLiveData.observe(owner) { updateVisibility() }
        vm.isInSplitMode.observe(owner) { updateVisibility() }

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
        )

        views.miniPlayerTitle.text = state.title
        views.miniPlayerArtist.text = state.artist

        views.miniPlayerPlayPause.setImageResource(
            if (state.playing) R.drawable.ic_mini_player_pause else R.drawable.ic_mini_player_play
        )
        views.miniPlayerPlayPause.contentDescription = context.getString(
            if (state.playing) R.string.mini_player_pause else R.string.mini_player_play
        )

        loadArtwork(state.artworkUrl)
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
     * The frozen design draws the pill on HOME, COLLECTION and ABOUT US, and on
     * COLLECTION's empty state too. It is absent from PLAYER, which is the one
     * screen that already shows all of this full size.
     *
     * Split-screen follows the BottomNavBar rather than the design: the bar hides
     * itself there today, and a pill floating above a bar that is not on screen
     * would be a state the design never draws.
     */
    private fun updateVisibility() {
        val onCanonicalScreen = vm.currentFragmentLiveData.value in SCREENS
        val split = vm.isInSplitMode.value == true
        views.root.visibility = if (onCanonicalScreen && !split) View.VISIBLE else View.GONE
    }

    private companion object {
        /**
         * `currentFragmentLiveData` keys for the screens the frozen design gives a
         * mini player. "donate" is deliberately absent: it is reached from inside
         * "О нас" and has no canonical frame, so there is nothing to reproduce.
         */
        val SCREENS = setOf("main", "favorites", "info")
    }
}
