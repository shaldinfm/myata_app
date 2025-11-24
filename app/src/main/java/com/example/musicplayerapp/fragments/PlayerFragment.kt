package com.example.musicplayerapp.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.example.musicplayerapp.MainActivity
import com.example.musicplayerapp.R
import com.example.musicplayerapp.StreamsViewModel
import com.example.musicplayerapp.adapters.FragmentStreamAdapter
import com.example.musicplayerapp.databinding.FragmentPlayerBinding
import com.example.musicplayerapp.service.MediaPlayerService

const val CURRENT_ITEM = "0"

class PlayerFragment : Fragment() {

    lateinit var binding:FragmentPlayerBinding
    lateinit var vm: StreamsViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        vm = (activity as MainActivity).viewModel

        val adapter = FragmentStreamAdapter(this)
        binding = DataBindingUtil.inflate(
            inflater,
            R.layout.fragment_player, container, false
        )

        // Handle window insets for safe area
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.dotsIndicator) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        binding.viewPager.adapter = adapter

        // Determine initial position from arguments or currentStreamLive
        val initialPosition = arguments?.takeIf { it.containsKey(CURRENT_ITEM) }?.getInt(CURRENT_ITEM)
            ?: when(vm.currentStreamLive.value) {
                "gold" -> 1
                "myata_hits" -> 2
                else -> 0  // default to "myata"
            }
        
        // Set initial position immediately to avoid visual glitch
        binding.viewPager.setCurrentItem(initialPosition, false)

        binding.viewPager.registerOnPageChangeCallback(object: ViewPager2.OnPageChangeCallback(){
            override fun onPageSelected(position: Int) {
                when(position){
                    0->{
                        vm.currentStreamLive.value = "myata"
                        binding.dot1.setImageResource(R.drawable.dot_active)
                        binding.dot2.setImageResource(R.drawable.dot_inactive)
                        binding.dot3.setImageResource(R.drawable.dot_inactive)
                    }
                    1->{
                        vm.currentStreamLive.value = "gold"
                        binding.dot1.setImageResource(R.drawable.dot_inactive)
                        binding.dot2.setImageResource(R.drawable.dot_active)
                        binding.dot3.setImageResource(R.drawable.dot_inactive)
                    }
                    2->{
                        vm.currentStreamLive.value = "myata_hits"
                        binding.dot1.setImageResource(R.drawable.dot_inactive)
                        binding.dot2.setImageResource(R.drawable.dot_inactive)
                        binding.dot3.setImageResource(R.drawable.dot_active)
                    }
                }
                super.onPageSelected(position)
            }
        })

        return binding.root
    }

}