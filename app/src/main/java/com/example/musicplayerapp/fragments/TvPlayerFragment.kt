package com.example.musicplayerapp.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.palette.graphics.Palette
import com.example.musicplayerapp.data.PlayerState
import com.example.musicplayerapp.R
import com.example.musicplayerapp.StreamsViewModel
import com.example.musicplayerapp.databinding.FragmentTvPlayerBinding
import android.util.Log
import com.example.musicplayerapp.data.NowPlayingArtwork
import com.example.musicplayerapp.service.MediaPlayerService
import com.example.musicplayerapp.ui.CoverArt
import com.example.musicplayerapp.ui.tv.TvAmbientPalette
import com.example.musicplayerapp.ui.tv.TvAmbientPolicy
import com.example.musicplayerapp.ui.tv.TvAmbientSwatch
import com.squareup.picasso.Picasso

class TvPlayerFragment : Fragment() {

    private var _binding: FragmentTvPlayerBinding? = null
    private val binding get() = _binding!!
    private val vm: StreamsViewModel by activityViewModels {
        com.example.musicplayerapp.StreamsViewModelFactory(requireActivity().application, requireActivity())
    }
    
    // Track previous track info to avoid re-animating unchanged content
    private var previousTrackInfo: String = ""
    private var currentImageUrl: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTvPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvTrackInfo.isSelected = true // Enable marquee

        setupObservers()
        setupListeners()

        // Auto-play if not already playing
        if (vm.isPlaying.value != true) {
            val intent = Intent(context, MediaPlayerService::class.java).apply {
                putExtra("STREAM", vm.currentStreamLive.value)
                putExtra("ACTION", "startStop")
            }
            activity?.startService(intent)
        }
        
        setupFocus(binding.btnBack)
        setupFocus(binding.btnPlayPause)
        setupFocus(binding.btnStreamMyata)
        setupFocus(binding.btnStreamGold)
        setupFocus(binding.btnStreamXtra)

        binding.btnPlayPause.requestFocus()
        
        setupAutoHide()
    }
    
    // Use ObjectAnimator for distinct alpha control to avoid conflict with ViewPropertyAnimator (scale)
    private var alphaAnimator: android.animation.ObjectAnimator? = null
    
    // PUBLIC so Activity can call it
    fun showBackButton() {
        // Force visibility reset
        binding.btnBack.visibility = View.VISIBLE
        
        // Cancel logic
        alphaAnimator?.cancel()
        hideHandler.removeCallbacks(hideRunnable)

        // Animate Alpha independently
        if (binding.btnBack.alpha < 1f) {
            alphaAnimator = android.animation.ObjectAnimator.ofFloat(binding.btnBack, "alpha", 1f).apply {
                duration = 200
                start()
            }
        } else {
            binding.btnBack.alpha = 1f
        }
        
        rescheduleHide()
    }
    
    private val hideRunnable = Runnable { 
        if (!binding.btnBack.hasFocus()) {
            // Use ObjectAnimator for fade out
            alphaAnimator?.cancel()
            alphaAnimator = android.animation.ObjectAnimator.ofFloat(binding.btnBack, "alpha", 0f).apply {
                duration = 500
                start()
            }
        }
    }
    
    private fun rescheduleHide() {
        hideHandler.removeCallbacks(hideRunnable)
        if (!binding.btnBack.hasFocus()) {
            hideHandler.postDelayed(hideRunnable, 4000) // 4 seconds timeout
        }
    }

    private fun setupAutoHide() {
        showBackButton()
        // Listeners in Activity will trigger this too
    }
    
    private val hideHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun setupListeners() {
        val backClickListener = View.OnClickListener {
            try {
                android.util.Log.d("TvPlayerFragment", "Back button clicked - popping backstack")
                
                // Correctly pop backstack to return to Selection (decrementing count)
                parentFragmentManager.popBackStack()
                    
            } catch (e: Exception) {
                android.util.Log.e("TvPlayerFragment", "Error handling back click", e)
            }
        }

        binding.btnBack.setOnClickListener(backClickListener)
        
        // CRITICAL FIX: Explicitly handle ENTER/DPAD_CENTER to prevent app exit
        binding.btnBack.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && 
               (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
                android.util.Log.d("TvPlayerFragment", "Back button KEY event - triggering click manually")
                backClickListener.onClick(binding.btnBack)
                return@setOnKeyListener true // Consume event!
            }
            false
        }

        binding.btnPlayPause.setOnClickListener {
            vm.togglePlayPause()
        }

        binding.btnStreamMyata.setOnClickListener { 
            vm.switchStream("myata")
        }
        binding.btnStreamGold.setOnClickListener { 
            vm.switchStream("gold")
        }
        binding.btnStreamXtra.setOnClickListener { 
            vm.switchStream("myata_hits")
        }
    }


    private fun setupFocus(view: View) {
        view.setOnFocusChangeListener { v, hasFocus ->
            // Scale Animation using ViewPropertyAnimator (animate())
            // This is now SAFE because alpha is handled by separate ObjectAnimator
            if (hasFocus) {
                v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(200).start()
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
            }

            // Special handling for Back button visibility
            if (v == binding.btnBack) {
                if (hasFocus) {
                    showBackButton()
                } else {
                    rescheduleHide()
                }
            }
        }
    }

    private fun getCurrentState(): PlayerState? {
        return when(vm.currentStreamLive.value) {
            "gold" -> vm.currentGoldState.value
            "myata_hits" -> vm.currentXtraState.value
            else -> vm.currentMyataState.value
        }
    }

    private fun setupObservers() {
        vm.currentStreamLive.observe(viewLifecycleOwner) { stream ->
            updateTheme(stream)
            vm.lastObservedStream = stream
            getCurrentState()?.let { updateUI(it) }
        }

        vm.currentMyataState.observe(viewLifecycleOwner) { if (vm.currentStreamLive.value == "myata") updateUI(it) }
        vm.currentGoldState.observe(viewLifecycleOwner) { if (vm.currentStreamLive.value == "gold") updateUI(it) }
        vm.currentXtraState.observe(viewLifecycleOwner) { if (vm.currentStreamLive.value == "myata_hits") updateUI(it) }

        vm.isPlaying.observe(viewLifecycleOwner) { 
            updatePlayButtonState()
        }
        vm.isBuffering.observe(viewLifecycleOwner) { updatePlayButtonState() }
    }

    private fun updatePlayButtonState() {
        val isPlaying = vm.isPlaying.value == true
        val isBuffering = vm.isBuffering.value == true

        if (isBuffering) {
            binding.loadingSpinner.visibility = View.VISIBLE
            // Перенести фокус на активный стрим, если кнопка была в фокусе
            if (binding.btnPlayPause.hasFocus()) {
                when(vm.currentStreamLive.value) {
                    "gold" -> binding.btnStreamGold.requestFocus()
                    "myata_hits" -> binding.btnStreamXtra.requestFocus()
                    else -> binding.btnStreamMyata.requestFocus()
                }
            }
            binding.btnPlayPause.visibility = View.INVISIBLE
        } else {
        binding.loadingSpinner.visibility = View.GONE
            binding.btnPlayPause.visibility = View.VISIBLE
            binding.btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_tv_pause else R.drawable.ic_tv_play)
            // Восстановить фокус на кнопку, если ничего не в фокусе
            if (binding.root.findFocus() == null) {
                binding.btnPlayPause.requestFocus()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Восстановить фокус при возврате к фрагменту
        view?.post {
            if (_binding != null && binding.root.findFocus() == null) {
                binding.btnPlayPause.requestFocus()
            }
        }
    }

    /**
     * Which station is playing, as view state rather than as a colour applied by
     * hand.
     *
     * The pills' selected look - the yellow label and, since the Material 3
     * treatment, the accent container under it - is a state list now, so the only
     * thing this fragment has to say is *which* pill is selected. Focus stays a
     * separate axis: a pill can be focused without being selected, and the styles
     * keep those two states visibly different, which is the whole point on a
     * remote.
     */
    private fun updateTheme(stream: String) {
        binding.btnStreamMyata.isSelected = stream == "myata"
        binding.btnStreamGold.isSelected = stream == "gold"
        binding.btnStreamXtra.isSelected = stream == "myata_hits"
    }

    private fun updateUI(state: PlayerState?) {
        if (state == null) return
        
        val newTrackInfo = "${state.artist} - ${state.song}"
        
        // Only animate if track info actually changed
        if (newTrackInfo != previousTrackInfo) {
            previousTrackInfo = newTrackInfo
            
            // Crossfade animation: fade out → update text → fade in
            binding.tvTrackInfo.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    // CRITICAL: Check binding is still valid after animation completes
                    // Fragment may be destroyed while animation was running
                    if (_binding == null) return@withEndAction
                    
                    binding.tvTrackInfo.text = newTrackInfo
                    binding.tvTrackInfo.animate()
                        .alpha(1f)
                        .setDuration(250)
                        .start()
                }
                .start()
        } else {
            // Same track - ensure text is visible without animation
            binding.tvTrackInfo.text = newTrackInfo
            binding.tvTrackInfo.alpha = 1f
        }

        // Handle Album Art
        //
        // Rendering is the phone's rule, not a TV one: CoverArt takes the previous
        // track's cover down as soon as the artwork answer changes and stands the
        // plate up until the new cover has decoded, so a finished track's artwork
        // can never sit under the new title while its lookup runs. This fragment
        // used to keep whatever bitmap was on the view and only replace it when
        // Picasso delivered the next one, which is the one way TV could show a
        // cover for a track that is no longer playing - same resolver, same URL,
        // different paint rule.
        //
        // What stays TV-only is what happens *with* the bitmap: the ambient
        // background is still derived from it, and now from the image the view is
        // actually showing rather than from a second full-resolution copy.
        val hadNoCover = NowPlayingArtwork.coverUrl(state.img) == null

        currentImageUrl = CoverArt.render(
            view = binding.ivAlbumArt,
            img = state.img,
            loaded = currentImageUrl,
            onLoaded = { bitmap ->
                if (_binding != null) extractColorsAndApply(bitmap)
            },
        ) {
            // The plate is already up; dropping the URL is what lets a later state
            // try the same cover again instead of treating it as already on screen.
            currentImageUrl = null
            // The lookup failed, so no palette is coming for this track: the plate
            // is what the viewer gets, and the background goes to the brand field
            // rather than staying on the previous track's colours.
            if (_binding != null) animateAmbient(TvAmbientPolicy.FALLBACK)
        }

        if (state.img == NowPlayingArtwork.NO_IMAGE) {
            // The resolver looked and found nothing: the plate is up, so the
            // background returns to the brand field rather than keeping the
            // previous track's extraction.
            animateAmbient(TvAmbientPolicy.FALLBACK)
        } else if (hadNoCover) {
            // Pending artwork on a just-announced track. The cover is already down
            // (CoverArt did that); the background deliberately stays as it is until
            // the new cover's own colours arrive, which is what stops it flickering
            // once per poll.
            Log.d("TvPlayerFragment", "Waiting for the artwork lookup - plate is up")
        }
    }

    /**
     * The cover decoded: hand its palette to the ambient field.
     *
     * This runs once per decoded cover, never per frame and never per metadata
     * tick - `CoverArt.render` returns early when the URL has not changed, so the
     * callback only fires for a cover that is genuinely new. The swatches are the
     * five the design names; which of them actually become areas of the field is
     * [TvAmbientPolicy]'s decision, not this fragment's, and the field is left
     * alone entirely when the palette reduces to what is already on screen.
     */
    private fun extractColorsAndApply(bitmap: android.graphics.Bitmap) {
        Palette.from(bitmap).generate { palette ->
            if (palette == null || _binding == null) return@generate

            val swatches = listOfNotNull(
                palette.vibrantSwatch,
                palette.darkVibrantSwatch,
                palette.mutedSwatch,
                palette.darkMutedSwatch,
                palette.dominantSwatch,
            ).map { TvAmbientSwatch(it.rgb, it.population) }

            // The shares are taken against every swatch the palette found, not
            // just these five, so "5% of the cover" means the cover.
            val ambient = TvAmbientPolicy.fromSwatches(
                swatches = swatches,
                totalPopulation = palette.swatches.sumOf { it.population },
            )

            // Which field this cover produced, for the next person to look at a
            // screenshot and wonder why it came out pink.
            Log.d(
                "TvPlayerFragment",
                "Ambient field: ${if (ambient.isFallback) "MYATA fallback" else "artwork"} " +
                    ambient.colors.joinToString(" ") { "#%06X".format(java.util.Locale.ROOT, it and 0xFFFFFF) },
            )
            animateAmbient(ambient)
        }
    }

    private fun animateAmbient(palette: TvAmbientPalette) {
        if (_binding == null) return

        binding.viewAmbient.setAmbientPalette(palette)
    }

    override fun onDestroyView() {
        hideHandler.removeCallbacksAndMessages(null)
        alphaAnimator?.cancel()
        // Cancel any running animations to prevent callbacks after binding is null
        binding.tvTrackInfo.animate().cancel()
        binding.ivAlbumArt.animate().cancel()
        // The load itself belongs to the shared renderer, which cancels on the view.
        Picasso.get().cancelRequest(binding.ivAlbumArt)
        // The drift and any palette crossfade belong to the view; this is the
        // explicit end of them, alongside the detach that follows.
        binding.viewAmbient.stopAmbient()
        currentImageUrl = null
        previousTrackInfo = ""
        super.onDestroyView()
        _binding = null
    }
}
