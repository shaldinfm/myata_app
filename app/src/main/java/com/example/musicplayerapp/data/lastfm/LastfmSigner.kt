package com.example.musicplayerapp.data.lastfm

/**
 * Who can produce an `api_sig`, as an interface rather than a function call.
 *
 * This is the seam the whole credential decision rests on. Shipping the shared
 * secret in the APK was accepted for v1 ([LastfmConfig] says what that does and
 * does not expose), and the condition attached to accepting it was that the
 * decision stay reversible. It stays reversible because nothing in this feature
 * ever reaches for the secret: request construction is handed a [LastfmSigner]
 * and asks it to sign a parameter map.
 *
 * Moving signing off the device is therefore a second implementation - one that
 * posts the parameter map to an endpoint holding the secret and returns the
 * signature it is given - plus one line in [LastfmSigners]. No builder, no error
 * mapping, no state machine and no test of any of them has to change. That is the
 * only reason a secret in an APK is an acceptable starting point instead of a
 * permanent one.
 *
 * It is also what lets every test in this package run on fake credentials: a test
 * constructs [SharedSecretSigner] with a made-up secret, and no test needs, reads
 * or could accidentally use a real one.
 */
interface LastfmSigner {

    /**
     * The `api_sig` for [params], or null if this build cannot sign at all.
     *
     * Null is the unconfigured case and is not an error: a build with no
     * credentials has no Last.fm in it, and a caller that gets null has no request
     * to make rather than a failure to report. It is deliberately not an exception,
     * because "this build has no Last.fm" is a state the app is expected to be in.
     *
     * [params] must be everything the call will send **except** `api_sig`; the
     * exclusion of `format` and `callback` is [LastfmSignature]'s business, not the
     * caller's.
     */
    fun sign(params: Map<String, String>): String?
}

/**
 * Signs on the device with a shared secret held in memory.
 *
 * Takes the secret as a constructor argument rather than reading [LastfmConfig]
 * itself, which is what makes it testable: the tests instantiate it with an
 * obviously fake secret and assert the signature, and a real credential is never
 * involved in a test run. [LastfmSigners.forThisBuild] is what binds it to the
 * build's actual secret, in exactly one place.
 *
 * A blank secret signs nothing. That is the unconfigured build, and returning null
 * here rather than producing a signature over an empty secret means such a build
 * cannot accidentally emit a request Last.fm would reject.
 */
class SharedSecretSigner(private val secret: String) : LastfmSigner {

    override fun sign(params: Map<String, String>): String? {
        if (secret.isBlank()) return null
        return LastfmSignature.sign(params, secret)
    }

    /** Never the secret, not even its length. */
    override fun toString(): String = "SharedSecretSigner(configured=${secret.isNotBlank()})"
}

/**
 * The one place a signer is bound to this build's credentials.
 *
 * Everything else in the feature receives a [LastfmSigner]. When signing moves
 * server-side, this function is what changes.
 */
object LastfmSigners {

    /**
     * The signer this build can use, or null when it has no credentials.
     *
     * Checks [LastfmConfig.isConfigured] rather than only the secret, because a
     * build with a secret and no api key cannot make a call either, and the caller
     * should get one honest "there is no Last.fm here" instead of a signer that
     * works and requests that do not.
     */
    fun forThisBuild(): LastfmSigner? =
        if (LastfmConfig.isConfigured) SharedSecretSigner(LastfmConfig.apiSecret) else null
}
