package com.example.musicplayerapp

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttpClient for the whole app.
 *
 * Replaces UnsafeNetModule, which installed a trust-all X509TrustManager and a
 * `hostnameVerifier { true }` on every request. Nothing here overrides TLS: the
 * client uses the platform SSL socket factory, so certificates and hostnames are
 * fully verified.
 *
 * The reason UnsafeNetModule existed is handled declaratively instead. Two of our
 * hosts chain to roots that older Android system trust stores do not carry:
 *
 *   radiomyata.ru         -> ISRG Root X1            (system store: Android 7.1.1+)
 *   radio.dline-media.com -> GlobalSign Root CA - R6 (system store: recent Android only)
 *
 * Both roots are bundled in res/raw and declared as extra trust anchors in
 * res/xml/network_security_config.xml. That config is applied by the platform to
 * every connection - OkHttp, HttpsURLConnection, Media3 and WebView alike - so no
 * per-client TLS code is needed. It is honoured from API 24, which is this
 * project's minSdk, so it applies on every device the app runs on.
 *
 * One client is shared so connection pool, dispatcher and cache are not duplicated
 * across the service, view models and Picasso.
 */
object SecureNetModule {

    @Volatile
    private var client: OkHttpClient? = null

    /** Shared, fully validating OkHttp client. Safe to call from any thread. */
    @Suppress("UNUSED_PARAMETER")
    fun getOkHttpClient(context: Context): OkHttpClient {
        return client ?: synchronized(this) {
            client ?: OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
                .also { client = it }
        }
    }
}
