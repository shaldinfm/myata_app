package com.example.musicplayerapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.LayoutInflaterCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.findNavController
import com.example.musicplayerapp.data.Streams
import com.example.musicplayerapp.databinding.ActivityMainBinding
import com.example.musicplayerapp.service.MediaPlayerService
import com.example.musicplayerapp.service.PlaybackLog
import com.example.musicplayerapp.ui.MiniPlayer
import com.example.musicplayerapp.ui.MyataTypography
import com.google.firebase.crashlytics.FirebaseCrashlytics


class MainActivity : AppCompatActivity() {

    lateinit var viewModel: StreamsViewModel
    lateinit var binding: ActivityMainBinding
    private var dismissReceiver: BroadcastReceiver? = null

    /**
     * The bottom bar belongs to the shell, not to any destination, so the shell
     * decides when it is allowed on screen. Destinations only state what they
     * want; [showBottomNav] applies it when it is safe to.
     *
     * The gate exists because on the one edge out of the splash, a destination
     * asks too early: MainFragment.onResume runs when the transaction commits,
     * with the splash artwork still the only thing drawn. Revealing the bar there
     * put the migrated bar on top of the un-migrated splash artwork for ~0.5-0.7s
     * of every cold launch.
     *
     * The signal is HOME's own first draw, taken from an
     * [android.view.ViewTreeObserver.OnPreDrawListener] on its root. That is the
     * frame in which HOME becomes the visible content, and it is safe by
     * construction rather than by timing: HOME's root fills the window and paints
     * `@color/background`, so once it draws opaque the splash behind it
     * contributes no pixels at all. The listener therefore both conditions are
     * satisfied in the same instant - the bar cannot land on the splash, and it
     * cannot land a frame after HOME either.
     *
     * The first attempt used onFragmentViewDestroyed for the splash instead. That
     * was the wrong event, and measurement is what showed it: HOME draws at
     * alpha 1.0 immediately, but the splash's view lingered ~815ms behind it -
     * invisible, occluded, and irrelevant - so the bar arrived that much after
     * HOME. Waiting for the splash to be torn down was waiting for something the
     * user cannot see.
     *
     * `alpha >= 1f` is still required before revealing. HOME is not faded in on
     * any device measured here, but if a transition ever does fade it, a
     * translucent HOME would let the splash through and the bar would be back on
     * top of it. The check costs nothing and removes that dependency.
     */
    private var splashHasView = false
    private var bottomNavRequested = false
    private var homeFirstFrameListener: android.view.ViewTreeObserver.OnPreDrawListener? = null

    /**
     * The observer the listener was actually registered on, kept so it can be
     * removed from that one. `view.viewTreeObserver` is only the same object while
     * the view stays attached, so re-reading it to unregister is a leak waiting
     * for the view to be detached first.
     */
    private var homeFirstFrameObserver: android.view.ViewTreeObserver? = null

    /**
     * A destination asking for the bottom bar.
     *
     * Applied immediately whenever no splash view exists, which is every path
     * except the first launch - a warm launch, a relaunch after process death,
     * and every later visit to HOME, COLLECTION or ABOUT US. On the splash edge it
     * is remembered, and [armHomeFirstFrameReveal] applies it with HOME's first
     * frame.
     *
     * Hiding needs no gate and stays direct: hiding while the splash is up is
     * what the splash wants anyway.
     */
    fun showBottomNav() {
        bottomNavRequested = true
        if (!splashHasView) {
            binding.bottomNavView.visibility = android.view.View.VISIBLE
        }
    }

    /**
     * Reveal the bar in the traversal that first draws [homeRoot] opaque.
     *
     * Only armed while the splash still has a view, so it costs nothing on the
     * paths that never see a splash.
     */
    private fun armHomeFirstFrameReveal(homeRoot: android.view.View) {
        disarmHomeFirstFrameReveal()
        val observer = homeRoot.viewTreeObserver
        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (homeRoot.width == 0 || homeRoot.height == 0 || homeRoot.alpha < 1f) {
                    // Not yet the frame that presents HOME. Let it draw and look again.
                    return true
                }
                if (bottomNavRequested) {
                    binding.bottomNavView.visibility = android.view.View.VISIBLE
                }
                disarmHomeFirstFrameReveal()
                return true
            }
        }
        homeFirstFrameListener = listener
        homeFirstFrameObserver = observer
        observer.addOnPreDrawListener(listener)
    }

    private fun disarmHomeFirstFrameReveal() {
        val listener = homeFirstFrameListener ?: return
        homeFirstFrameObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        homeFirstFrameListener = null
        homeFirstFrameObserver = null
    }

    // Non-null since androidx.activity 1.9.0, which arrived with the Supabase
    // dependency: ComponentActivity declares the parameter @NonNull, so an
    // `Intent?` override no longer overrides anything. Only the signature changes -
    // handleIntent still takes a nullable and still returns early on null, because
    // the intent that reaches it from onCreate genuinely can be null.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the intent stored in this activity
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        // The action is only a stream name for our own deep links. Framework
        // actions - the manifest also declares android.intent.action.PLAY on this
        // activity - must never become a stream key: nothing matches it further
        // down and playback silently never starts (issue #14).
        val requestedStream = Streams.normalise(intent.action)

        if (requestedStream != null) {
            // Only update if stream actually changed
            if (viewModel.currentStreamLive.value != requestedStream) {
                viewModel.currentStreamLive.value = requestedStream
            }

            // Navigate to player tab if not already there
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.navHostFragment) as? androidx.navigation.fragment.NavHostFragment
            val navController = navHostFragment?.navController
            
            if (navController != null) {
                if (navController.currentDestination?.id != R.id.player) {
                    navController.navigate(R.id.player)
                }
            }
        } else if (intent.action != null && intent.action != Intent.ACTION_MAIN) {
            PlaybackLog.event(
                "LAUNCH_ACTION_IGNORED",
                "action" to intent.action,
                "reason" to "not_a_stream_key"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate, and before the inflater factory below, because
        // this is what swaps the activity off the launch theme and onto AppTheme
        // (Theme.Myata.Splash declares it as postSplashScreenTheme). After this
        // line the live theme is what it has always been, and the random
        // AppTheme0..9 below still applies on top of it.
        //
        // No setKeepOnScreenCondition. The splash is dismissed by the first frame
        // the app draws; nothing is held back to show branding for longer.
        installSplashScreen()

        // Before super.onCreate, and it has to be: AppCompat installs its own
        // inflater factory during onCreate and skips it if one is already set, so
        // this is the only point at which ours can wrap it rather than lose to it.
        // Everything mobile inflates through this inflater or a clone of it -
        // fragments, adapters, the history bottom sheet - so this one line is what
        // makes android:textAppearance carry a whole token. TvMainActivity is
        // deliberately not given it; TV is not on the 3.6.6 typography.
        LayoutInflaterCompat.setFactory2(layoutInflater, MyataTypography.Factory(delegate))

        super.onCreate(savedInstanceState)

        val theme = (0..9).random()

        when(theme){
            0->{ setTheme(R.style.AppTheme0) }
            1->{ setTheme(R.style.AppTheme1) }
            2->{ setTheme(R.style.AppTheme2) }
            3->{ setTheme(R.style.AppTheme3) }
            4->{ setTheme(R.style.AppTheme4) }
            5->{ setTheme(R.style.AppTheme5) }
            6->{ setTheme(R.style.AppTheme6) }
            7->{ setTheme(R.style.AppTheme7) }
            8->{ setTheme(R.style.AppTheme8) }
            9->{ setTheme(R.style.AppTheme9) }
        }
        
        // Projector/TV Detection Hack:
        // If we are on a device that identifies as a TV OR has no touchscreen, 
        // redirect to the TV UI immediately.
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager
        val isTvMode = uiModeManager.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val hasTouchScreen = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
        
        if (isTvMode || !hasTouchScreen) {
             Log.d("MainActivity", "Device detected as TV (Mode: $isTvMode, Touch: $hasTouchScreen). Redirecting to TvMainActivity.")
             startActivity(Intent(this, TvMainActivity::class.java))
             finish()
             return
        }

        dismissReceiver = closeBroadcastReceiver()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(dismissReceiver!!, IntentFilter("Dismiss"))

        val viewModelProviderFactory = StreamsViewModelFactory(application, this)
        viewModel = ViewModelProvider(this, viewModelProviderFactory).get(StreamsViewModel ::class.java)

        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        
        // Ensure bottom navigation is hidden on startup (splash screen)
        binding.bottomNavView.visibility = android.view.View.GONE

        // Handle window insets for accessibility (lift bottom menu above system navigation)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavView) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            // The bar used to float, so the system inset was added as a bottom
            // margin. The frozen design has it flush with the bottom edge, so the
            // inset becomes bottom padding instead: the surface still reaches the
            // edge while its content clears the system navigation.
            // setPaddingRelative, and the horizontal values re-applied from
            // resources: setPadding() reads paddingLeft/paddingRight before RTL
            // resolution has run and switches the view out of relative-padding
            // mode, which silently dropped the bar's frozen 23.32/18dp side
            // padding and let the items span the full screen width.
            v.setPaddingRelative(
                resources.getDimensionPixelSize(R.dimen.bottom_nav_padding_start),
                v.paddingTop,
                resources.getDimensionPixelSize(R.dimen.bottom_nav_padding_end),
                resources.getDimensionPixelSize(R.dimen.bottom_nav_padding_bottom) + bars.bottom
            )
            insets
        }

        viewModel.currentFragmentLiveData.observe(this, Observer { current ->
            // Frozen 3.6.6 BottomNavBar: the active destination gets a #FFCCFF pill
            // with #00723D content; the rest use the text_secondary semantic role,
            // which resolves per theme. Both colours are read from resources so
            // Light and Dark follow the theme rather than a hardcoded value.
            val inactiveColor = androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary)
            val activeColor = androidx.core.content.ContextCompat.getColor(this, R.color.brand_nav_active_content)

            val items = listOf(
                Triple("main", binding.navItemHome, binding.homeBtn to binding.homeLabel),
                Triple("player", binding.navItemPlayer, binding.playerBtn to binding.playerLabel),
                Triple("favorites", binding.navItemFavorites, binding.favoritesBtn to binding.favoritesLabel),
                Triple("info", binding.navItemInfo, binding.infoBtn to binding.infoLabel)
            )
            for ((key, container, views) in items) {
                val active = key == current
                val (icon, label) = views
                container.setBackgroundResource(if (active) R.drawable.bg_nav_active_pill else 0)
                icon.setColorFilter(if (active) activeColor else inactiveColor)
                label.setTextColor(if (active) activeColor else inactiveColor)
            }
        })


        handleIntent(intent)


        supportActionBar?.hide()

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Centralized Navigation Handling with proper back stack management
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.navHostFragment) as androidx.navigation.fragment.NavHostFragment
        val navController = navHostFragment.navController

        // Registered here, inside onCreate, which is before the NavHost's own
        // child fragments reach onCreateView - those run when the fragment
        // manager moves to STARTED, after this method returns. So the splash's
        // view is always seen being created, and never missed.
        //
        // A warm launch and a launch after process death never create a
        // SplashFragment at all - the navigation state is restored past it - so
        // splashHasView simply stays false there and the bar is never held back.
        navHostFragment.childFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager, f: Fragment, v: android.view.View, s: Bundle?
                ) {
                    if (f is com.example.musicplayerapp.fragments.SplashFragment) {
                        splashHasView = true
                    }
                    // Only on the edge out of the splash. Everywhere else
                    // showBottomNav() is already immediate, and arming here would
                    // add a pre-draw listener to every visit to HOME for nothing.
                    if (f is com.example.musicplayerapp.fragments.MainFragment && splashHasView) {
                        armHomeFirstFrameReveal(v)
                    }
                }

                override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
                    if (f is com.example.musicplayerapp.fragments.SplashFragment) {
                        splashHasView = false
                        // Backstop. HOME's first frame is what normally reveals the
                        // bar; this only matters if HOME somehow never draws opaque,
                        // in which case a late bar still beats no bar.
                        if (bottomNavRequested) {
                            binding.bottomNavView.visibility = android.view.View.VISIBLE
                        }
                    }
                    if (f is com.example.musicplayerapp.fragments.MainFragment) {
                        disarmHomeFirstFrameReveal()
                    }
                }
            },
            false
        )


        val navOptions = androidx.navigation.NavOptions.Builder()
            .setPopUpTo(R.id.home, false) // Pop up to home, but don't pop home itself
            .setLaunchSingleTop(true)     // Don't create multiple instances of the same fragment
            .setEnterAnim(R.anim.fade_in)
            .setExitAnim(R.anim.fade_out)
            .build()
            
        // The frozen 3.6.6 design has four destinations, and Donate is not one of
        // them: donation lives inside "О нас", which hands it to YooMoney rather
        // than running a payment screen of its own. There is no fifth destination
        // left to route to.
        binding.navItemHome.setOnClickListener {
            // When going home, pop everything else off the stack
            if (navController.currentDestination?.id != R.id.home) {
                navController.popBackStack(R.id.home, false)
            }
        }
        // One way to reach PLAYER, used by the bottom navigation and by the Mini
        // Player body. Sharing the call rather than repeating it is what keeps the
        // pill inside the existing navigation architecture instead of opening a
        // second route to the same destination.
        val openPlayer = {
            if (navController.currentDestination?.id != R.id.player) {
                navController.navigate(R.id.player, null, navOptions)
            }
        }
        binding.navItemPlayer.setOnClickListener { openPlayer() }

        // The Mini Player is bound once, here, for the reason given in
        // activity_main.xml: one pill on the shell, reading the one playback
        // state, outliving every fragment transaction.
        MiniPlayer(binding.miniPlayer, viewModel, openPlayer).bind(this)
        binding.navItemFavorites.setOnClickListener {
            if (navController.currentDestination?.id != R.id.favorites) {
                navController.navigate(R.id.favorites, null, navOptions)
            }
        }
        binding.navItemInfo.setOnClickListener {
            if (navController.currentDestination?.id != R.id.info) {
                navController.navigate(R.id.info, null, navOptions)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Ensure UI is marked active and refresh status for instant sync
        viewModel.isUIActive = true
        viewModel.refreshPlayerStatus()
    }

    override fun onStop() {
        viewModel.isUIActive = false
        Log.d("MainActivity", "Stop")
        super.onStop()
    }

    override fun onResume() {
        Log.d("MainActivity","Resume")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            viewModel.isInSplitMode.value = this.isInMultiWindowMode
        }
        super.onResume()
    }

    override fun onResumeFragments() {
        Log.d("MainActivity","ResumeFragment")
        super.onResumeFragments()
    }

    override fun onRestart() {
        Log.d("MainActivity", "Restart")
        viewModel.isUIActive = true

        super.onRestart()
    }

    override fun onDestroy() {
        dismissReceiver?.let { LocalBroadcastManager.getInstance(this).unregisterReceiver(it) }
        dismissReceiver = null
        super.onDestroy()
        Log.d("MainActivity", "Destroyed")
    }

    inner class closeBroadcastReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) {
                if (intent.action == "Dismiss") {
                    Log.e("MAINACTIVITY", "Destroy")
                }
            }
        }
    }
}