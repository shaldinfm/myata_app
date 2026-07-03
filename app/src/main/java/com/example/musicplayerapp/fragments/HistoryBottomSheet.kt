package com.example.musicplayerapp.fragments

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.cardview.widget.CardView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.musicplayerapp.MainActivity
import com.example.musicplayerapp.R
import com.example.musicplayerapp.adapters.HistoryAdapter
import kotlinx.coroutines.launch

/**
 * Centered popup dialog for displaying track history.
 */
class HistoryBottomSheet : DialogFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loadingSpinner: View
    private lateinit var emptyText: View
    private lateinit var adapter: HistoryAdapter
    private lateinit var historyCard: CardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Use MaterialComponents theme to support MaterialCardView inflation
        setStyle(STYLE_NO_TITLE, R.style.Theme_MusicPlayerApp_BottomSheet)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_history_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup background dim/blur
        setupDialogWindow()

        recyclerView = view.findViewById(R.id.rv_history)
        swipeRefresh = view.findViewById(R.id.swipe_refresh)
        loadingSpinner = view.findViewById(R.id.loading_spinner)
        emptyText = view.findViewById(R.id.tv_empty)
        historyCard = view.findViewById(R.id.history_card)
        val btnClose = view.findViewById<View>(R.id.btn_close)

        adapter = HistoryAdapter()
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        val vm = (activity as MainActivity).viewModel

        // Apply stream-specific color to the card
        applyStreamColor(vm.currentStreamLive.value)

        // Set colors for SwipeRefresh
        swipeRefresh.setColorSchemeColors(Color.WHITE)
        swipeRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#40FFFFFF"))

        // Observe history data
        vm.historyTracks.observe(viewLifecycleOwner) { tracks ->
            val isLoading = vm.historyLoading.value ?: false
            swipeRefresh.isRefreshing = false
            
            if (tracks.isNullOrEmpty()) {
                // Show empty text only if NOT loading
                emptyText.visibility = if (isLoading) View.GONE else View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyText.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                adapter.submitList(tracks)
            }
        }

        // Observe loading state
        vm.historyLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                if (adapter.currentList.isEmpty()) {
                    loadingSpinner.visibility = View.VISIBLE
                    emptyText.visibility = View.GONE
                }
            } else {
                loadingSpinner.visibility = View.GONE
                // After loading completes, if still empty, show the message
                if (adapter.currentList.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                }
            }
        }

        // Pull to refresh
        swipeRefresh.setOnRefreshListener {
            vm.loadHistory()
        }

        // Close button
        btnClose.setOnClickListener {
            dismiss()
        }
        
        // Also close when clicking outside the card
        view.findViewById<View>(R.id.history_root).setOnClickListener {
            dismiss()
        }
        historyCard.setOnClickListener { /* prevent click propagation */ }

        // Load initial data
        vm.loadHistory()
    }

    private fun setupDialogWindow() {
        dialog?.window?.apply {
            // Prevent the activity from resizing or jumping
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            
            // Set window background to transparent
            setBackgroundDrawableResource(android.R.color.transparent)

            // Add dimming
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f) 
            
            // High-quality blur on supported versions (API 31+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                
                // Animate blur radius for smooth entrance
                val animator = android.animation.ValueAnimator.ofInt(0, 120)
                animator.duration = 400
                animator.interpolator = android.view.animation.DecelerateInterpolator()
                animator.addUpdateListener { animation ->
                    val radius = animation.animatedValue as Int
                    attributes.blurBehindRadius = radius
                    setAttributes(attributes) // Notify window of changes
                }
                animator.start()
            }
            
            // Set animation
            setWindowAnimations(android.R.style.Animation_Dialog)

            // Layout fill
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun applyStreamColor(stream: String?) {
        val color = when(stream) {
            "gold" -> Color.parseColor("#08AFB5")
            "myata_hits" -> Color.parseColor("#1C4771")
            else -> Color.parseColor("#9263AB") // myata
        }
        historyCard.setCardBackgroundColor(color)
    }

    companion object {
        const val TAG = "HistoryBottomSheet"
    }
}
