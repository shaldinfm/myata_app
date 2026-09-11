package com.example.musicplayerapp.fragments

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayerapp.MainActivity
import com.example.musicplayerapp.R
import com.example.musicplayerapp.StreamsViewModel
import com.example.musicplayerapp.adapters.BroadcastHistoryFooterAdapter
import com.example.musicplayerapp.adapters.PlayerHistoryAdapter
import com.example.musicplayerapp.data.HistoryTrack
import com.example.musicplayerapp.databinding.FragmentBroadcastHistoryBinding
import com.example.musicplayerapp.ui.FindTrackQuery
import com.example.musicplayerapp.ui.HistoryScreenState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * История эфира, full screen (G4b) - `Menu / Плеер` > `История эфира`.
 *
 * history-loading / -content / -empty / -error in one pushed destination. Like
 * report and settings it has a back band and no bottom bar, and NavScreen
 * classifies it as pushed, so there is no Mini Player over it.
 *
 * ## There is still one history
 *
 * This is a third view of `StreamsViewModel.historyTracks` - after the PLAYER's
 * inline section and the unreachable History bottom sheet - not a second
 * backend. The request, the 30-entry ceiling, the "current track is not history"
 * projection and the one-request-at-a-time rule are all the ViewModel's, exactly
 * as the inline section gets them. What G4b added underneath is only the
 * distinction the frozen frames need: a failed request is now `historyFailed`
 * rather than an empty list, so `history-error` and `history-empty` can differ.
 *
 * ## The row's one action
 *
 * The circular action on a row opens [FindTrackSheet] for that row's track - the
 * same sheet `Найти трек` opens for the current one, through the same rows.
 */
class BroadcastHistoryFragment : Fragment() {

    private var _binding: FragmentBroadcastHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var vm: StreamsViewModel
    private lateinit var rows: PlayerHistoryAdapter
    private val footer = BroadcastHistoryFooterAdapter()

    /** Outstanding cover lookups, by row, so a recycled row can withdraw its own. */
    private val artworkJobs = mutableMapOf<HistoryTrack, Job>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentBroadcastHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vm = (requireActivity() as MainActivity).viewModel

        // The band sits below the status bar, as on every pushed destination; the
        // list and the two message frames clear the navigation bar themselves,
        // because nothing else on this screen reserves it.
        val listBottom = binding.historyList.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.historyRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            binding.historyList.setPadding(
                binding.historyList.paddingLeft, binding.historyList.paddingTop,
                binding.historyList.paddingRight, listBottom + bars.bottom,
            )
            listOf<View>(binding.historyEmpty, binding.historyError).forEach { panel ->
                panel.setPadding(panel.paddingLeft, panel.paddingTop, panel.paddingRight, bars.bottom)
            }
            insets
        }

        // Back returns to the player: popBackStack, the rule every pushed screen
        // here follows, rather than a named destination.
        binding.historyBack.setOnClickListener { findNavController().popBackStack() }

        rows = PlayerHistoryAdapter(
            artworkFor = ::requestArtwork,
            cancelArtwork = ::cancelArtwork,
            rowLayout = R.layout.item_broadcast_history_track,
            onFindTrack = ::openFindTrack,
        )
        binding.historyList.layoutManager = LinearLayoutManager(requireContext())
        binding.historyList.adapter = ConcatAdapter(rows, footer)
        binding.historyList.addItemDecoration(
            BroadcastHistoryRowGap(resources.getDimensionPixelSize(R.dimen.history_screen_row_gap))
        )

        // `Обновить` and `Повторить` are the same request. What differs is which
        // frame offers it - see HistoryScreenState.
        binding.historyEmptyAction.setOnClickListener { vm.loadHistory() }
        binding.historyErrorAction.setOnClickListener { vm.loadHistory() }

        vm.historyTracks.observe(viewLifecycleOwner) { render() }
        vm.historyLoading.observe(viewLifecycleOwner) { render() }
        vm.historyFailed.observe(viewLifecycleOwner) { render() }

        // Fresh on arrival, not on every recreation: a rotation keeps the list the
        // ViewModel already holds rather than asking the station again. Any rows
        // the PLAYER already loaded stay up while this runs.
        if (savedInstanceState == null) vm.loadHistory()
    }

    override fun onDestroyView() {
        artworkJobs.values.forEach { it.cancel() }
        artworkJobs.clear()
        binding.historyList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun render() {
        val b = _binding ?: return
        val tracks = vm.historyTracks.value.orEmpty()
        val state = HistoryScreenState.of(
            total = tracks.size,
            isLoading = vm.historyLoading.value == true,
            failed = vm.historyFailed.value == true,
        )

        b.historyList.isVisible = state == HistoryScreenState.CONTENT
        b.historySkeleton.isVisible = state == HistoryScreenState.LOADING
        b.historyEmpty.isVisible = state == HistoryScreenState.EMPTY
        b.historyError.isVisible = state == HistoryScreenState.ERROR

        rows.submitList(tracks)
        footer.shownCount = tracks.size
    }

    private fun openFindTrack(track: HistoryTrack) {
        val query = FindTrackQuery.of(artist = track.artist, title = track.title) ?: return
        FindTrackSheet.show(childFragmentManager, query)
    }

    /** A cover for one row - the inline section's route, see MyataStreamFragment. */
    private fun requestArtwork(track: HistoryTrack, onResult: (String?) -> Unit) {
        artworkJobs.remove(track)?.cancel()
        artworkJobs[track] = viewLifecycleOwner.lifecycleScope.launch {
            val url = vm.historyArtworkUrl(track)
            artworkJobs.remove(track)
            onResult(url)
        }
    }

    private fun cancelArtwork(track: HistoryTrack) {
        artworkJobs.remove(track)?.cancel()
    }
}

/**
 * `Broadcast History List`'s 8 between rows. Rows only: the footer carries its own
 * 24 above it, and the first row sits on the list's 16 of top padding.
 *
 * Internal rather than private so `BroadcastHistoryLayoutTest` measures the list
 * with the same decoration the screen draws it with.
 */
internal class BroadcastHistoryRowGap(private val gap: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val holder = parent.getChildViewHolder(view)
        if (holder.bindingAdapter is PlayerHistoryAdapter && holder.bindingAdapterPosition > 0) {
            outRect.top = gap
        }
    }
}
