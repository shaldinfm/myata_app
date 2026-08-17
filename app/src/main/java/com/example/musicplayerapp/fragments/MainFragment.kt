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
import com.example.musicplayerapp.utils.ServiceUtils


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

            // The frozen clearance covers the chrome the design draws - navigation
            // bar, gap, mini player - but the navigation bar also takes the system
            // inset as padding (see MainActivity), so the content has to clear that
            // too or the last card ends up under the pill on a gesture-nav device.
            val scroll = binding.homeScroll
            scroll.setPadding(
                scroll.paddingLeft,
                scroll.paddingTop,
                scroll.paddingRight,
                resources.getDimensionPixelSize(R.dimen.content_bottom_clearance) + bars.bottom,
            )
            insets
        }
        binding.playlists.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.playlists.adapter = vm.playlistList.value?.let { PlaylistAdapter(it, { position -> onItemClick(position)}) }

        // Navigation listeners are now handled in MainActivity

        binding.myataStreamBanner.setOnClickListener {
            vm.switchStream("myata")
            findNavController().navigate(R.id.player, Bundle().apply {
                putInt(CURRENT_ITEM, 0)
            })
        }

        binding.goldStreamBanner.setOnClickListener {
            vm.switchStream("gold")
            findNavController().navigate(R.id.player, Bundle().apply {
                putInt(CURRENT_ITEM, 1)
            })
        }

        binding.xtraStreamBanner.setOnClickListener {
            vm.switchStream("myata_hits")
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

        return binding.root
    }

    override fun onResume() {

        vm.currentFragmentLiveData.value = "main"

        if (!vm.isInSplitMode.value!!){
            binding.playlists.visibility = View.VISIBLE
            // Ask the shell rather than poking the bar directly. onResume runs
            // when the transaction commits, which on the very first launch is
            // while the splash is still fading out - the shell holds the request
            // until the splash view is actually gone. Every later visit to HOME
            // is unaffected: there is no splash by then, so this is immediate.
            (activity as MainActivity).showBottomNav()
            binding.playlistString.visibility = View.VISIBLE
        }
        
        // MediaController automatically syncs state when re-connected

        super.onResume()
    }


    private fun onItemClick(position: Int){
        val intent = Intent(Intent.ACTION_VIEW)
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.setData(Uri.parse(vm.playlistList.value!![position].uri))
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            // No browser installed on this device (common on Android TV/projectors)
            android.widget.Toast.makeText(
                requireContext(),
                "Не удалось открыть ссылку",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

}