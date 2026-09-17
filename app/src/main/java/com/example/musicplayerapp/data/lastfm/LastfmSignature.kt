package com.example.musicplayerapp.data.lastfm

import java.security.MessageDigest

/**
 * The `api_sig` construction, and nothing else.
 *
 * Pure: strings in, string out, no Android types, no network, no configuration. It
 * is the one piece of this feature that is impossible to test against the real
 * service - a wrong signature produces
 * [LastfmError.INVALID_METHOD_SIGNATURE] and nothing more informative - so it is
 * pinned by golden vectors in `LastfmSignatureTest` instead.
 *
 * ## The rule, from Last.fm's own specification
 *
 * Verified against https://www.last.fm/api/authspec rather than reproduced from
 * memory, because every clause is load-bearing:
 *
 *  1. take every parameter that will be sent **except `format` and `callback`** -
 *     the spec says "You must not include the format and callback parameters";
 *  2. order them alphabetically by parameter name;
 *  3. concatenate them as `<name><value>` with no separator of any kind;
 *  4. append the shared secret;
 *  5. MD5 the UTF-8 bytes of that string;
 *  6. render as 32 lowercase hex characters.
 *
 * `api_sig` itself is of course never part of its own input, which is why signing
 * happens on the parameter map *before* the signature is added to it - see
 * [LastfmRequestFactory].
 *
 * ## Why the exclusions are a set and not two `if`s
 *
 * Adding `format=json` is the last thing request construction does and the easiest
 * thing to accidentally sign. Naming the excluded parameters once, here, means the
 * builders cannot get it wrong individually: they hand over everything and this
 * decides.
 *
 * ## Ordering
 *
 * [String.compareTo], which is UTF-16 code-unit order and locale-independent -
 * never a `Collator` and never `CASE_INSENSITIVE_ORDER`, either of which would
 * hand a device with a different locale a different signature for the same call.
 * Every parameter name Last.fm defines is ASCII, so this is plain ASCII order.
 *
 * It puts `artist[10]` before `artist[1]`, because `'0'` (0x30) sorts before `']'`
 * (0x5D). That looks wrong and is correct: what matters is only that the order is
 * deterministic and is the one the server re-derives from the parameters it
 * received, and a straight alphabetical sort of the full names is that order. The
 * batch vectors in the tests exist to pin exactly this case, since it is invisible
 * until a batch grows past ten entries.
 *
 * ## MD5
 *
 * MD5 is broken for every purpose that matters and is what the Last.fm protocol
 * specifies, so it is what this uses. It is not protecting anything here: the
 * signature proves possession of the shared secret, which ships in the APK anyway
 * (see [LastfmConfig]). Nothing else in this app hashes with MD5 - identity uses
 * SHA-256 in `TrackKey`.
 */
object LastfmSignature {

    /**
     * The parameters Last.fm's specification excludes from the signature.
     *
     * `format` is added by request construction and `callback` only exists for
     * JSONP, which this app never uses; both are listed so the rule reads as the
     * whole rule.
     */
    private val UNSIGNED_PARAMS = setOf("format", "callback")

    /**
     * Steps 1-4: the exact string that gets hashed, secret included.
     *
     * Public because it is half the contract. A signature mismatch is otherwise
     * undebuggable - the service says only "invalid method signature" - and being
     * able to assert the base string is what makes the golden vectors readable.
     *
     * **Never log the result.** It ends with the shared secret in clear text.
     */
    fun signatureBase(params: Map<String, String>, secret: String): String {
        val builder = StringBuilder()
        params.entries
            .filterNot { it.key in UNSIGNED_PARAMS }
            .sortedBy { it.key }
            .forEach { builder.append(it.key).append(it.value) }
        return builder.append(secret).toString()
    }

    /** Steps 1-6: the `api_sig` value for [params]. */
    fun sign(params: Map<String, String>, secret: String): String =
        md5Hex(signatureBase(params, secret))

    /** The UTF-8 MD5 of [value], as 32 lowercase hex characters. */
    fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))

        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val unsigned = byte.toInt() and 0xFF
            hex.append(HEX[unsigned ushr 4]).append(HEX[unsigned and 0x0F])
        }
        return hex.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
