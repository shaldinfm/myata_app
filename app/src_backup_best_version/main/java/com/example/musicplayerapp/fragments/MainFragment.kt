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
        (activity as MainActivity).binding.bottomNavView.visibility = View.VISIBLE
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
        binding.playlists.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.playlists.adapter = vm.playlistList.value?.let { PlaylistAdapter(it, { position -> onItemClick(position)}) }

        (activity as MainActivity).binding.infoBtn.setOnClickListener {
            findNavController().navigate(R.id.info)
        }

        (activity as MainActivity).binding.playerBtn.setOnClickListener {
            findNavController().navigate(R.id.player, Bundle().apply {
                when(vm.currentStreamLive.value){
                    "myata"->putInt(CURRENT_ITEM, 0)
                    "gold"->putInt(CURRENT_ITEM, 1)
                    "myata_hits"->putInt(CURRENT_ITEM, 2)
                }
            })
        }

        binding.myataStreamBanner.setOnClickListener {
            vm.currentStreamLive.value = "myata"
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
            vm.currentStreamLive.value = "gold"
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
            vm.currentStreamLive.value = "myata_hits"
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

        (activity as MainActivity).binding.donateBtn.setOnClickListener {
            findNavController().navigate(R.id.donate)
        }

        return binding.root
    }

    override fun onResume() {

        vm.currentFragmentLiveData.value = "main"

        if (!vm.isInSplitMode.value!!){
            binding.playlists.visibility = View.VISIBLE
            (activity as MainActivity).binding.bottomNavView.visibility = View.VISIBLE
            binding.playlistString.visibility = View.VISIBLE
        }

        super.onResume()
    }


    private fun onItemClick(position: Int){
        val intent = Intent(Intent.ACTION_VIEW)
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.setData(Uri.parse(vm.playlistList.value!![position].uri))
        startActivity(intent)
    }

}