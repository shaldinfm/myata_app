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
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.findNavController
import com.example.musicplayerapp.databinding.ActivityMainBinding
import com.example.musicplayerapp.service.MediaPlayerService
import com.google.firebase.crashlytics.FirebaseCrashlytics


class MainActivity : AppCompatActivity() {

    lateinit var viewModel: StreamsViewModel
    lateinit var binding: ActivityMainBinding

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the intent stored in this activity
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        
        if (intent.action != Intent.ACTION_MAIN && intent.action != null) {
            // Only update if stream actually changed
            if (viewModel.currentStreamLive.value != intent.action) {
                viewModel.currentStreamLive.value = intent.action
            }
            
            // Navigate to player tab if not already there
            val navController = findNavController(R.id.navHostFragment)
            if (navController.currentDestination?.id != R.id.player) {
                navController.navigate(R.id.player)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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

        val receiver = closeBroadcastReceiver()
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, IntentFilter("Dismiss"))

        val viewModelProviderFactory = StreamsViewModelFactory(application, this)
        viewModel = ViewModelProvider(this, viewModelProviderFactory).get(StreamsViewModel ::class.java)

        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        
        // Ensure bottom navigation is hidden on startup (splash screen)
        binding.bottomNavView.visibility = android.view.View.GONE

        // Handle window insets for accessibility (lift bottom menu above system navigation)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavView) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val params = v.layoutParams as android.view.ViewGroup.MarginLayoutParams
            // 10dp default margin converted to pixels
            val density = resources.displayMetrics.density
            val defaultMargin = (10 * density).toInt()
            
            params.bottomMargin = defaultMargin + bars.bottom
            v.layoutParams = params
            insets
        }

        viewModel.currentFragmentLiveData.observe(this, Observer {
            when(it){
                null->{
                    binding.infoBtn.setColorFilter(Color.parseColor("#67686D"))
                    binding.donateBtn.setColorFilter(Color.parseColor("#67686D"))
                    binding.homeBtn.setColorFilter(Color.parseColor("#67686D"))
                    binding.playerBtn.setColorFilter(Color.parseColor("#67686D"))
                }
                "main"->{
                    binding.infoBtn.setColorFilter(Color.parseColor("#67686D"))
                    binding.donateBtn.setColorFilter(Color.parseColor("#67686D"))
                    binding.homeBtn.setColorFilter(Color.parseColor("#FFFFFF"))
                    binding.playerBtn.setColorFilter(Color.parseColor("#67686D"))
                }
                "player"->{
                    binding.infoBtn.setColorFilter(Color.parseColor("#67686D"))
                    binding.donateBtn.setColorFilter(Color.parseColor("#67686D"))
                    binding.homeBtn.setColorFilter(Color.parseColor("#67686D"))
                    binding.playerBtn.setColorFilter(Color.parseColor("#FFFFFF"))
                    // binding code commented out
                }
                "donate"->{
                    binding.infoBtn.setColorFilter(Color.parseColor("#67686D"))
                    binding.donateBtn.setColorFilter(Color.parseColor("#FFFFFF"))
                    binding.homeBtn.setColorFilter(Color.parseColor("#67686D"))
                    binding.playerBtn.setColorFilter(Color.parseColor("#67686D"))
                }
                "info"->{
                    binding.donateBtn.setColorFilter(Color.parseColor("#67686D"))
                    binding.infoBtn.setColorFilter(Color.parseColor("#FFFFFF"))
                    binding.homeBtn.setColorFilter(Color.parseColor("#67686D"))
                    binding.playerBtn.setColorFilter(Color.parseColor("#67686D"))
                }
            }
        })


        handleIntent(intent)


        supportActionBar?.hide()

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Centralized Navigation Handling
        binding.homeBtn.setOnClickListener {
            findNavController(R.id.navHostFragment).navigate(R.id.home)
        }
        binding.infoBtn.setOnClickListener {
            findNavController(R.id.navHostFragment).navigate(R.id.info)
        }
        binding.donateBtn.setOnClickListener {
            findNavController(R.id.navHostFragment).navigate(R.id.donate)
        }
        binding.playerBtn.setOnClickListener {
            findNavController(R.id.navHostFragment).navigate(R.id.player)
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