package com.example.musicplayerapp.fragments

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.MainActivity
import com.example.musicplayerapp.R
import com.example.musicplayerapp.StreamsViewModel
import com.example.musicplayerapp.data.PlayerState
import com.example.musicplayerapp.databinding.FragmentMyataStreamBinding
import com.example.musicplayerapp.service.MediaPlayerService
import com.example.musicplayerapp.utils.ServiceUtils
import com.squareup.picasso.Picasso
import jp.wasabeef.picasso.transformations.CropCircleTransformation
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
    var stream: String = "myata"
    private var currentImageUrl: String? = null  // Track currently displayed image
    private var currentBackgroundUrl: String? = null // Track currently displayed background

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

        // Handle window insets for safe area
        if (vm.cachedTopInset != null) {
            binding.streamContentContainer.setPadding(
                binding.streamContentContainer.paddingLeft,
                vm.cachedTopInset!!,
                binding.streamContentContainer.paddingRight,
                binding.streamContentContainer.paddingBottom
            )
        }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.streamContentContainer) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            vm.cachedTopInset = bars.top
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        binding.mainAuthor.text = ""

        binding.mainAuthor.setOnClickListener { copyTrackInfoToClipboard() }
        binding.mainSong.setOnClickListener { copyTrackInfoToClipboard() }

        vm.isPlaying.observe(viewLifecycleOwner, Observer {
            // Only change button image, let isBuffering control visibility
            if(it){
                when(stream){
                    "myata"-> binding.btnPlay.setImageResource(R.drawable.pause_btn)
                    "gold"-> binding.btnPlay.setImageResource(R.drawable.pause_btn_yellow)
                    "myata_hits"-> binding.btnPlay.setImageResource(R.drawable.pause_btn_pink)
                }
            }
            else{
                when(stream){
                    "myata"-> binding.btnPlay.setImageResource(R.drawable.btn_play)
                    "gold"-> binding.btnPlay.setImageResource(R.drawable.btn_play_yellow)
                    "myata_hits"-> binding.btnPlay.setImageResource(R.drawable.btn_play_pink)
                }
            }
        })
        
        vm.isBuffering.observe(viewLifecycleOwner, Observer {
            if(it == true){
                // Show loading spinner, hide button
                binding.btnPlay.visibility = View.INVISIBLE
                binding.loadingSpinner.visibility = View.VISIBLE
            }
            else{
                // Hide loading spinner, show button
                binding.loadingSpinner.visibility = View.GONE
                binding.btnPlay.visibility = View.VISIBLE
            }
        })
        
        // Sync state logic removed - handled by improved observer

        when(stream){
            "myata"->{
                binding.backgroundImage.setImageResource(R.drawable.myata_bg_new)
                binding.loadingSpinner.indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF"))
                vm.currentMyataState.observe(viewLifecycleOwner, Observer {
                    if (it != null) {
                        updateUI(it)
                    }
                })
            }
            "gold"-> {
                binding.backgroundImage.setImageResource(R.drawable.gold_bg_new)
                binding.loadingSpinner.indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFFF00"))
                vm.currentGoldState.observe(viewLifecycleOwner, Observer {
                    if (it != null) {
                        updateUI(it)
                    }
                })
            }
            "myata_hits"->{
                binding.backgroundImage.setImageResource(R.drawable.xtra_bg_new)
                binding.loadingSpinner.indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFCCFF"))
                vm.currentXtraState.observe(viewLifecycleOwner, Observer {
                    if (it != null) {
                        updateUI(it)
                    }
                })
            }
        }

        binding.btnPlay.setOnClickListener {
            vm.togglePlayPause()
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

            when(it){
                "myata"->{
                    if(vm.isPlaying.value == false)
                        binding.btnPlay.setImageResource(R.drawable.btn_play)
                    else
                        binding.btnPlay.setImageResource(R.drawable.pause_btn)
                    
                    val color = Color.parseColor("#00E5FF")
                    binding.mainAuthor.setTextColor(color)
                    binding.btnFavorite.setColorFilter(color)
                    binding.btnHistory.setColorFilter(color)
                }
                "gold"->{
                    if(vm.isPlaying.value == false)
                        binding.btnPlay.setImageResource(R.drawable.btn_play_yellow)
                    else
                        binding.btnPlay.setImageResource(R.drawable.pause_btn_yellow)
                    
                    val color = Color.parseColor("#FFFF00")
                    binding.mainAuthor.setTextColor(color)
                    binding.btnFavorite.setColorFilter(color)
                    binding.btnHistory.setColorFilter(color)
                }
                "myata_hits"->{
                    if(vm.isPlaying.value == false)
                        binding.btnPlay.setImageResource(R.drawable.btn_play_pink)
                    else
                        binding.btnPlay.setImageResource(R.drawable.pause_btn_pink)
                    
                    val color = Color.parseColor("#FFCCFF")
                    binding.mainAuthor.setTextColor(color)
                    binding.btnFavorite.setColorFilter(color)
                    binding.btnHistory.setColorFilter(color)
                }
            }
        })



        // Navigation listeners are now handled in MainActivity
        
        // Favorite button handler
        binding.btnFavorite.setOnClickListener {
            vm.toggleCurrentFavorite()
        }
        
        // History button handler
        binding.btnHistory.setOnClickListener {
            val historyDialog = HistoryBottomSheet()
            historyDialog.show(parentFragmentManager, HistoryBottomSheet.TAG)
        }
        
        // Observe favorite status for current track from centralized VM
        vm.isCurrentFavorite.observe(viewLifecycleOwner) { isFavorite ->
            updateHeartIcon(isFavorite)
        }

        return binding.root
    }
    
    
    private fun updateHeartIcon(isFavorite: Boolean) {
        if (isFavorite) {
            binding.btnFavorite.setImageResource(R.drawable.ic_heart_filled)
        } else {
            binding.btnFavorite.setImageResource(R.drawable.ic_heart_outline)
        }
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

        Log.d("PLAYER", "resume")
        super.onResume()
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
            // Background Image Logic - Static Only
            if (currentBackgroundUrl != "STATIC") {
                currentBackgroundUrl = "STATIC"
                binding.backgroundImage.setImageResource(getStaticBackgroundRes())
            }

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

    private fun getStaticBackgroundRes(): Int {
        return when(stream) {
            "gold" -> R.drawable.gold_bg_new
            "myata_hits" -> R.drawable.xtra_bg_new
            else -> R.drawable.myata_bg_new
        }
    }
}