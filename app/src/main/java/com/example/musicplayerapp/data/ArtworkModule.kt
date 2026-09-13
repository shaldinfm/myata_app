package com.example.musicplayerapp.data

import android.content.Context
import com.example.musicplayerapp.SecureNetModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The application's single artwork resolver, and the two clients it needs (G5c).
 *
 * There is one of each for the whole process, held the way [SecureNetModule]
 * holds the shared client - this app has no dependency-injection framework, and
 * a second singleton beside the one that already exists reads better than
 * threading an object through four constructors.
 *
 * ## Why artwork gets its own clients
 *
 * The shared client is the *playback* client: 30 s to connect and 30 s to read,
 * with no overall deadline, which is right for an audio stream that must survive
 * a slow start and wrong for a cover nobody will wait a minute for. Artwork
 * therefore takes that client's own `newBuilder()` - inheriting its connection
 * pool, its dispatcher and the platform TLS the network security config
 * applies - and only shortens the clocks and adds a cache. **Nothing here can
 * change how the audio stream connects**, which is the point of not editing the
 * shared client.
 *
 * ## Why the caches are worth having
 *
 * Both artwork CDNs are explicitly cacheable - Apple sends `max-age` of about
 * 195 days on an image and Deezer about 150 - so an ordinary HTTP cache holds a
 * cover across process restarts with no invalidation logic of our own. The
 * search API is cacheable too, at about 6.6 hours, so a repeat lookup after a
 * cold start can be answered from disk without reaching the provider at all -
 * which is worth something on a network where reaching it is the unreliable part.
 */
object ArtworkModule {

    /**
     * Artwork lookups die quickly. Measured from Russia, a working request
     * completes in about 2-3 s, so 10 s of total patience is generous while still
     * returning control long before the 60 s a blocked provider used to cost.
     */
    private const val API_CONNECT_SECONDS = 5L
    private const val API_READ_SECONDS = 8L
    private const val API_CALL_SECONDS = 10L

    /**
     * Images get longer: they are up to ~115 KB and worth waiting for on a slow
     * connection, but still bounded, which they were not before.
     */
    private const val IMAGE_CONNECT_SECONDS = 5L
    private const val IMAGE_READ_SECONDS = 15L
    private const val IMAGE_CALL_SECONDS = 20L

    /** The API answers are small JSON; the images are not. */
    private const val API_CACHE_BYTES = 4L * 1024 * 1024
    private const val IMAGE_CACHE_BYTES = 60L * 1024 * 1024

    @Volatile
    private var apiClient: OkHttpClient? = null

    @Volatile
    private var imageClient: OkHttpClient? = null

    @Volatile
    private var resolver: ArtworkResolver? = null

    /**
     * The scope shared lookups run in.
     *
     * Application-lived on purpose: a lookup outlives the row, the screen and the
     * ViewModel that happened to ask for it first, because everyone else waiting
     * on it should still get an answer. `SupervisorJob` so one failed lookup
     * cannot take the others down with it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The client for provider APIs: short deadlines, small disk cache. */
    fun apiClient(context: Context): OkHttpClient = apiClient ?: synchronized(this) {
        apiClient ?: SecureNetModule.getOkHttpClient(context).newBuilder()
            .connectTimeout(API_CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(API_READ_SECONDS, TimeUnit.SECONDS)
            .callTimeout(API_CALL_SECONDS, TimeUnit.SECONDS)
            .cache(cacheIn(context, "artwork-api", API_CACHE_BYTES))
            .build()
            .also { apiClient = it }
    }

    /** The client for cover images, used by Picasso and by the prefetch. */
    fun imageClient(context: Context): OkHttpClient = imageClient ?: synchronized(this) {
        imageClient ?: SecureNetModule.getOkHttpClient(context).newBuilder()
            .connectTimeout(IMAGE_CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(IMAGE_READ_SECONDS, TimeUnit.SECONDS)
            .callTimeout(IMAGE_CALL_SECONDS, TimeUnit.SECONDS)
            .cache(cacheIn(context, "artwork-images", IMAGE_CACHE_BYTES))
            .build()
            .also { imageClient = it }
    }

    /** The one resolver every surface asks. Safe to call from any thread. */
    fun resolver(context: Context): ArtworkResolver = resolver ?: synchronized(this) {
        resolver ?: ArtworkResolver(
            repository = ArtworkRepository(
                httpClient = apiClient(context),
                imageClient = imageClient(context),
            ),
            scope = scope,
        ).also { resolver = it }
    }

    /**
     * A disk cache, or none at all if the directory cannot be used.
     *
     * A cache is an optimisation; a device that refuses one still has to work, so
     * this never throws.
     */
    private fun cacheIn(context: Context, name: String, bytes: Long): Cache? = runCatching {
        Cache(File(context.applicationContext.cacheDir, name), bytes)
    }.getOrNull()
}
