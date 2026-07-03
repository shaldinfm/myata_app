package com.example.musicplayerapp.fragments

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.title) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
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
                    binding.btnExportTxt.isEnabled = false
                    binding.btnExportCsv.isEnabled = false
                } else {
                    binding.rvFavorites.visibility = View.VISIBLE
                    binding.emptyState.visibility = View.GONE
                    binding.btnExportTxt.isEnabled = true
                    binding.btnExportCsv.isEnabled = true
                    adapter.submitList(favorites)
                }
            }
        }

        // Export TXT
        binding.btnExportTxt.setOnClickListener {
            if (currentFavorites.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "myata_favorites.txt")
                }
                createTxtFile.launch(intent)
            }
        }

        // Export CSV
        binding.btnExportCsv.setOnClickListener {
            if (currentFavorites.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/csv"
                    putExtra(Intent.EXTRA_TITLE, "myata_favorites.csv")
                }
                createCsvFile.launch(intent)
            }
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        (activity as MainActivity).viewModel.currentFragmentLiveData.value = "favorites"
        (activity as MainActivity).binding.bottomNavView.visibility = View.VISIBLE
    }
}
