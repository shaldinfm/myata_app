package com.example.musicplayerapp.data

import android.content.Context
import androidx.core.content.edit

/**
 * The two facts a killed process has to be able to get back: which station, and
 * whether the listener wanted it playing.
 *
 * ## Why this exists
 *
 * `START_STICKY` hands `MediaPlayerService` back with a **null intent**. The
 * process is new, so `stream` is `""`, the player has no media item and
 * `userWantsPlayback` is `false` - and every one of those was the only copy of
 * something the listener had told us. Playback stayed stopped, and the
 * notification's Play could not fix it either, because `play()` on an empty
 * timeline is a silent no-op. Pressing Play *inside the app* worked only because
 * that path carries the stream key on its intent.
 *
 * ## What is stored, and what is deliberately not
 *
 * A stream key and a boolean. Nothing else - no track, no artist, no artwork, no
 * position, no recovery counters, no credentials, and nothing that belongs to
 * Media3. Transient player state is exactly what must not come back: a live radio
 * stream has no position to resume to, and a restored buffer would be stale by the
 * length of the outage.
 *
 * ## Three operations, not two fields
 *
 * The API is deliberately not a pair of setters. A Play is **one** event - "this
 * station, and I want it" - and writing it as two edits leaves a window in which
 * a process death sees the new intent against the *previous* station and restores
 * the wrong radio. So [recordPlaybackWanted] writes both keys through one editor,
 * and the other two say exactly which field they touch.
 *
 * ## commit(), not apply()
 *
 * `apply()` returns before the file is written and is lost if the process is
 * killed in between - which is precisely the event this record exists for. These
 * are two keys, written only when a listener presses something, so the
 * synchronous write costs nothing that matters and removes the window entirely.
 *
 * ## No boot identity, unlike [SleepTimerStore]
 *
 * A sleep timer must not survive a reboot, so it is stamped with [BootIdentity].
 * This record has no such rule and needs none: nothing in the app starts the
 * service at boot - there is no `BOOT_COMPLETED` receiver anywhere - so a record
 * that outlives a reboot is never *acted* on by itself. It is only ever read when
 * something has already started the service, and then "which station was last
 * chosen" is just as true after a reboot as before one.
 *
 * ## Not backed up
 *
 * `myata_playback_intent.xml` is excluded from cloud backup and device transfer -
 * see `res/xml/lastfm_backup_rules.xml` and `lastfm_data_extraction_rules.xml`.
 * It is runtime state about one device's speaker; restoring it onto a new phone
 * would mean an app that has never played anything believing it was mid-broadcast.
 *
 * ## One writer
 *
 * `MediaPlayerService` writes; everything else reads. Same rule as
 * [SleepTimerStore], for the same reason: two writers would race over which of
 * them last knew what the listener wanted.
 */
object PlaybackIntentStore {

    /** Kept public so the backup-rule test can assert the excluded path is this one. */
    const val FILE = "myata_playback_intent"

    private const val KEY_STREAM = "stream_key"
    private const val KEY_WANTS_PLAYBACK = "wants_playback"

    /** What [read] found. */
    sealed class Stored {
        /** Nothing on disk: nothing has ever been played, or the record was cleared. */
        object None : Stored()

        /**
         * A stream key that is not a stream. Cannot be recovered from and cannot
         * be corrected from here - [rawStream] is kept only so the caller can say
         * what it threw away.
         */
        data class Unusable(val rawStream: String) : Stored()

        /** A canonical stream key, and what the listener last asked for. */
        data class Known(val stream: String, val wantsPlayback: Boolean) : Stored()
    }

    /**
     * One event: this station, and the listener wants it playing.
     *
     * Both keys go through a single editor, so there is no instant at which the
     * record says "playback wanted" beside a station the listener has just moved
     * off. Called before the playback that follows it can be lost to a process
     * death - the write is what makes the Play survive the kill, so it has to
     * happen first.
     *
     * Returns false, having written nothing, if [streamKey] is not a stream.
     */
    fun recordPlaybackWanted(context: Context, streamKey: String?): Boolean {
        val normalised = Streams.normalise(streamKey) ?: return false
        prefs(context).edit(commit = true) {
            putString(KEY_STREAM, normalised)
            putBoolean(KEY_WANTS_PLAYBACK, true)
        }
        return true
    }

    /**
     * The station alone, leaving the intent as it was.
     *
     * For the places that settle on a stream without that being a claim about
     * whether audio is wanted. A key that is not a stream is refused rather than
     * written: the record's only job is to be usable later, and a record nobody
     * can act on is worse than the older, valid one it would have overwritten.
     */
    fun recordSelectedStream(context: Context, streamKey: String?): Boolean {
        val normalised = Streams.normalise(streamKey) ?: return false
        prefs(context).edit(commit = true) { putString(KEY_STREAM, normalised) }
        return true
    }

    /**
     * The listener stopped. The station is kept.
     *
     * Written **before** the stop it describes, so a kill in between cannot leave
     * a record that resurrects audio somebody asked to end. Keeping the station is
     * what lets the notification's Play work after a restart instead of guessing.
     *
     * Every caller is an explicit stop or one of the system stops this app already
     * treats as "press Play again": a pause, a stop, a sleep-timer expiry,
     * headphones pulled out, audio focus lost.
     */
    fun recordPlaybackNotWanted(context: Context) {
        prefs(context).edit(commit = true) { putBoolean(KEY_WANTS_PLAYBACK, false) }
    }

    /**
     * What is on disk. Reads only.
     *
     * Discarding a [Stored.Unusable] record is the caller's move - the same
     * division [SleepTimerStore.restore] draws - because the caller is also what
     * has to reconcile the player with it.
     */
    fun read(context: Context): Stored {
        val p = prefs(context)
        val raw = p.getString(KEY_STREAM, null) ?: return Stored.None
        val normalised = Streams.normalise(raw) ?: return Stored.Unusable(raw)
        return Stored.Known(normalised, p.getBoolean(KEY_WANTS_PLAYBACK, false))
    }

    /**
     * The last station the service settled on, or null if there is no usable
     * record. The UI's fallback when the session cannot answer - see
     * `StreamsViewModel.initialStream`.
     */
    fun selectedStream(context: Context): String? =
        (read(context) as? Stored.Known)?.stream

    fun clear(context: Context) {
        prefs(context).edit(commit = true) {
            remove(KEY_STREAM)
            remove(KEY_WANTS_PLAYBACK)
        }
    }

    /** Test-only: return this install to the state a fresh one is in. */
    fun clearForTest(context: Context) {
        prefs(context).edit(commit = true) { clear() }
    }

    /** Test-only: plant a record directly, including one the writers would refuse. */
    fun writeRawForTest(context: Context, streamKey: String?, wantsPlayback: Boolean) {
        prefs(context).edit(commit = true) {
            if (streamKey == null) remove(KEY_STREAM) else putString(KEY_STREAM, streamKey)
            putBoolean(KEY_WANTS_PLAYBACK, wantsPlayback)
        }
    }

    /** Test-only: the raw stored key, whatever it says. */
    fun rawStreamForTest(context: Context): String? =
        prefs(context).getString(KEY_STREAM, null)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
