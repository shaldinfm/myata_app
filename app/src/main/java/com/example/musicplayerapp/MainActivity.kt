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


class MainActivity : AppCompatActivity() {

    lateinit var viewModel: StreamsViewModel
    lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {

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

        super.onCreate(savedInstanceState)

        val receiver = closeBroadcastReceiver()
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, IntentFilter("Dismiss"))

        val viewModelProviderFactory = StreamsViewModelFactory(application)
        viewModel = ViewModelProvider(this, viewModelProviderFactory).get(StreamsViewModel ::class.java)

        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)

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
//                    when(viewModel.currentStreamLive.value){
//                        "myata"->{binding.playerBtn.setColorFilter(Color.parseColor("#FFCCFF"))}
//                        "gold"->{binding.playerBtn.setColorFilter(Color.parseColor("#FF3F7B"))}
//                        "myata_hits"->{binding.playerBtn.setColorFilter(Color.parseColor("#FF3F7B"))}
//                    }
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


        if(intent.action != Intent.ACTION_MAIN){
            viewModel.currentStreamLive.value = intent.action
            viewModel.ifNeedToNavigateStraightToPlayer = true
        }


        supportActionBar?.hide()

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
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
        viewModel.getStreamJson()
        super.onRestart()
    }

    override fun onDestroy() {
        this.stopService(Intent(this, MediaPlayerService::class.java))
        super.onDestroy()
        finishAffinity()

        this@MainActivity.finish()
        System.exit(0)
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