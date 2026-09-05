package com.example.musicplayerapp.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.musicplayerapp.MainActivity
import com.example.musicplayerapp.R
import com.example.musicplayerapp.ui.profile.ProfileRoute
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.ui.HomeGreeting
import com.example.musicplayerapp.StreamsViewModel
import com.example.musicplayerapp.adapters.PlaylistAdapter
import com.example.musicplayerapp.data.MyataPlaylist
import com.example.musicplayerapp.databinding.FragmentMainBinding
import com.example.musicplayerapp.ui.HomePlaylistSection
import com.example.musicplayerapp.ui.HomePlaylistsState
import com.example.musicplayerapp.service.MediaPlayerService
import com.example.musicplayerapp.utils.ServiceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MainFragment : Fragment() {

    lateinit var binding: FragmentMainBinding
    lateinit var vm: StreamsViewModel

    /**
     * Created once, empty, and attached before any data exists.
     *
     * The row used to get its adapter from `playlistList.value` at this point,
     * which silently produced a **null adapter** whenever the value had not
     * arrived - and nothing ever set one afterwards, because there was no
     * observer. It only worked because the splash held HOME back until the load
     * had finished. Attaching an empty adapter and filling it later is what makes
     * HOME safe to exist before that is true.
     */
    private lateinit var playlistAdapter: PlaylistAdapter


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        vm = (activity as MainActivity).viewModel

        vm.currentFragmentLiveData.value = "main"

        if(vm.ifNeedToNavigateStraightToPlayer){
            findNavController().navigate(R.id.player, Bundle().apply {
                when(vm.currentStreamLive.value){
                    "myata"->putInt(CURRENT_ITEM, 0)
                    "gold"->putInt(CURRENT_ITEM, 1)
                    "myata_hits"->putInt(CURRENT_ITEM, 2)
                }
            })
        }

        binding = DataBindingUtil.inflate(
            inflater,
            R.layout.fragment_main, container, false
        )

        // Handle window insets for safe area (notch/status bar)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.mainContentContainer) { v, insets ->
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

            // The frozen clearance covers the chrome the design draws - navigation
            // bar, gap, mini player - but the navigation bar also takes the system
            // inset as padding (see MainActivity), so the content has to clear that
            // too or the last card ends up under the pill on a gesture-nav device.
            val scroll = binding.homeScroll
            scroll.setPadding(
                scroll.paddingLeft,
                scroll.paddingTop,
                scroll.paddingRight,
                resources.getDimensionPixelSize(R.dimen.content_bottom_clearance) + bars.bottom,
            )
            insets
        }
        binding.playlists.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        playlistAdapter = PlaylistAdapter(::onPlaylistClick)
        binding.playlists.adapter = playlistAdapter

        binding.playlistRetry.setOnClickListener {
            // The same entry point the splash's Retry uses, and the same one the
            // connectivity callback calls. It is a no-op while a load is already
            // running, so an impatient double tap costs nothing.
            vm.refreshPlaylists()
        }

        // Both halves of the section's state, observed for the lifetime of the
        // view. Either can arrive first, and either can arrive long after HOME is
        // already on screen, so both just re-render.
        vm.playlistList.observe(viewLifecycleOwner, Observer { playlists ->
            playlistAdapter.submit(playlists ?: emptyList())
            renderPlaylistSection()
        })

        vm.playlistsState.observe(viewLifecycleOwner, Observer { renderPlaylistSection() })

        // Navigation listeners are now handled in MainActivity

        binding.myataStreamBanner.setOnClickListener {
            vm.switchStream("myata")
            findNavController().navigate(R.id.player, Bundle().apply {
                putInt(CURRENT_ITEM, 0)
            })
        }

        binding.goldStreamBanner.setOnClickListener {
            vm.switchStream("gold")
            findNavController().navigate(R.id.player, Bundle().apply {
                putInt(CURRENT_ITEM, 1)
            })
        }

        binding.xtraStreamBanner.setOnClickListener {
            vm.switchStream("myata_hits")
            findNavController().navigate(R.id.player, Bundle().apply {
                putInt(CURRENT_ITEM, 2)
            })
        }

        vm.isInSplitMode.observe(viewLifecycleOwner, Observer {
            if(it){
                (activity as MainActivity).binding.bottomNavView.visibility = View.GONE
            }
            // Split mode hides the whole section; renderPlaylistSection owns that
            // now, so the inline status cannot survive a rotation into split view.
            renderPlaylistSection()
        })

        // The 40x40 profile control. It opens profile-guest and does nothing else -
        // in particular it does not touch the identity boundary, so looking at the
        // profile never mints an anonymous uid.
        binding.profileEntry.root.setOnClickListener {
            ProfileRoute.open(this)
        }


        return binding.root
    }

    override fun onResume() {

        vm.currentFragmentLiveData.value = "main"

        if (!vm.isInSplitMode.value!!){
            // Ask the shell rather than poking the bar directly. onResume runs
            // when the transaction commits, which on the very first launch is
            // while the splash is still fading out - the shell holds the request
            // until the splash view is actually gone. Every later visit to HOME
            // is unaffected: there is no splash by then, so this is immediate.
            (activity as MainActivity).showBottomNav()
        }

        // Not `visibility = VISIBLE`. This used to force the row and its heading
        // on regardless of whether there was anything to put in them, which would
        // have overwritten the section's state on every return to HOME.
        renderPlaylistSection()

        // Every return to HOME, not once at inflation. Sign-in, registration, a
        // logout and a switch to another account all end with HOME resumed and none
        // of them notifies it, so re-asking is what keeps the header honest - and in
        // particular is what stops the previous listener's name surviving a logout.
        renderGreeting()

        // MediaController automatically syncs state when re-connected

        super.onResume()
    }

    /**
     * `Привет!` or `Привет, <name>!`, from the account the profile already trusts.
     *
     * [HomeGreeting] owns which of the two this is; everything here is the resource
     * lookup it deliberately does not do, so the copy stays in `strings.xml` and a
     * future locale gets its own.
     *
     * Off the main thread for the same reason `ProfileRoute` is: everything under it
     * reads `SharedPreferences`, and the auth boundary is suspending. Neither can
     * mint - `currentAccount` reads the session the Auth plugin is already holding
     * and makes no request - so drawing HOME still never creates an identity, which
     * `ProfileEntryTest` asserts for the whole screen.
     *
     * The header is **not** cleared first. The plain greeting is a safe value, not a
     * blank one, so the alternative would be flashing `Привет!` at a signed-in
     * listener on every single return to HOME to protect against a stale name that
     * survives at most the millisecond these two local reads take.
     */
    private fun renderGreeting() {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            val name = withContext(Dispatchers.IO) {
                HomeGreeting.name(IdentityStore.state(context)) {
                    EmailAuthBackend.api(context).currentAccount()
                }
            }

            if (view == null || !::binding.isInitialized) return@launch
            binding.homeGreeting.text =
                if (name == null) getString(R.string.home_greeting)
                else getString(R.string.home_greeting_named, name)
        }
    }


    /**
     * Draws the playlist section for whatever the loader currently knows.
     *
     * Everything about which of the three views is on screen lives here, so there
     * is one answer rather than one per caller - `onCreateView`, both observers,
     * the split-mode observer and `onResume` all route through it.
     *
     * HOME itself is never blocked: the greeting, the three stream cards and the
     * whole navigation shell are untouched by any of these states. Only the
     * section that has nothing to show changes.
     */
    private fun renderPlaylistSection() {
        if (!::binding.isInitialized || !::playlistAdapter.isInitialized) return

        if (vm.isInSplitMode.value == true) {
            HomePlaylistSection.hide(
                binding.playlistString, binding.playlists, binding.playlistState
            )
            return
        }

        HomePlaylistSection.apply(
            state = HomePlaylistsState.of(
                state = vm.playlistsState.value,
                itemCount = vm.playlistList.value?.size ?: 0,
                isOnline = vm.isOnline(),
            ),
            heading = binding.playlistString,
            row = binding.playlists,
            status = binding.playlistState,
            loading = binding.playlistLoading,
            error = binding.playlistError,
            errorText = binding.playlistErrorText,
        )
    }

    private fun onPlaylistClick(playlist: MyataPlaylist){
        val intent = Intent(Intent.ACTION_VIEW)
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.setData(Uri.parse(playlist.uri))
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            // No browser installed on this device (common on Android TV/projectors)
            android.widget.Toast.makeText(
                requireContext(),
                "Не удалось открыть ссылку",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

}