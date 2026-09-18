package com.example.musicplayerapp.data.lastfm

import androidx.annotation.VisibleForTesting
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

    /**
     * Test-only answer for [isConfigured], and the only way instrumentation can
     * reach the Last.fm screen.
     *
     * G6b ships before its credentials exist, so every build in existence - CI, a
     * fresh clone, this developer's - is currently the unconfigured one, and the
     * Settings row and its screen would be untestable on a device without this.
     * The suites set it both ways round: to true, to reach the row at all, and to
     * false, to prove the row and its section then disappear.
     *
     * Deliberately an override of the *decision* rather than of the credentials.
     * A test that supplied a fake key and secret would be a test that could sign
     * something, and nothing in this slice should be able to.
     *
     * It changes nothing in a shipped app: nothing in `src/main` writes it, and a
     * release build has no code that can.
     */
    @VisibleForTesting
    @Volatile
    var configuredOverrideForTest: Boolean? = null

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
     * "Has", not "has well-formed" - see [isSupplied] for why no shape is checked.
     */
    val isConfigured: Boolean
        get() = configuredOverrideForTest ?: (isSupplied(apiKey) && isSupplied(apiSecret))

    /**
     * Whether [value] is a credential somebody actually supplied, as opposed to
     * absent, blank, or a template placeholder.
     *
     * ## Why this checks presence and not shape
     *
     * An earlier version of this required both values to be exactly 32 hexadecimal
     * characters. That was checked against Last.fm's official documentation and is
     * **not** a format Last.fm guarantees:
     *
     *  - the **api key**'s length *is* documented - `/api/authspec` says "Your
     *    32-character API Key" in §3.3, §4.1 and §7 - but its **character set is
     *    not documented anywhere**;
     *  - the **shared secret** has **neither** documented. `/api/authspec` §2 says
     *    only "Your account page contains your secret", and §8's own worked example
     *    uses a secret of `'ilovecher'` - nine characters, not hexadecimal. The
     *    desktop, web and mobile how-tos all use `'mysecret'`, eight characters and
     *    likewise not hexadecimal;
     *  - the "32-character hexadecimal" wording that does appear in the
     *    specification describes **api_sig**, which is an md5 output and therefore
     *    32 hex by construction. It says nothing about the credentials that go into
     *    it.
     *
     * So a 32-hex rule would have been an invented restriction, and the way it
     * failed would have been silent: a build with genuine credentials in which the
     * feature simply never appears, with no error anywhere to explain it. Last.fm's
     * documented example secret would have been rejected by our own validator.
     *
     * The rule is therefore the narrowest one that still does the job it was added
     * for - keeping a `lastfm.properties.example` that was copied but never filled
     * in from producing [LastfmError.INVALID_API_KEY] on a listener's first tap.
     * Whether a credential is *valid* is Last.fm's answer to give, not ours.
     */
    fun isSupplied(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.isNotEmpty() && !isPlaceholder(trimmed)
    }

    /**
     * Whether [value] is one of the template's own placeholders.
     *
     * Matched on the `REPLACE` marker rather than the exact strings, so editing the
     * wording in `lastfm.properties.example` cannot quietly turn a placeholder into
     * something this accepts as a credential. The build applies the same marker to
     * the tracked template from the other direction - it refuses any value there
     * that is *not* a placeholder - so the two checks meet in the middle.
     */
    private fun isPlaceholder(value: String): Boolean =
        value.contains(PLACEHOLDER_MARKER, ignoreCase = true)

    /**
     * Deliberately says nothing about the values.
     *
     * A config object tends to end up interpolated into a log line eventually, and
     * the default `toString` of a Kotlin `object` is harmless only by accident. This
     * one is harmless on purpose.
     */
    override fun toString(): String = "LastfmConfig(isConfigured=$isConfigured)"

    /**
     * The marker every placeholder in `lastfm.properties.example` carries. Kept in
     * step with the same marker in `app/build.gradle`'s template guard.
     */
    private const val PLACEHOLDER_MARKER = "REPLACE"
}
