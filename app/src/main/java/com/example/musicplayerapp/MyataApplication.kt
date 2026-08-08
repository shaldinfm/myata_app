package com.example.musicplayerapp

import android.app.Application
import com.squareup.picasso.OkHttp3Downloader
import com.squareup.picasso.Picasso

class MyataApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Configure Picasso to use the shared, fully validating OkHttpClient.
        // Old Android TV/projector trust stores are handled by the extra roots
        // bundled in SecureNetModule, not by disabling certificate checks.
        try {
            val client = SecureNetModule.getOkHttpClient(this)
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
            android.util.Log.e("MyataApplication", "Failed to init Picasso: ${e.message}")
        }
    }
}
