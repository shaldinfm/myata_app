package com.example.musicplayerapp.data.lastfm

import com.example.musicplayerapp.BuildConfig

/**
 * Whether this build can talk to Last.fm at all, and the credentials it does it
 * with.
 *
 * Both values come from `lastfm.properties` in the project root through
 * `BuildConfig`, the same untracked-file route Supabase, the report endpoint and
 * release signing use. Unlike any of those, one of them is genuinely secret.
 *
 * ## The secret, and why it is here rather than on a server
 *
 * Every authenticated Last.fm method - `auth.getToken`, `auth.getSession`,
 * `track.updateNowPlaying`, `track.scrobble` - requires an `api_sig` derived from
 * the shared secret. There is no unsigned path and no PKCE-style equivalent, so a
 * client that scrobbles has to be able to sign, which means the secret is in the
 * APK and is extractable from it. That is the model Last.fm's own authentication
 * how-tos assume, and the owner accepted it for v1.
 *
 * What the exposure allows is narrower than it first looks:
 *
 *  - it does **not** expose any listener's account. Reading or writing a profile
 *    needs a per-user session key, obtained through Last.fm's own web
 *    authorisation and never leaving the device;
 *  - it **does** let someone make signed calls as this application, and the
 *    realistic worst case is abuse leading Last.fm to suspend the key
 *    ([LastfmError.SUSPENDED_API_KEY]), which disables scrobbling for everyone
 *    until a new key is issued.
 *
 * An availability risk, then, not a user-data one - and a reversible decision:
 * every signature goes through the [LastfmSigner] seam, so moving to server-side
 * signing is a second implementation of one interface rather than a rewrite.
 *
 * ## The secret is never logged, and only one class reads it
 *
 * [apiSecret] is `internal` and is read by [SharedSecretSigner] alone. Nothing
 * here, and nothing downstream, may put it in a log line, a crash report, a
 * `toString`, an analytics event or an error message - see [toString], which
 * exists to make the accidental case harmless.
 *
 * ## An unconfigured build is an ordinary state
 *
 * [isConfigured] being false is supported, not an error, and it is what CI and
 * every fresh clone build in. The app then has no Last.fm in it: no Settings row,
 * no now-playing, no queue, no worker. Nothing in the radio, the player or the
 * Collection asks this class anything.
 */
object LastfmConfig {

    /** Identifies this application to Last.fm. Public by design. */
    val apiKey: String = BuildConfig.LASTFM_API_KEY

    /**
     * The shared secret, for signing only.
     *
     * `internal` so the compiler keeps the blast radius to this module, and read by
     * [SharedSecretSigner] and its tests and nothing else. Never log it, never put
     * it in a message, never return it from a public API.
     */
    internal val apiSecret: String = BuildConfig.LASTFM_API_SECRET

    /**
     * Whether this build has both credentials.
     *
     * The shape check is what separates a real credential from the placeholders in
     * `lastfm.properties.example`, so a file copied but not filled in leaves the
     * feature absent rather than producing
     * [LastfmError.INVALID_API_KEY] on the listener's first tap.
     *
     * If Last.fm ever issues a credential in another shape, [looksLikeCredential]
     * is the single place to relax - and the symptom will be a build with real
     * credentials in which the feature simply does not appear.
     */
    val isConfigured: Boolean
        get() = looksLikeCredential(apiKey) && looksLikeCredential(apiSecret)

    /**
     * The shape Last.fm issues both the api key and the shared secret in: 32 hex
     * characters. Case-insensitive, because nothing guarantees which case a value
     * was pasted in.
     *
     * This answers "is this a credential rather than a placeholder", and never
     * "is this credential valid" - only Last.fm can say that.
     */
    fun looksLikeCredential(value: String): Boolean =
        value.length == CREDENTIAL_LENGTH && value.all { it in HEX_DIGITS }

    /**
     * Deliberately says nothing about the values.
     *
     * A config object tends to end up interpolated into a log line eventually, and
     * the default `toString` of a Kotlin `object` is harmless only by accident. This
     * one is harmless on purpose.
     */
    override fun toString(): String = "LastfmConfig(isConfigured=$isConfigured)"

    private const val CREDENTIAL_LENGTH = 32

    private val HEX_DIGITS: Set<Char> =
        ('0'..'9').toSet() + ('a'..'f').toSet() + ('A'..'F').toSet()
}
