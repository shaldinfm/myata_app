package com.example.musicplayerapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import com.example.musicplayerapp.databinding.ActivityTvMainBinding
import androidx.activity.viewModels

class TvMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvMainBinding
    private val vm: StreamsViewModel by viewModels {
        StreamsViewModelFactory(application, this)
    }
    private var isExitDialogShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.tv_fragment_container, com.example.musicplayerapp.fragments.TvSplashFragment())
                .commit()
        }

        // Using the modern OnBackPressedDispatcher
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentFragment = supportFragmentManager.findFragmentById(R.id.tv_fragment_container)
                val backstackCount = supportFragmentManager.backStackEntryCount
                
                android.util.Log.d("TvMainActivity", "=== BACK PRESSED === backstack: $backstackCount, fragment: $currentFragment")

                // 1. If on Splash, just exit the app
                if (currentFragment is com.example.musicplayerapp.fragments.TvSplashFragment) {
                    isEnabled = false // Disable this callback to allow system back
                    onBackPressedDispatcher.onBackPressed() // Calls the next callback or system
                    return
                }

                // 2. If in Player (backstack > 0), pop it to return to Selection
                if (backstackCount > 0) {
                    supportFragmentManager.popBackStack()
                } else {
                    // 3. On Selection screen, show the exit dialog
                    if (!isExitDialogShowing) {
                        showExitDialog()
                    }
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        vm.isUIActive = true
        vm.refreshPlayerStatus()
    }

    override fun onStop() {
        vm.isUIActive = false
        super.onStop()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (event == null) return super.dispatchKeyEvent(event)
        
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            // Wake up UI on ANY key press (D-Pad, Volume, etc.)
            val currentFragment = supportFragmentManager.findFragmentById(R.id.tv_fragment_container)
            if (currentFragment is com.example.musicplayerapp.fragments.TvPlayerFragment) {
                currentFragment.showBackButton()
            }

            android.util.Log.d("TvMainActivity", "Key Down: ${event.keyCode}, Source: ${event.source}")
            
            // GLOBAL INTERCEPT for Back Button in TvPlayerFragment
            if (currentFragment is com.example.musicplayerapp.fragments.TvPlayerFragment) {
                 val focusedView = currentFragment.view?.findFocus()
                 if (focusedView != null && focusedView.id == R.id.btn_back) {
                     if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || 
                         event.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                         event.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER || 
                         event.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A) {
                         
                        android.util.Log.d("TvMainActivity", "Intercepted ENTER on Back Button - Navigating globally")
                        
                        // Perform navigation directly by popping the stack
                        if (supportFragmentManager.backStackEntryCount > 0) {
                             supportFragmentManager.popBackStack()
                        } else {
                             // Fallback if stack is somehow empty (shouldn't happen on Player)
                             supportFragmentManager.beginTransaction()
                                .replace(R.id.tv_fragment_container, com.example.musicplayerapp.fragments.TvStreamSelectionFragment())
                                .commit()
                        }
                        return true // CONSUME EVENT
                     }
                 }
            }
        }
        
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent?): Boolean {
        // Wake up Back button on ANY touch/click event
        if (event != null && event.action == android.view.MotionEvent.ACTION_DOWN) {
             val currentFragment = supportFragmentManager.findFragmentById(R.id.tv_fragment_container)
             if (currentFragment is com.example.musicplayerapp.fragments.TvPlayerFragment) {
                 currentFragment.showBackButton()
             }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent?): Boolean {
        // Ensure Back button wakes up on ANY mouse/pointer movement
        if (event != null && event.action == android.view.MotionEvent.ACTION_HOVER_MOVE) {
             val currentFragment = supportFragmentManager.findFragmentById(R.id.tv_fragment_container)
             if (currentFragment is com.example.musicplayerapp.fragments.TvPlayerFragment) {
                 currentFragment.showBackButton()
             }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun showExitDialog() {
        isExitDialogShowing = true
        android.util.Log.d("TvMainActivity", "Showing exit dialog")
        
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Выход")
            .setMessage("Что вы хотите сделать?")
            .setPositiveButton("Закрыть") { _, _ ->
                val intent = android.content.Intent(this, com.example.musicplayerapp.service.MediaPlayerService::class.java).apply {
                    putExtra("ACTION", "stop")
                }
                startService(intent)
                finishAffinity()
            }
            .setNeutralButton("Свернуть") { _, _ ->
                isExitDialogShowing = false
                moveTaskToBack(true)
            }
            .setNegativeButton("Отмена") { _, _ ->
                isExitDialogShowing = false
            }
            .setCancelable(false)
            .create()
        
        dialog.setOnDismissListener {
            isExitDialogShowing = false
        }
        
        dialog.show()
    }
}
