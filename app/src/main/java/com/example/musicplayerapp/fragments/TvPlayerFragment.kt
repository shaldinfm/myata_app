package com.example.musicplayerapp.fragments

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.musicplayerapp.StreamsViewModel.PlayerState
import com.example.musicplayerapp.R
import com.example.musicplayerapp.StreamsViewModel
import com.example.musicplayerapp.databinding.FragmentTvPlayerBinding
import android.util.Log
import com.example.musicplayerapp.service.MediaPlayerService
import com.squareup.picasso.Picasso

class TvPlayerFragment : Fragment() {

    private var _binding: FragmentTvPlayerBinding? = null
    private val binding get() = _binding!!
    private val vm: StreamsViewModel by activityViewModels()
    private var currentColor: Int = Color.BLACK
    
    // Track previous track info to avoid re-animating unchanged content
    private var previousTrackInfo: String = ""
    private var currentImageUrl: String? = null
    private var currentTarget: com.squareup.picasso.Target? = null

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
            val intent = Intent(context, MediaPlayerService::class.java).apply {
                putExtra("STREAM", vm.currentStreamLive.value)
                putExtra("ACTION", "startStop")
                val currentState = getCurrentState()
                putExtra("SONG", currentState?.song ?: "Radio Myata")
                putExtra("ARTIST", currentState?.artist ?: "You are listening to")
            }
            activity?.startService(intent)
        }

        binding.btnStreamMyata.setOnClickListener { 
            switchStream("myata")
        }
        binding.btnStreamGold.setOnClickListener { 
            switchStream("gold")
        }
        binding.btnStreamXtra.setOnClickListener { 
            switchStream("myata_hits")
        }
    }

    private fun switchStream(stream: String) {
        if (vm.currentStreamLive.value != stream) {
            // Immediate feedback: Show spinner while service prepares stream
            vm.isBuffering.value = true
            
            vm.currentStreamLive.value = stream
            vm.triggerMetadataUpdate()
            // Auto-play: Send play intent immediately
            val intent = Intent(context, MediaPlayerService::class.java).apply {
                putExtra("STREAM", stream)
                putExtra("ACTION", "play") 
            }
            activity?.startService(intent)
        } else {
             // If clicking the same stream, ensure it plays if paused
             if (vm.isPlaying.value != true) {
                val intent = Intent(context, MediaPlayerService::class.java).apply {
                    putExtra("STREAM", stream)
                    putExtra("ACTION", "play")
                }
                activity?.startService(intent)
             }
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
            binding.btnPlayPause.setImageResource(if (isPlaying) R.drawable.btn_pause_tv else R.drawable.btn_play_tv)
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

    private fun updateTheme(stream: String) {
        val yellow = Color.parseColor("#FFFF00")
        val white = Color.WHITE

        binding.btnStreamMyata.setTextColor(if (stream == "myata") yellow else white)
        binding.btnStreamGold.setTextColor(if (stream == "gold") yellow else white)
        binding.btnStreamXtra.setTextColor(if (stream == "myata_hits") yellow else white)
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
        if (state.img != null && state.img != "NO_IMAGE") {
            // Track URL change to force color extraction even from cache
            val imageUrlChanged = currentImageUrl != state.img
            
            // CRITICAL: Only load if URL changed to prevent duplicate loads
            if (imageUrlChanged) {
                currentImageUrl = state.img
                
                // CRITICAL: Create and store Target to prevent garbage collection
                val target = object : com.squareup.picasso.Target {
                    override fun onBitmapLoaded(bitmap: android.graphics.Bitmap?, from: Picasso.LoadedFrom?) {
                        if (_binding == null) return
                        
                        // Fade in the new image
                        binding.ivAlbumArt.alpha = 0f
                        binding.ivAlbumArt.setImageBitmap(bitmap)
                        binding.ivAlbumArt.animate()
                            .alpha(1f)
                            .setDuration(500)
                            .start()
                        
                        // Always extract colors
                        bitmap?.let { extractColorsAndApply(it) }
                    }
                    override fun onBitmapFailed(e: Exception?, errorDrawable: android.graphics.drawable.Drawable?) {
                        if (_binding == null) return
                        binding.ivAlbumArt.setImageResource(R.drawable.zaglushka_logo)
                        binding.ivAlbumArt.alpha = 1f
                    }
                    override fun onPrepareLoad(placeHolderDrawable: android.graphics.drawable.Drawable?) {
                        if (_binding == null) return
                        binding.ivAlbumArt.setImageDrawable(placeHolderDrawable)
                    }
                }
                
                // Store reference to prevent GC
                currentTarget = target
                
                Picasso.get()
                    .load(state.img)
                    .noPlaceholder()
                    .error(R.drawable.zaglushka_logo)
                    .into(target)
            }
        } else {
            currentImageUrl = null
            currentTarget = null
            binding.ivAlbumArt.setImageResource(R.drawable.zaglushka_logo)
            binding.ivAlbumArt.alpha = 1f
            animateBackgroundColor(Color.parseColor("#2A2A2A"))
        }
    }

    private fun extractColorsAndApply(bitmap: android.graphics.Bitmap) {
        androidx.palette.graphics.Palette.from(bitmap).generate { palette ->
            if (palette == null || _binding == null) return@generate

            val dominantSwatch = palette.dominantSwatch
            val vibrantSwatch = palette.vibrantSwatch
            
            val color = vibrantSwatch?.rgb ?: dominantSwatch?.rgb ?: Color.parseColor("#2A2A2A")
            animateBackgroundColor(adjustForBackground(color))
        }
    }
    
    private fun adjustForBackground(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        
        // Darken for background
        hsv[2] = hsv[2] * 0.4f
        
        return Color.HSVToColor(hsv)
    }

    private fun animateBackgroundColor(targetColor: Int) {
        if (_binding == null) return
        
        val colorFrom = currentColor
        val colorTo = targetColor
        
        val colorAnimation = android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(), colorFrom, colorTo)
        colorAnimation.duration = 1000
        colorAnimation.addUpdateListener { animator ->
            if (_binding == null) return@addUpdateListener
            val color = animator.animatedValue as Int
            binding.ivBackground.setImageDrawable(android.graphics.drawable.ColorDrawable(color))
            currentColor = color
        }
        colorAnimation.start()
    }

    override fun onDestroyView() {
        hideHandler.removeCallbacksAndMessages(null)
        currentTarget?.let { Picasso.get().cancelRequest(it) }
        currentImageUrl = null
        previousTrackInfo = ""
        super.onDestroyView()
        _binding = null
    }
}
