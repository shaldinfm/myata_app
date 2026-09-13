package com.example.musicplayerapp.service

/**
 * The two decisions `MediaPlayerService.updateMetadata` makes about the
 * notification / lock-screen metadata, kept free of Android types so they can be
 * pinned by JVM tests (G5d).
 *
 * The service used to decide both from the last strings it had been *given*. The
 * player's MediaItem is replaced underneath those strings - `startStop` installs a
 * bare stream item on every resume - so "same artist and title as last time" was
 * not evidence that the session still showed them. The notification then fell back
 * to the raw ICY `ARTIST - TITLE` with no artist and no artwork until the next
 * track change. These rules look at what the session actually carries instead.
 */
internal object SessionMetadataPolicy {

    /** What the player's current MediaItem carries, as plain strings. */
    data class Installed(val title: String?, val artist: String?, val artworkUri: String?)

    /**
     * Whether the session already shows [title] and [artist] with [artworkUrl] -
     * the only case in which a repeated update may be skipped. A bare item (no
     * title) or one missing the known cover is not "installed".
     */
    fun isInstalled(installed: Installed?, title: String, artist: String, artworkUrl: String?): Boolean =
        installed != null &&
            installed.title == title &&
            installed.artist == artist &&
            installed.artworkUri == artworkUrl

    /**
     * Whether a cover lookup should start for the track just applied.
     *
     * A new track always gets one (its predecessor's lookup was cancelled). The same
     * track gets one only if no lookup has finished for it and none is running - so
     * a refresh never restarts a valid in-flight lookup, and a lookup that was lost
     * is not left lost.
     */
    fun shouldFetchArtwork(
        isPlaceholder: Boolean,
        trackChanged: Boolean,
        artworkSettled: Boolean,
        fetchInFlight: Boolean,
    ): Boolean = when {
        isPlaceholder -> false
        trackChanged -> true
        else -> !artworkSettled && !fetchInFlight
    }
}
