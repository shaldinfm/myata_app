package com.example.musicplayerapp.fragments

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.musicplayerapp.MainActivity
import com.example.musicplayerapp.R
import com.example.musicplayerapp.adapters.FavoritesAdapter
import com.example.musicplayerapp.data.FavoriteTrack
import com.example.musicplayerapp.databinding.FragmentFavoritesBinding
import com.example.musicplayerapp.viewmodel.FavoritesViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fragment for displaying and managing favorite tracks collection.
 */
class FavoritesFragment : Fragment() {

    private lateinit var binding: FragmentFavoritesBinding
    private lateinit var viewModel: FavoritesViewModel
    private lateinit var adapter: FavoritesAdapter
    private var currentFavorites: List<FavoriteTrack> = emptyList()

    // File create launchers
    private val createTxtFile = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                lifecycleScope.launch {
                    try {
                        val content = viewModel.exportToTxt(currentFavorites)
                        requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
                            // Write UTF-8 BOM
                            stream.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                            stream.write(content.toByteArray(Charsets.UTF_8))
                        }
                        Toast.makeText(context, "Файл сохранён", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val createCsvFile = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                lifecycleScope.launch {
                    try {
                        val content = viewModel.exportToCsv(currentFavorites)
                        requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
                            // Write UTF-8 BOM
                            stream.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                            stream.write(content.toByteArray(Charsets.UTF_8))
                        }
                        Toast.makeText(context, "Файл сохранён", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DataBindingUtil.inflate(
            inflater,
            R.layout.fragment_favorites,
            container,
            false
        )

        // Update current fragment for navigation
        (activity as MainActivity).viewModel.currentFragmentLiveData.value = "favorites"

        // Handle window insets for safe area
        //
        // Top goes on the whole column rather than on the title, so the frozen
        // 64dp app bar keeps its height under a notch instead of growing by the
        // inset and pushing everything below it down.
        //
        // Bottom is the frozen 154 clearance plus the same system inset
        // MainActivity adds to the navigation bar's own padding - without it the
        // last row ends up under the Mini Player on a gesture-nav device. Both
        // scrolling containers get it, because either one of them can be the one
        // on screen.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.collectionRoot) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)

            val clearance = resources.getDimensionPixelSize(R.dimen.content_bottom_clearance) +
                bars.bottom
            for (scroller in listOf<View>(binding.rvFavorites, binding.emptyState)) {
                scroller.setPadding(
                    scroller.paddingLeft,
                    scroller.paddingTop,
                    scroller.paddingRight,
                    clearance,
                )
            }
            insets
        }

        viewModel = ViewModelProvider(this)[FavoritesViewModel::class.java]

        adapter = FavoritesAdapter { track ->
            viewModel.removeFavorite(track)
        }

        binding.rvFavorites.layoutManager = LinearLayoutManager(context)
        binding.rvFavorites.adapter = adapter

        // Observe favorites
        lifecycleScope.launch {
            viewModel.favorites.collectLatest { favorites ->
                currentFavorites = favorites

                if (favorites.isEmpty()) {
                    binding.rvFavorites.visibility = View.GONE
                    binding.emptyState.visibility = View.VISIBLE
                    // The frozen empty frame hides the overflow: with nothing in
                    // the collection there is nothing to export. This is the same
                    // condition the two export pills used to express by going
                    // disabled.
                    binding.collectionOverflow.visibility = View.GONE
                } else {
                    binding.rvFavorites.visibility = View.VISIBLE
                    binding.emptyState.visibility = View.GONE
                    binding.collectionOverflow.visibility = View.VISIBLE
                    adapter.submitList(favorites)
                }
            }
        }

        // Export, from the frozen header overflow.
        //
        // The two actions are the ones the screen already had as always-visible
        // pills inside the container card the frozen design removes. Everything
        // behind them is untouched - the same SAF ACTION_CREATE_DOCUMENT intents,
        // the same MIME types and filenames, the same UTF-8 BOM, the same CSV
        // formatting in FavoritesViewModel and the same two toasts. Only where
        // the user reaches them has moved.
        //
        // A platform PopupMenu is deliberate and temporary: F2 replaces it with
        // the frozen `Menu / Коллекция` surface. Relocating the actions first
        // keeps export reachable across the interval instead of removing it and
        // waiting for F2 to bring it back.
        binding.collectionOverflow.setOnClickListener { anchor ->
            PopupMenu(anchor.context, anchor).apply {
                menuInflater.inflate(R.menu.collection_overflow, menu)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.collection_action_export_txt -> {
                            exportTxt()
                            true
                        }
                        R.id.collection_action_export_csv -> {
                            exportCsv()
                            true
                        }
                        else -> false
                    }
                }
            }.show()
        }

        return binding.root
    }

    private fun exportTxt() {
        if (currentFavorites.isNotEmpty()) {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "myata_favorites.txt")
            }
            createTxtFile.launch(intent)
        }
    }

    private fun exportCsv() {
        if (currentFavorites.isNotEmpty()) {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/csv"
                putExtra(Intent.EXTRA_TITLE, "myata_favorites.csv")
            }
            createCsvFile.launch(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as MainActivity).viewModel.currentFragmentLiveData.value = "favorites"
        (activity as MainActivity).binding.bottomNavView.visibility = View.VISIBLE
    }
}
