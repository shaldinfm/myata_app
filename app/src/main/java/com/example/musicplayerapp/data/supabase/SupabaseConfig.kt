package com.example.musicplayerapp.data.supabase

import com.example.musicplayerapp.BuildConfig

/**
 * Where the app's Supabase project is, and whether it has one at all.
 *
 * Both values come from `supabase.properties` in the project root through
 * `BuildConfig`, the same untracked-file route release signing uses. Neither is a
 * secret: the project URL is public by definition and the **publishable** key
 * (`sb_publishable_...`) is designed to ship in clients - it grants nothing on its
 * own, because every table is behind RLS and every row is owned by an
 * `auth.uid()`. A **secret** key (`sb_secret_...`, or the legacy `service_role`)
 * bypasses RLS entirely and must never be in an APK; the build fails if one is
 * found in that file.
 *
 * [isConfigured] being false is a supported, ordinary state, not an error. A fresh
 * clone, CI and any build made without the file get an app with no Supabase in it,
 * and the listener cannot tell: nothing in the radio, the Collection or the
 * reaction model asks this class anything.
 */
object SupabaseConfig {

    val url: String = BuildConfig.SUPABASE_URL

    val publishableKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    /**
     * Whether this build can talk to Supabase at all.
     *
     * The `https` check is not decoration. `network_security_config` permits
     * cleartext for exactly one host, the audio stream, so a project URL that
     * arrived without a scheme would fail at the TLS layer in a way that reads like
     * a Supabase outage; refusing it here says what is actually wrong.
     */
    val isConfigured: Boolean =
        url.startsWith("https://") && publishableKey.isNotBlank()

    /**
     * True for a key that must never have been in the app to begin with.
     *
     * The build already refuses these, so this is the runtime half of the same
     * check - a belt-and-braces guard for a mistake whose blast radius is every
     * row in the database.
     */
    fun isSecretKey(key: String): Boolean =
        key.startsWith("sb_secret_") || key.startsWith("service_role")
}
