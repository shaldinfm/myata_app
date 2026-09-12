package com.example.musicplayerapp.data

/**
 * Which cover the app is entitled to draw for the track playing now, per stream.
 *
 * The now-playing state has two writers - the metadata poll and the session's
 * `onMediaMetadataChanged` - and before G5a each of them decided on its own
 * whether an artwork answer still belonged on screen. Both decided it by reading
 * the LiveData back, which is not an answer to that question: `postValue` is
 * delivered on a later main-thread message, so a lookup that returned without
 * suspending - a cache hit - read the *previous* track and threw its own answer
 * away. The cover then stayed as it was, which is how a finished track's artwork
 * ended up under the next track's title.
 *
 * So the question is put to this object instead, and it is answered from explicit
 * state rather than from delivery timing. Each stream owns exactly one identity -
 * the track it last announced - and at most one cover for it. The three ordering
 * guarantees all fall out of that single rule:
 *
 *  - a cover can only be applied to the identity it was requested for, so a
 *    lookup that finishes after the stream has moved on cannot paint;
 *  - announcing a new identity drops the old cover, so artwork never carries
 *    forward from the previous track;
 *  - a writer with nothing to offer cannot take away what another writer has
 *    already resolved for the same track.
 *
 * It holds three entries at most, one per stream. Nothing here fetches, nothing
 * here caches across tracks, and nothing here knows what a provider is: the
 * lookup cache is still [ArtworkRepository]'s, untouched by G5a.
 */
class NowPlayingArtwork {

    private data class Owned(val identity: String, val img: String?)

    private val owned = HashMap<String, Owned>()

    /**
     * [stream] is now playing [identity]. Returns the cover that track already
     * owns, or null when it has none yet and one has to be looked up.
     *
     * Announcing a *different* identity is what discards the previous track's
     * cover, and it happens before anything is published, so no writer can hand
     * the UI a state still carrying the old artwork.
     */
    fun announce(stream: String, identity: String): String? {
        val existing = owned[stream]
        if (existing == null || existing.identity != identity) {
            owned[stream] = Owned(identity, null)
            return null
        }
        return existing.img
    }

    /** The cover [stream] currently owns, or null while it has none. */
    fun current(stream: String): String? = owned[stream]?.img

    /** The identity [stream] last announced, or null before its first. */
    fun identity(stream: String): String? = owned[stream]?.identity

    /**
     * Offers an answer for [identity] - from the resolver, or from the playback
     * session's own metadata. Returns whether it became the stream's cover, which
     * is also whether the caller should publish it.
     *
     * Refused when the stream has moved on to another track, and refused when it
     * would make the current track's cover worse:
     *
     *  - nothing (null or blank) never replaces anything;
     *  - [NO_IMAGE] - "the resolver looked and found none" - never replaces a real
     *    cover, while a real cover does replace [NO_IMAGE];
     *  - a real cover never replaces a *different* real cover, so the first answer
     *    for a track is its answer for as long as it plays. Two writers racing
     *    therefore cannot swap the artwork back and forth under the reader.
     */
    fun offer(stream: String, identity: String, img: String?): Boolean {
        if (img.isNullOrBlank()) return false
        val existing = owned[stream] ?: return false
        if (existing.identity != identity) return false

        val held = existing.img
        if (held != null && (held != NO_IMAGE || img == NO_IMAGE)) return false

        owned[stream] = existing.copy(img = img)
        return true
    }

    companion object {

        /** The resolver looked and found nothing. Distinct from "not resolved yet". */
        const val NO_IMAGE = "NO_IMAGE"

        /**
         * What "the same track" means for artwork.
         *
         * [TrackKey] v1 where the pair is a track, which is the identity the rest
         * of the app already agrees on. Where it is not - a blank field between
         * tracks, or the station's own ident, both of which [TrackKey.of] refuses -
         * the normalised pair stands in, so those states still get an identity of
         * their own and still cannot inherit the previous track's cover. Never
         * null, because every announced state needs something to be compared by.
         *
         * The separator is the same ASCII Unit Separator [TrackKey] uses, and for
         * the same reason: [TrackKey.normalize] removes it from both fields, so it
         * cannot be smuggled in to make two different pairs read as one identity.
         */
        fun identityOf(artist: String?, title: String?): String =
            TrackKey.of(artist, title)
                ?: "raw:${TrackKey.normalize(artist)}\u001F${TrackKey.normalize(title)}"

        /**
         * The URL to draw for a [PlayerState.img], or null when what belongs on
         * screen is the fallback plate: no lookup has finished yet (null), or one
         * finished and found nothing ([NO_IMAGE]).
         */
        fun coverUrl(img: String?): String? =
            img?.takeUnless { it.isBlank() || it == NO_IMAGE }
    }
}
