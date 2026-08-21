package com.example.musicplayerapp.fragments


import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.MainActivity
import com.example.musicplayerapp.R
import com.example.musicplayerapp.StreamsViewModel
import com.example.musicplayerapp.data.PlaylistsState
import com.example.musicplayerapp.databinding.FragmentSplashBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


/**
 * The legacy full-screen splash: it holds the app back until the playlist load
 * finishes, and offers a retry when it does not.
 *
 * Its connectivity retry has moved to [StreamsViewModel]. That behaviour belongs
 * to the loader rather than to a screen - the reader should get their playlists
 * back when the network returns whether or not a splash happens to be on top -
 * and it has to keep working once this screen is gone. Nothing else here changed:
 * this fragment still gates entry exactly as it did, and still shows the same
 * loading, error and offline copy, so the launch sequence is unaffected.
 */
class
SplashFragment : Fragment() {

    lateinit var vm: StreamsViewModel
    private lateinit var binding: FragmentSplashBinding

    private var hasNavigated = false
    private var waitDeadlineJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Hide bottom navigation during splash screen safely
        activity?.findViewById<View>(R.id.bottomNavView)?.visibility = View.GONE

        binding = DataBindingUtil.inflate(
            inflater, R.layout.fragment_splash, container, false
        )

        val a = context?.theme?.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
        val background = a?.getDrawable(0)
        a?.recycle()
        binding.splashBg.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        binding.splashBg.setImageDrawable(background)

        vm = (activity as MainActivity).viewModel

        binding.splashRetryButton.setOnClickListener {
            showLoadingState()
            vm.refreshPlaylists()
        }

        vm.playlistList.observe(viewLifecycleOwner, Observer {
            if (it.isNotEmpty()) enterApp()
        })

        vm.playlistsState.observe(viewLifecycleOwner, Observer { state ->
            when (state) {
                PlaylistsState.READY -> enterApp()
                PlaylistsState.ERROR -> showErrorState()
                PlaylistsState.LOADING -> showLoadingState()
                else -> Unit
            }
        })

        return binding.root
    }

    /**
     * Backstop for the case where the load neither succeeds nor reports failure in
     * time — a blocking socket read can outlive the view model's own budget. After
     * this the user always has something actionable instead of a frozen splash.
     */
    private fun startWaitDeadline() {
        waitDeadlineJob?.cancel()
        waitDeadlineJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(MAX_SPLASH_WAIT_MS)
            if (!hasNavigated && vm.playlistsState.value != PlaylistsState.READY) {
                showErrorState()
            }
        }
    }

    private fun enterApp() {
        if (hasNavigated) return
        hasNavigated = true
        waitDeadlineJob?.cancel()
        findNavController().navigate(R.id.action_splashFragment_to_mainFragment)
    }

    private fun showLoadingState() {
        binding.splashErrorContainer.visibility = View.GONE
        startWaitDeadline()
    }

    private fun showErrorState() {
        if (hasNavigated) return
        binding.splashErrorTitle.setText(
            if (vm.isOnline()) R.string.splash_error_title
            else R.string.splash_offline_title
        )
        binding.splashErrorContainer.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        waitDeadlineJob?.cancel()
        waitDeadlineJob = null
        super.onDestroyView()
    }

    private companion object {
        const val MAX_SPLASH_WAIT_MS = 15_000L
    }
}
