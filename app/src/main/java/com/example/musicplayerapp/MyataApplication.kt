package com.example.musicplayerapp

import android.app.Application
import com.example.musicplayerapp.data.supabase.IdentityReconciler
import com.example.musicplayerapp.data.supabase.ReactionSyncScheduler
import com.squareup.picasso.OkHttp3Downloader
import com.squareup.picasso.Picasso

class MyataApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Restores a Supabase session this install already has, creates none, and
        // then repairs the persisted identity around whatever came back.
        //
        // Opening the radio is not a reason to exist in a database: a listener who
        // never reacts to anything never signs in, so there is no user row and no
        // request on their cold launch. The identity is minted at the sync
        // boundary - ListenerSession.identity - by the first caller that actually
        // has remote data to own, and reconciliation deliberately cannot mint at all.
        //
        // The repair half is what makes an interrupted registration survivable: the
        // server can have issued a session in the instant before this process died,
        // and only a cold start comparing that session against what is on disk can
        // notice. See IdentityReconciler.
        //
        // Off the main thread, and a build with no project configured does not even
        // start it. Nothing reads the session yet.
        IdentityReconciler.startupInBackground(this)

        // Startup recovery for the reaction outbox. A reaction commits to Room and
        // *then* schedules its drain, so a process death between the two leaves a
        // row with no wake-up; this is what finds it. It asks the outbox first and
        // schedules nothing when the answer is zero, so a listener who has never
        // reacted pays one COUNT on a table that is empty - and still no identity,
        // no request and no user row.
        ReactionSyncScheduler.onAppStart(this)

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
