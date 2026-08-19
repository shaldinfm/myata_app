package com.example.musicplayerapp.fragments

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
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

    /** One in-flight cover lookup per row, cancelled when its row is recycled. */
    private val artworkJobs = mutableMapOf<FavoriteTrack, Job>()

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
            // systemBars() OR displayCutout(), not systemBars() alone. The status
            // bar normally covers a top cutout - measured 49.1dp of bar against a
            // 30.3dp hole on the API 36 emulator - but that is the platform being
            // helpful, not a guarantee the app is entitled to. The union is the
            // safe top area by construction, and it costs nothing: on every cutout
            // emulation measured (none/hole/tall/waterfall/double) it resolves to
            // exactly the same number the status bar alone gave.
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
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

        // The FINAL row has one control, and it opens the per-track sheet. The
        // four service actions and the removal that used to sit inline are rows
        // on that sheet now - see CollectionTrackSheet.
        adapter = FavoritesAdapter(
            artworkFor = ::requestArtwork,
            cancelArtwork = ::cancelArtwork,
            onActionClick = { track ->
                CollectionTrackSheet.show(childFragmentManager, track.artist, track.track)
            },
        )

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // `Удалить из коллекции` on the sheet does not delete anything itself: it
        // reports the request here, so the removal and its undo stay next to the
        // list that has to show both. The sheet identifies the row by artist and
        // track - the pair the favorites table is uniquely indexed on.
        childFragmentManager.setFragmentResultListener(
            CollectionTrackSheet.RESULT_REMOVE,
            viewLifecycleOwner,
        ) { _, bundle ->
            val artist = bundle.getString(CollectionTrackSheet.ARG_ARTIST)
            val track = bundle.getString(CollectionTrackSheet.ARG_TRACK)
            currentFavorites
                .firstOrNull { it.artist == artist && it.track == track }
                ?.let(::removeWithUndo)
        }
    }

    /**
     * Removes a track and offers `Отменить` for as long as the Snackbar is up.
     *
     * Undo needs no store of its own: the entity that came out of the list is
     * the undo record, and putting it back keeps its id and its addedAt, so the
     * row returns to the position it was removed from. See
     * FavoritesViewModel.restoreFavorite.
     *
     * The Snackbar is anchored on the chrome rather than on the window, so it
     * clears the Mini Player when there is one and the navigation bar when there
     * is not - the same 154 of clearance the list itself reserves.
     */
    private fun removeWithUndo(track: FavoriteTrack) {
        viewModel.removeFavorite(track)
        Snackbar.make(binding.root, R.string.collection_removed, Snackbar.LENGTH_LONG)
            .setAnchorView(snackbarAnchor())
            .setAction(R.string.collection_removed_undo) { viewModel.restoreFavorite(track) }
            .also(::styleSnackbar)
            .show()
    }

    /**
     * The design system's own snackbar, spec/primitives.mjs:286 - r12 on
     * `surface_container` with a `menu_outline` stroke, the message on
     * `text_primary` and the action on `primary`, inset on the screen's 16
     * margins. No COLLECTION frame draws a snackbar, so this takes the
     * established pattern rather than shipping Material's default grey slab,
     * which belongs to neither theme.
     *
     * `isAllCaps` is turned off because the Material button style would render
     * the owner's `Отменить` as `ОТМЕНИТЬ`, and nothing else in the app shouts.
     */
    private fun styleSnackbar(bar: Snackbar) {
        val ctx = requireContext()
        bar.view.background = ContextCompat.getDrawable(ctx, R.drawable.bg_snackbar)
        bar.view.setBackgroundTintList(null)
        (bar.view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            val m = resources.getDimensionPixelSize(R.dimen.snackbar_margin)
            lp.setMargins(m, m, m, m)
            bar.view.layoutParams = lp
        }
        bar.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
        bar.setActionTextColor(ContextCompat.getColor(ctx, R.color.primary))
        bar.view.findViewById<Button>(com.google.android.material.R.id.snackbar_action)
            ?.isAllCaps = false
    }

    private fun snackbarAnchor(): View? {
        val main = activity as? MainActivity ?: return null
        val mini = main.binding.miniPlayer.root
        return if (mini.visibility == View.VISIBLE) mini else main.binding.bottomNavView
    }

    /**
     * A cover for one row. [FavoriteTrack] has no artwork of its own, so this
     * goes through the ViewModel to ArtworkRepository, which derives one from the
     * row's artist and track.
     */
    private fun requestArtwork(track: FavoriteTrack, onResult: (String?) -> Unit) {
        artworkJobs.remove(track)?.cancel()
        artworkJobs[track] = viewLifecycleOwner.lifecycleScope.launch {
            val url = viewModel.artworkUrl(track)
            artworkJobs.remove(track)
            onResult(url)
        }
    }

    private fun cancelArtwork(track: FavoriteTrack) {
        artworkJobs.remove(track)?.cancel()
    }

    override fun onDestroyView() {
        // viewLifecycleOwner's scope cancels the jobs themselves; this drops the
        // entries, which outlive the view because the map does not belong to it.
        artworkJobs.clear()
        super.onDestroyView()
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
