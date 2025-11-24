package com.example.musicplayerapp.service

import android.app.*
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerNotificationManager
import com.example.musicplayerapp.MainActivity


class MediaPlayerService(): Service(){

    private lateinit var exoPlayer: ExoPlayer
    val myataItem = MediaItem.fromUri("https://radio.dline-media.com/myata")
    val xtraItem = MediaItem.fromUri("https://radio.dline-media.com/myata_hits")
    val goldItem = MediaItem.fromUri("https://radio.dline-media.com/gold")

    var playerNotificationManager: PlayerNotificationManager? = null
    var song: String = ""
    var artist: String = ""
    var stream: String = ""
    lateinit var notification: Notification


    val mediaDescriptionAdapter = object: PlayerNotificationManager.MediaDescriptionAdapter{
        override fun getCurrentContentTitle(player: Player): CharSequence {
            return artist
        }

        override fun createCurrentContentIntent(player: Player): PendingIntent? {
            val intent = Intent(this@MediaPlayerService, MainActivity::class.java)
            intent.action = stream
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.getActivity(this@MediaPlayerService,0,intent,PendingIntent.FLAG_MUTABLE)
            } else {
                PendingIntent.getActivity(this@MediaPlayerService,0,intent,0)
            }
        }


        override fun getCurrentContentText(player: Player): CharSequence? {
            return song
        }

        override fun getCurrentSubText(player: Player): CharSequence? {
            return super.getCurrentSubText(player)
        }

        override fun getCurrentLargeIcon(
            player: Player,
            callback: PlayerNotificationManager.BitmapCallback
        ): Bitmap? {
            return null
        }
    }


    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        super.onStartCommand(intent, flags, startId)

        if(intent != null) {
            when(intent?.getStringExtra("ACTION")){
                "startStop"->{
                    if(exoPlayer.isPlaying) {
                        exoPlayer.stop()
                        exoPlayer.clearMediaItems()
                    }
                    else{
                        if (stream != intent.getStringExtra("STREAM")!!)
                        {
                            stream = intent.getStringExtra("STREAM")!!
                            when(stream){
                                "myata"->{exoPlayer.setMediaItem(myataItem)}
                                "gold"->{exoPlayer.setMediaItem(goldItem)}
                                "myata_hits"->{exoPlayer.setMediaItem(xtraItem)}
                            }
                            updateNotificationColor(stream)
                        }
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }
                }
                "play"->{
                    if (stream != intent.getStringExtra("STREAM")!!)
                    {
                        stream = intent.getStringExtra("STREAM")!!
                        when(stream){
                            "myata"->{exoPlayer.setMediaItem(myataItem)}
                            "gold"->{exoPlayer.setMediaItem(goldItem)}
                            "myata_hits"->{exoPlayer.setMediaItem(xtraItem)}
                        }
                        exoPlayer.prepare()
                        updateNotificationColor(stream)
                    }
                    if(!exoPlayer.isPlaying) {
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }
                }
                "switch"->{
                    val wasPlaying = exoPlayer.isPlaying
                    if (stream != intent.getStringExtra("STREAM")!!)
                    {
                        stream = intent.getStringExtra("STREAM")!!
                        when(stream){
                            "myata"->{exoPlayer.setMediaItem(myataItem)}
                            "gold"->{exoPlayer.setMediaItem(goldItem)}
                            "myata_hits"->{exoPlayer.setMediaItem(xtraItem)}
                        }
                        updateNotificationColor(stream)
                        // If was playing, restart playback on new stream
                        if (wasPlaying) {
                            exoPlayer.prepare()
                            exoPlayer.play()
                            Log.d("SWITCH", "Stream switched to $stream and playback resumed")
                        }
                    }
                    song = intent.getStringExtra("SONG")!!
                    artist = intent.getStringExtra("ARTIST")!!
                }

                "switch_track"->{
                    song = intent.getStringExtra("SONG")!!
                    artist = intent.getStringExtra("ARTIST")!!
                    // For live radio, we just update metadata - no need to pause/play
                    // PlayerNotificationManager will automatically update the notification
                    Log.d("SWITCH", "Track metadata updated: $artist - $song")
                }
            }
        }

        return START_STICKY
    }

    override fun onCreate() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = Notification.Builder(this, createNotificationChannel("307","307"))
                .build()
            startForeground(307, notification)
        } else {
            notification = Notification.Builder(this)
                .build()
            startForeground(307, notification)
        }

        Log.e("Service","Create")

        // Configure LoadControl for stable playback (increased buffers)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                3000,  // minBufferMs - increased for stability
                5000,  // maxBufferMs
                1500,  // bufferForPlaybackMs - increased to prevent initial stutter
                2000   // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Configure AudioAttributes for proper audio focus handling
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true) // true = automatic audio focus handling
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK) // Prevent CPU sleep
            .build().apply {
            addListener(object: Player.Listener{
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    super.onIsPlayingChanged(isPlaying)
                    
                    val action = if(isPlaying) "play" else "pause"
                    val intent = Intent(action).apply {
                    }
                    LocalBroadcastManager.getInstance(this@MediaPlayerService)
                        .sendBroadcast(intent)
                }
            })
        }

        val notificationListener: PlayerNotificationManager.NotificationListener =
            object : PlayerNotificationManager.NotificationListener {

                override fun onNotificationCancelled(
                    notificationId: Int,
                    dismissedByUser: Boolean
                ) {
                    Log.d("DISMISS","onNotificationCancelled dismissedByUser $dismissedByUser")
                    stopSelf()
                }

                override fun onNotificationPosted(
                    notificationId: Int,
                    notification: Notification,
                    ongoing: Boolean
                ) {
                    if(ongoing){
                        startForeground(notificationId, notification)
                    }
                    else{
                        stopForeground(false)
                    }
                }
            }


        playerNotificationManager = PlayerNotificationManager.Builder(
            this, 307, "307")
            .setNotificationListener(notificationListener)
            .setMediaDescriptionAdapter(mediaDescriptionAdapter)
            .build()

        playerNotificationManager!!.setPlayer(exoPlayer)

        playerNotificationManager?.setUseStopAction(true)
        super.onCreate()
    }

    private fun updateNotificationColor(stream: String) {
        val color = when(stream) {
            "myata" -> 0xFF5FD9B4.toInt()  // Mint green
            "gold" -> 0xFF2FB56A.toInt()   // Green
            "myata_hits" -> 0xFF1C4771.toInt()  // Blue
            else -> 0xFF5FD9B4.toInt()
        }
        playerNotificationManager?.setColor(color)
    }


    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(channelId: String, channelName: String): String{
        val chan = NotificationChannel(channelId,
            "Радио Мята Плеер", NotificationManager.IMPORTANCE_LOW)
        chan.description = "Управление воспроизведением радио"
        chan.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this@MediaPlayerService)
            .sendBroadcast(Intent("Dismiss").apply {})
        playerNotificationManager?.setPlayer(null)
        stopForeground(true)
        stopSelf()
        Log.e("Service","Stopped")
        exoPlayer.release()
        super.onDestroy()
    }
}