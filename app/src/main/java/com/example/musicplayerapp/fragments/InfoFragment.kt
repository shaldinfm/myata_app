package com.example.musicplayerapp.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.MainActivity
import com.example.musicplayerapp.R
import com.example.musicplayerapp.StreamsViewModel
import com.example.musicplayerapp.databinding.FragmentInfoBinding

class InfoFragment : Fragment() {

    private lateinit var vm: StreamsViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {



        val binding: FragmentInfoBinding = DataBindingUtil.inflate(
            inflater, R.layout.fragment_info, container, false
        )

        // Handle window insets for safe area
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.title) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            // Add status bar height to existing top padding/margin
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        vm = (activity as MainActivity).viewModel

        vm.currentFragmentLiveData.value = "info"

        binding.yandex.setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://band.link/myata_yandex"))
            startActivity(browserIntent)
        }
        binding.instagram.setOnClickListener{
            val intent = Intent(Intent.ACTION_VIEW)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.setData(Uri.parse("https://www.instagram.com/radiomyata/"))
            startActivity(intent)
        }
        binding.boosty.setOnClickListener{
            val intent = Intent(Intent.ACTION_VIEW)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.setData(Uri.parse("https://boosty.to/myata"))
            startActivity(intent)
        }
        binding.vk.setOnClickListener{
            val intent = Intent(Intent.ACTION_VIEW)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.setData(Uri.parse("https://www.tiktok.com/@radio_myata"))
            startActivity(intent)
        }
        binding.youtube.setOnClickListener{
            val intent = Intent(Intent.ACTION_VIEW)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.setData(Uri.parse("https://www.youtube.com/channel/UC30ExLCP-enuCrHH2qRRlCw"))
            startActivity(intent)
        }
        binding.twitter.setOnClickListener{
            val intent = Intent(Intent.ACTION_VIEW)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.setData(Uri.parse("https://twitter.com/radiomyata"))
            startActivity(intent)
        }
        binding.telegram.setOnClickListener{
            val intent = Intent(Intent.ACTION_VIEW)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.setData(Uri.parse("https://t.me/radiomyata"))
            startActivity(intent)
        }
        binding.spotify.setOnClickListener{
            val intent = Intent(Intent.ACTION_VIEW)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.setData(Uri.parse("https://open.spotify.com/user/31b7rfatuqf7lc76thiudm5bxxuy"))
            startActivity(intent)
        }


        vm.isInSplitMode.observe(viewLifecycleOwner, Observer {
            if(it){
//                binding.bottomStreams.visibility = View.GONE
                (activity as MainActivity).binding.bottomNavView.visibility = View.GONE
                binding.title.visibility = View.GONE
            }
            else{
//                binding.bottomStreams.visibility = View.VISIBLE
                (activity as MainActivity).binding.bottomNavView.visibility = View.VISIBLE
                binding.title.visibility = View.VISIBLE
            }
        })

        (activity as MainActivity).binding.playerBtn.setOnClickListener {
            findNavController().navigate(R.id.player, Bundle().apply {
                when(vm.currentStreamLive.value){
                    "myata"->putInt(CURRENT_ITEM, 0)
                    "gold"->putInt(CURRENT_ITEM, 1)
                    "myata_hits"->putInt(CURRENT_ITEM, 2)
                }
            })
        }

        (activity as MainActivity).binding.donateBtn.setOnClickListener {
            findNavController().navigate(R.id.donate)
        }
        (activity as MainActivity).binding.homeBtn.setOnClickListener {
            findNavController().navigate(R.id.home)
        }

        return binding.root
    }

    override fun onResume() {
        vm.currentFragmentLiveData.value = "info"
        super.onResume()
    }
}