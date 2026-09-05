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
import com.example.musicplayerapp.data.ThemeStore
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
     * A destination asking for the bottom bar.
     *
     * Direct, now that HOME is the first application screen. This used to be a
     * gate: the bar had to be held back until the artwork splash was gone, because
     * MainFragment.onResume runs when the transaction commits and the splash was
     * still the only thing drawn, which put the bar on top of it for ~0.5-0.7s of
     * every cold launch. With no second splash there is nothing to be on top of,
     * and the pre-draw listener that timed the reveal has gone with it.
     *
     * The bar starts visible rather than being revealed - see onCreate - so HOME's
     * first frame already has it and there is no hidden-to-visible step to see.
     */
    fun showBottomNav() {
        binding.bottomNavView.visibility = android.view.View.VISIBLE
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

        // The chosen appearance, applied to **this activity's delegate** and to
        // nothing else (G1). Before super.onCreate, which is the documented point
        // for it: set later, AppCompat has already applied a night mode for this
        // activity and has to undo it, which costs a recreation on the way in.
        //
        // `localNightMode` rather than `AppCompatDelegate.setDefaultNightMode`, and
        // the difference is the whole of the TV isolation. The default is a static,
        // process-wide switch; TvMainActivity is an AppCompatActivity in this same
        // process and the <application> theme it sits under is now a DayNight tree,
        // so a process-wide night mode set from a phone screen would reach a TV
        // surface that cannot open that screen. Scoped to this delegate, it cannot.
        // Nothing in this app calls setDefaultNightMode; AppearanceSelectionTest
        // asserts the process default is never forced afterwards.
        //
        // An install that has never opened the appearance screen has no key on disk,
        // ThemeStore answers SYSTEM, and SYSTEM assigns MODE_NIGHT_UNSPECIFIED -
        // AppCompat's own "no local override", which is the exact state this activity
        // was in before G1. So the upgrade path is that there is nothing to migrate,
        // and the default path costs nothing: assigning the explicit
        // MODE_NIGHT_FOLLOW_SYSTEM instead was measured recreating the activity on
        // every cold start. See ThemeMode.localNightMode.
        //
        // The uiMode change this can cause is not in configChanges, so a change made
        // on the appearance screen recreates the activity and comes back through
        // here. See applySystemBarAppearance below, which was already written for
        // exactly that path.
        delegate.localNightMode = ThemeStore.read(this).localNightMode()

        super.onCreate(savedInstanceState)

        // No setTheme() here any more. It used to install one of AppTheme0..9, whose
        // only real content was a random full-bleed windowBackground - and the only
        // thing that ever displayed that artwork was SplashFragment, which copied it
        // into an ImageView. With the splash gone the artwork would have been decoded
        // on every cold start and never seen, so the themes and the ten 8.3MB bitmaps
        // went with it. AppTheme, installed by installSplashScreen() above as the
        // postSplashScreenTheme, is the whole story now.
        
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

        applySystemBarAppearance()
        
        // Visible from the first frame. HOME is the first application screen and
        // every destination that follows it shows the bar too, so there is nothing
        // to hide it for - and starting hidden would put a hidden-to-visible step
        // inside HOME's first frame for no reason. The two screens that genuinely
        // hide it (split mode, and ABOUT's export sheet) still do so themselves.
        binding.bottomNavView.visibility = android.view.View.VISIBLE

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

        // profile-guest is a pushed destination and the frame has no bottom bar, so
        // the bar follows the destination rather than being toggled by whichever
        // screen happened to open the profile. Doing it here also means Back
        // restores it without any of the three entry points having to remember to.
        //
        // GONE, not INVISIBLE: the Mini Player is constrained to the bar's top
        // edge, and a GONE view collapses to a point at its own position, which
        // drops the player to the screen bottom rather than leaving it floating
        // above a 76dp hole.
        //
        // Split mode owns the bar too and sets it directly; the two do not fight,
        // because split mode is not entered while the profile is open.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            // The two auth screens joined the profile here at G-A4c1. Their frames
            // have no bottom bar either, and they are reached only from the profile,
            // so the bar would otherwise appear for the length of a sign-in and
            // vanish again - offering four destinations to somebody in the middle of
            // typing a password.
            // settings and settings-appearance joined them at G1, for the same
            // reason and from the same place: neither frozen frame has a bottom
            // bar, and settings is now what the 40x40 header control opens, so the
            // bar would otherwise be present on the parent of a screen that hides
            // it and absent on the child.
            val hidesBottomBar = destination.id == R.id.profile ||
                destination.id == R.id.profile_authenticated ||
                destination.id == R.id.auth_sign_in ||
                destination.id == R.id.auth_create_account ||
                destination.id == R.id.settings ||
                destination.id == R.id.settings_appearance

            binding.bottomNavView.visibility =
                if (hidesBottomBar) android.view.View.GONE
                else android.view.View.VISIBLE
        }


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

    /**
     * Which way round the system bar icons are drawn.
     *
     * targetSdk 36 runs the window EDGE_TO_EDGE_ENFORCED, which makes the platform
     * ignore `android:statusBarColor` and leave the bar transparent: what shows
     * behind the clock and the battery is the app's own `background` role. That is
     * `#F8F9FA` in Light, so the bar needs **dark** icons, and `#0F253E` in Dark, so
     * it needs light ones. Without this the bar is technically visible and
     * practically unreadable - white on near-white - which is how it first came back
     * when `windowFullscreen` was removed.
     *
     * `isAppearanceLightStatusBars = true` means "the background behind me is light,
     * draw dark icons", which is why it is the negation of night mode.
     *
     * Read from the configuration rather than stored, and applied in onCreate:
     * `uiMode` is not in this activity's `configChanges`, so a day/night switch
     * recreates the activity and this runs again with the new answer.
     */
    private fun applySystemBarAppearance() {
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        // statusBarColor/navigationBarColor are deprecated because the platform stopped
        // honouring them at API 35 - which is exactly the distinction [behind] draws, so
        // reading them here is deliberate rather than overlooked.
        @Suppress("DEPRECATION")
        controller.isAppearanceLightStatusBars = isLight(behind(window.statusBarColor))
        @Suppress("DEPRECATION")
        controller.isAppearanceLightNavigationBars = isLight(behind(window.navigationBarColor))
    }

    /**
     * What is actually painted behind a system bar, which is not the same answer on
     * every API level and is the whole reason this is a function.
     *
     * From API 35 the window is edge-to-edge enforced: the platform ignores
     * `statusBarColor` / `navigationBarColor` entirely and leaves the bars
     * transparent, so what shows through is the app's own `background` role. Below
     * 35 those colours are still honoured and painted as an opaque strip, and this
     * app's themes set `statusBarColor` to `main_fragment`, which is `#000000`.
     *
     * Deriving the icon colour from the app background on every level - which the
     * first version of this did - therefore produced dark icons on a black bar on
     * API 24: a status bar that was present, correct, and completely invisible.
     * Measured, the band had exactly one distinct colour in it.
     *
     * @param themeColor the colour the theme asks for, used only where the platform
     *   still honours it.
     */
    private fun behind(themeColor: Int): Int =
        if (Build.VERSION.SDK_INT >= 35) {
            androidx.core.content.ContextCompat.getColor(this, R.color.background)
        } else {
            themeColor
        }

    /** Whether icons drawn on [color] need to be dark to be legible. */
    private fun isLight(color: Int): Boolean =
        androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.5

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