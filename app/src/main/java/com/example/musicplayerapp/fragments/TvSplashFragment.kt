package com.example.musicplayerapp.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.musicplayerapp.R
import com.example.musicplayerapp.databinding.FragmentTvSplashBinding

class TvSplashFragment : Fragment() {

    private var _binding: FragmentTvSplashBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTvSplashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Navigate to Stream Selection after 2 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            if (isAdded) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.tv_fragment_container, TvStreamSelectionFragment())
                    .commit()
            }
        }, 2000)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
