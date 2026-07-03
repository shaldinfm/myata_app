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
    
    private val handler = Handler(Looper.getMainLooper())
    private val navigateRunnable = Runnable {
        // Check fragment is still attached and Activity is not saving state
        if (isAdded && !isStateSaved) {
            parentFragmentManager.beginTransaction()
                .replace(R.id.tv_fragment_container, TvStreamSelectionFragment())
                .commit()
        } else if (isAdded) {
            // Activity is saving state, use commitAllowingStateLoss as fallback
            parentFragmentManager.beginTransaction()
                .replace(R.id.tv_fragment_container, TvStreamSelectionFragment())
                .commitAllowingStateLoss()
        }
    }

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
        handler.postDelayed(navigateRunnable, 2000)
    }

    override fun onDestroyView() {
        // Cancel pending navigation to prevent leaks and crashes
        handler.removeCallbacks(navigateRunnable)
        super.onDestroyView()
        _binding = null
    }
}
