package com.example.musicplayerapp.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.musicplayerapp.MainActivity
import com.example.musicplayerapp.R
import com.example.musicplayerapp.StreamsViewModel
import com.example.musicplayerapp.adapters.PlaylistAdapter
import com.example.musicplayerapp.databinding.FragmentMainBinding
import com.example.musicplayerapp.service.MediaPlayerService


class MainFragment : Fragment() {

    lateinit var binding: FragmentMainBinding
    lateinit var vm: StreamsViewModel


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        vm = (activity as MainActivity).viewModel

        vm.currentFragmentLiveData.value = "main"

        if(vm.ifNeedToNavigateStraightToPlayer){
            findNavController().navigate(R.id.player, Bundle().apply {
                when(vm.currentStreamLive.value){
                    "myata"->putInt(CURRENT_ITEM, 0)
                    "gold"->putInt(CURRENT_ITEM, 1)
                    "myata_hits"->putInt(CURRENT_ITEM, 2)
                }
            })
        }

        binding = DataBindingUtil.inflate(
            inflater,
            R.layout.fragment_main, container, false
        )

        // Handle window insets for safe area (notch/status bar)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.mainContentContainer) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        binding.playlists.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.playlists.adapter = vm.playlistList.value?.let { PlaylistAdapter(it, { position -> onItemClick(position)}) }

        // Navigation listeners are now handled in MainActivity

        binding.myataStreamBanner.setOnClickListener {
            // Check if already playing this stream to avoid infinite spinner
            if (vm.currentStreamLive.value != "myata" || vm.isPlaying.value != true) {
                vm.isBuffering.value = true
            }
            
            vm.currentStreamLive.value = "myata"
            vm.triggerMetadataUpdate()
            (activity as MainActivity).startService(
                Intent(context, MediaPlayerService::class.java).also {
                    it.putExtra("STREAM", "myata")
                    it.putExtra("ACTION", "play")
                })
            findNavController().navigate(R.id.player, Bundle().apply {
                putInt(CURRENT_ITEM, 0)
            })
        }

        binding.goldStreamBanner.setOnClickListener {
            // Check if already playing this stream
            if (vm.currentStreamLive.value != "gold" || vm.isPlaying.value != true) {
                 vm.isBuffering.value = true
            }

            vm.currentStreamLive.value = "gold"
            vm.triggerMetadataUpdate()
            (activity as MainActivity).startService(
                Intent(context, MediaPlayerService::class.java).also {
                    it.putExtra("STREAM", "gold")
                    it.putExtra("ACTION", "play")
                })
            findNavController().navigate(R.id.player, Bundle().apply {
                putInt(CURRENT_ITEM, 1)
            })
        }

        binding.xtraStreamBanner.setOnClickListener {
            // Check if already playing this stream
            if (vm.currentStreamLive.value != "myata_hits" || vm.isPlaying.value != true) {
                 vm.isBuffering.value = true
            }

            vm.currentStreamLive.value = "myata_hits"
            vm.triggerMetadataUpdate()
            (activity as MainActivity).startService(
                Intent(context, MediaPlayerService::class.java).also {
                    it.putExtra("STREAM", "myata_hits")
                    it.putExtra("ACTION", "play")
                })
            findNavController().navigate(R.id.player, Bundle().apply {
                putInt(CURRENT_ITEM, 2)
            })
        }

        vm.isInSplitMode.observe(viewLifecycleOwner, Observer {
            if(it){
                binding.playlists.visibility = View.GONE
                (activity as MainActivity).binding.bottomNavView.visibility = View.GONE
                binding.playlistString.visibility = View.GONE
            }
        })

        // Donate button listener handled in MainActivity

        return binding.root
    }

    override fun onResume() {

        vm.currentFragmentLiveData.value = "main"

        if (!vm.isInSplitMode.value!!){
            binding.playlists.visibility = View.VISIBLE
            (activity as MainActivity).binding.bottomNavView.visibility = View.VISIBLE
            binding.playlistString.visibility = View.VISIBLE
        }
        
        // Force refresh of player status (including metadata) when returning to foreground
        // This fixes the issue where TV home screen idle time makes UI stale
        vm.refreshPlayerStatus()

        super.onResume()
    }


    private fun onItemClick(position: Int){
        val intent = Intent(Intent.ACTION_VIEW)
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.setData(Uri.parse(vm.playlistList.value!![position].uri))
        startActivity(intent)
    }

}