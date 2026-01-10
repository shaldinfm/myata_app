package com.example.musicplayerapp.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import android.content.Intent
import com.example.musicplayerapp.databinding.FragmentTvStreamSelectionBinding
import com.example.musicplayerapp.service.MediaPlayerService

class TvStreamSelectionFragment : Fragment() {

    private var _binding: FragmentTvStreamSelectionBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTvStreamSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupFocus(binding.cardMyata)
        setupFocus(binding.cardGold)
        setupFocus(binding.cardXtra)

        binding.cardMyata.setOnClickListener {
            navigateToPlayer("myata")
        }

        binding.cardGold.setOnClickListener {
            navigateToPlayer("gold")
        }

        binding.cardXtra.setOnClickListener {
            navigateToPlayer("myata_hits")
        }
        
        // Request focus on the first card by default
        binding.cardMyata.requestFocus()
    }

    private fun navigateToPlayer(stream: String) {
        // Update ViewModel
        val vm: com.example.musicplayerapp.StreamsViewModel by activityViewModels()
        
        // Immediate feedback: Show spinner when entering player ONLY if switching streams or not playing
        if (vm.currentStreamLive.value != stream || vm.isPlaying.value != true) {
            vm.isBuffering.value = true
        }
        
        vm.currentStreamLive.value = stream
        vm.triggerMetadataUpdate()
        
        // Auto-play: Send play intent immediately
        val intent = Intent(context, MediaPlayerService::class.java).apply {
            putExtra("STREAM", stream)
            putExtra("ACTION", "play")
        }
        activity?.startService(intent)

        // Navigate
        parentFragmentManager.beginTransaction()
            .replace(com.example.musicplayerapp.R.id.tv_fragment_container, TvPlayerFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun setupFocus(view: View) {
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(200).start()
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
