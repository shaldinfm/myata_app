package com.example.musicplayerapp.data.supabase

import android.content.Context
import android.util.Log
import com.example.musicplayerapp.SecureNetModule
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.okhttp.OkHttp

/**
 * The app's Supabase client, or none.
 *
 * A hand-rolled lazy singleton, like [SecureNetModule], because this project has no
 * DI framework and one more `object` is cheaper than introducing one.
 *
 * Two things about it matter more than its contents:
 *
 *  - **It is optional.** [client] is null whenever the build has no project
 *    configured, and every caller has to handle that. That is not a degraded mode
 *    to be fixed later; it is the guarantee that the radio, the Collection and the
 *    reaction model never depend on a backend being there.
 *  - **It is not on any hot path.** Nothing here is touched by playback, by the
 *    PLAYER controls or by a reaction write. Those are Room's, and they stay
 *    Room's; this exists so a later phase can *mirror* them.
 *
 * The HTTP engine is the app's own [SecureNetModule] client. supabase-kt speaks
 * Ktor, Ktor's OkHttp engine can be handed a preconfigured OkHttpClient, so
 * Supabase traffic reuses this app's connection pool, timeouts and - the reason it
 * is worth doing - the platform `network_security_config`, whose bundled roots are
 * what make TLS work on API 24 devices. A second HTTP stack would be a second TLS
 * configuration to get right on exactly the devices least able to cope.
 */
object SupabaseModule {

    private const val TAG = "Supabase"

    @Volatile
    private var instance: SupabaseClient? = null

    /**
     * The client, or null when this build has no Supabase project.
     *
     * Safe to call from any thread. Building it performs no I/O - no connection is
     * made until something actually asks for a session or a row.
     */
    fun client(context: Context): SupabaseClient? {
        if (!SupabaseConfig.isConfigured) return null

        return instance ?: synchronized(this) {
            instance ?: create(context).also { instance = it }
        }
    }

    private fun create(context: Context): SupabaseClient {
        val okHttp = SecureNetModule.getOkHttpClient(context)
        Log.d(TAG, "creating client for ${SupabaseConfig.url}")

        return createSupabaseClient(
            supabaseUrl = SupabaseConfig.url,
            supabaseKey = SupabaseConfig.publishableKey,
        ) {
            httpEngine = OkHttp.create { preconfigured = okHttp }

            install(Auth) {
                // The session is persisted and restored by the Auth plugin's Android
                // defaults, which is the whole reason this is a dependency rather
                // than hand-written HTTP: an anonymous listener must come back as
                // the same auth.uid() after a process death, and that means storing
                // a refresh token and refreshing it before it expires.
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
            }

            install(Postgrest)
        }
    }
}
