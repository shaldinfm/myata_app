package com.example.musicplayerapp

import android.app.Application
import com.example.musicplayerapp.data.supabase.AnonymousSession
import com.squareup.picasso.OkHttp3Downloader
import com.squareup.picasso.Picasso

class MyataApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Restores a Supabase session this install already has, and creates none.
        // Opening the radio is not a reason to exist in a database: a listener who
        // never reacts to anything never signs in, so there is no user row and no
        // request on their cold launch. The identity is minted at the sync
        // boundary - AnonymousSession.ensureAuthenticatedListener - by the first
        // caller that actually has remote data to own.
        //
        // Off the main thread, and a build with no project configured does not even
        // start it. Nothing reads the session yet.
        AnonymousSession.restoreInBackground(this)

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
