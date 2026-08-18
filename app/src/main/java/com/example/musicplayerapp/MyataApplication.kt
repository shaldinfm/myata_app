package com.example.musicplayerapp

import android.app.Application
import com.example.musicplayerapp.data.supabase.AnonymousSession
import com.squareup.picasso.OkHttp3Downloader
import com.squareup.picasso.Picasso

class MyataApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // The listener's anonymous Supabase identity, if this build has a project
        // at all. Off the main thread, one request, and every failure ends in a log
        // line: nothing on this path can delay the splash or affect playback, and a
        // build with no Supabase configured does not even start it.
        //
        // Nothing reads the session yet. It is here so that the phase which syncs
        // reactions finds an identity already waiting instead of having to make one
        // at the moment somebody taps Like.
        AnonymousSession.ensureInBackground(this)

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
