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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.MainActivity
import com.example.musicplayerapp.R
import com.example.musicplayerapp.StreamsViewModel
import com.example.musicplayerapp.databinding.FragmentMyataStreamBinding
import com.example.musicplayerapp.service.MediaPlayerService
import com.squareup.picasso.Picasso
import jp.wasabeef.picasso.transformations.CropCircleTransformation
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.widget.Toast


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
                binding.backgroundImage.setImageResource(R.drawable.myata_bg)
                binding.loadingSpinner.indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF"))
                vm.currentMyataState.observe(viewLifecycleOwner, Observer {
                    if (it != null) {
                        updateUI(it)
                    }
                })
            }
            "gold"-> {
                binding.backgroundImage.setImageResource(R.drawable.gold_bg)
                binding.loadingSpinner.indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFFF00"))
                vm.currentGoldState.observe(viewLifecycleOwner, Observer {
                    if (it != null) {
                        updateUI(it)
                    }
                })
            }
            "myata_hits"->{
                binding.backgroundImage.setImageResource(R.drawable.xtra_bg)
                binding.loadingSpinner.indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFCCFF"))
                vm.currentXtraState.observe(viewLifecycleOwner, Observer {
                    if (it != null) {
                        updateUI(it)
                    }
                })
            }
        }

        binding.btnPlay.setOnClickListener {
            // Show loading spinner only when starting playback, not when pausing
            if (vm.isPlaying.value == false) {
                vm.isBuffering.value = true
            }
            
            vm.ifNeedToListenReciever = true
            (activity as MainActivity).startService(
                Intent(
                    context,
                    MediaPlayerService::class.java
                ).also {
                    it.putExtra("STREAM", vm.currentStreamLive.value)
                    it.putExtra("ACTION", "startStop")
                    // Include current track metadata for notification
                    when(vm.currentStreamLive.value) {
                        "myata" -> {
                            it.putExtra("SONG", vm.currentMyataState.value?.song ?: "Radio Myata")
                            it.putExtra("ARTIST", vm.currentMyataState.value?.artist ?: "You are listening to")
                        }
                        "gold" -> {
                            it.putExtra("SONG", vm.currentGoldState.value?.song ?: "Radio Myata")
                            it.putExtra("ARTIST", vm.currentGoldState.value?.artist ?: "You are listening to")
                        }
                        "myata_hits" -> {
                            it.putExtra("SONG", vm.currentXtraState.value?.song ?: "Radio Myata")
                            it.putExtra("ARTIST", vm.currentXtraState.value?.artist ?: "You are listening to")
                        }
                    }
                })
        }

        vm.isInSplitMode.observe(viewLifecycleOwner, Observer {
            if (vm.isInSplitMode.value!!){
                binding.photo.visibility = View.GONE
                (activity as MainActivity).binding.bottomNavView.visibility = View.GONE
            }
        })

        // Remove previous sync fix as it is handled by the improved observer logic below
        
        vm.currentStreamLive.observe(viewLifecycleOwner, Observer {
            
            // Show buffering indicator ONLY when switching to a DIFFERENT stream if already playing
            if (vm.isPlaying.value == true && vm.lastObservedStream != it && vm.lastObservedStream != null) {
                vm.isBuffering.value = true
            }
            vm.lastObservedStream = it

            var intent = Intent()
            intent.setAction("switch_track")
            when(it){
                "myata"->{
                    intent.putExtra("artist",vm.currentMyataState.value?.artist)
                    intent.putExtra("song",vm.currentMyataState.value?.song)
                    if(vm.isPlaying.value == false)
                        binding.btnPlay.setImageResource(R.drawable.btn_play)
                    else
                        binding.btnPlay.setImageResource(R.drawable.pause_btn)
                    binding.mainAuthor.setTextColor(Color.parseColor("#00E5FF"))
                }
                "gold"->{
                    intent.putExtra("artist",vm.currentGoldState.value?.artist)
                    intent.putExtra("song",vm.currentGoldState.value?.song)
                    if(vm.isPlaying.value == false)
                        binding.btnPlay.setImageResource(R.drawable.btn_play_yellow)
                    else
                        binding.btnPlay.setImageResource(R.drawable.pause_btn_yellow)
                    binding.mainAuthor.setTextColor(Color.parseColor("#FFFF00"))
                }
                "myata_hits"->{
                    intent.putExtra("artist",vm.currentXtraState.value?.artist)
                    intent.putExtra("song",vm.currentXtraState.value?.song)
                    if(vm.isPlaying.value == false)
                        binding.btnPlay.setImageResource(R.drawable.btn_play_pink)
                    else
                        binding.btnPlay.setImageResource(R.drawable.pause_btn_pink)
                    binding.mainAuthor.setTextColor(Color.parseColor("#FFCCFF"))
                }
            }
            context?.let { it1 ->
                LocalBroadcastManager.getInstance(it1)
                    .sendBroadcast(intent).apply {}
            }
        })



        // Navigation listeners are now handled in MainActivity

        return binding.root
    }

    override fun onResume() {
        vm.currentFragmentLiveData.value = "player"

        updatePlayer()

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
        (activity as MainActivity).startService(
            Intent(
                context,
                MediaPlayerService::class.java
            ).also {
                it.putExtra("STREAM", vm.currentStreamLive.value)
                it.putExtra("ACTION", "switch")
                vm.ifNeedToListenReciever = false
                when(vm.currentStreamLive.value){
                    "myata"->{
                        if(vm.currentMyataState.value!!.song != null && vm.currentMyataState.value!!.artist != null) {
                            it.putExtra("SONG", vm.currentMyataState.value!!.song)
                            it.putExtra("ARTIST", vm.currentMyataState.value!!.artist)
                        }
                        else{
                            it.putExtra("SONG", "You are listening to")
                            it.putExtra("ARTIST", "Radio Myata")
                        }
                    }
                    "gold"->{
                        if(vm.currentGoldState.value!!.song != null && vm.currentGoldState.value!!.artist != null) {
                            it.putExtra("SONG", vm.currentGoldState.value!!.song)
                            it.putExtra("ARTIST", vm.currentGoldState.value!!.artist)
                        }
                        else{
                            it.putExtra("SONG", "You are listening to")
                            it.putExtra("ARTIST", "Radio Myata")
                        }
                    }
                    "myata_hits"->{
                        if(vm.currentXtraState.value!!.song != null && vm.currentXtraState.value!!.artist != null) {
                            it.putExtra("SONG", vm.currentXtraState.value!!.song)
                            it.putExtra("ARTIST", vm.currentXtraState.value!!.artist)
                        }
                        else{
                            it.putExtra("SONG", "You are listening to")
                            it.putExtra("ARTIST", "Radio Myata")
                        }
                    }
                }
            })
    }

    fun updateUI(it: StreamsViewModel.PlayerState){
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
                    binding.mainAuthor.text = "YOU ARE LISTENING"
                    binding.mainSong.text = "RADIO MYATA"
                    // Show logo placeholder immediately without animation
                    binding.photo.setImageResource(R.drawable.zaglushka_logo)
                    binding.photo.alpha = 1f
                }
            }
        }
        else {
            currentImageUrl = null
            binding.mainAuthor.text = "YOU ARE LISTENING"
            binding.mainSong.text = "RADIO MYATA"
            binding.photo.setImageResource(R.drawable.zaglushka_logo)
            binding.photo.alpha = 1f
        }
    }

    private fun copyTrackInfoToClipboard() {
        val artist = binding.mainAuthor.text.toString()
        val song = binding.mainSong.text.toString()
        if (artist.isNotBlank() && song.isNotBlank() && artist != "YOU ARE LISTENING" && song != "RADIO MYATA") {
            val textToCopy = "$artist - $song"
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Track Info", textToCopy)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Скопировано: $textToCopy", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getStaticBackgroundRes(): Int {
        return when(stream) {
            "gold" -> R.drawable.gold_bg
            "myata_hits" -> R.drawable.xtra_bg
            else -> R.drawable.myata_bg
        }
    }
}