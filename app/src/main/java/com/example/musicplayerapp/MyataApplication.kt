package com.example.musicplayerapp

import android.app.Application
import com.squareup.picasso.OkHttp3Downloader
import com.squareup.picasso.Picasso

class MyataApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Configure Picasso to use Unsafe OkHttpClient
        // This handles cases where Android TV/Projectors have old/expired root certs
        // which causes loading images from Spotify (Let's Encrypt) to fail.
        try {
            val client = UnsafeNetModule.getUnsafeOkHttpClient()
            val picasso = Picasso.Builder(this)
                .downloader(OkHttp3Downloader(client))
                .listener { _, uri, exception -> 
                    android.util.Log.e("Picasso", "Error loading $uri: ${exception.message}")
                }
                .build()
            
            try {
                Picasso.setSingletonInstance(picasso)
            } catch (e: IllegalStateException) {
                // Singleton already set (should not happen in Application.onCreate)
                android.util.Log.w("Picasso", "Singleton already set")
            }
        } catch (e: Exception) {
            android.util.Log.e("MyataApplication", "Failed to init unsafe Picasso: ${e.message}")
        }
    }
}
