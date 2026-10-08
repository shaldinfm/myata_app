package com.example.musicplayerapp.data

/**
 * Which track the PLAYER's reaction controls are showing, and whether an answer from
 * the database still belongs to it.
 *
 * ## Why this exists
 *
 * The controls follow one Room observation per track. The now-playing state is
 * republished on every metadata poll, and each republish used to cancel that
 * observation and start a new one - for the same track - while the reaction itself
 * was delivered with `postValue`, which lands on a later main-thread message. A value
 * already posted by an observation that had just been cancelled for a *new* track
 * could therefore still arrive afterwards and paint the previous track's Like or
 * Dislike onto the next one.
 *
 * So the current track is held here, and every emission carries the key it was read
 * for: an answer about any other track is dropped, whenever it arrives. And the same
 * track arriving again is reported as no change, so a poll no longer restarts the
 * observation at all.
 *
 * Pure: `CurrentTrackReactionTest` walks it without a database or a main thread.
 */
class CurrentTrackReaction {

    /** The track whose reaction is on the controls, or null between tracks. */
    var trackKey: String? = null
        private set

    /**
     * The now-playing track is [key] (null: none). True when that is a different track
     * from the one being shown - the caller must then clear the controls and observe
     * the new key; false when it is the same track and nothing needs to happen.
     */
    fun onTrack(key: String?): Boolean {
        if (key == trackKey) return false
        trackKey = key
        return true
    }

    /**
     * What the controls should show for a database answer about [key]: [stored] (no row
     * is NEUTRAL), NEUTRAL while the active rows are not this install's to show, or
     * null when [key] is no longer the current track and the answer must be dropped.
     */
    fun shown(key: String, stored: Reaction?, visible: Boolean): Reaction? = when {
        key != trackKey -> null
        !visible -> Reaction.NEUTRAL
        else -> stored ?: Reaction.NEUTRAL
    }
}
