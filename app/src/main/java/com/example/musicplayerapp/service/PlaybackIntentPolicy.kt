package com.example.musicplayerapp.service

import com.example.musicplayerapp.data.PlaybackIntentStore
import com.example.musicplayerapp.data.Streams

/**
 * What a service that has just come back from nothing is allowed to do, kept free
 * of Android types so it can be pinned by JVM tests - the same shape
 * [StreamErrorPolicy] and [SessionMetadataPolicy] already have.
 *
 * Two questions, and they are deliberately separate:
 *
 *  - [onStickyRestart] - *the system* handed the service back after a process
 *    death. Nobody is watching. Audio may start only because the listener had
 *    already said they wanted it, and a record that cannot be read must produce
 *    silence rather than a guess.
 *  - [onEmptyTimelinePlay] - *the listener* pressed Play, on a notification or a
 *    media button, and the fresh player has nothing loaded. Here the intent is
 *    the press itself, so the stored boolean has nothing to say; the only
 *    question is which station, and the answer must never be "none" - a Play that
 *    does nothing is issue #14 all over again.
 */
internal object PlaybackIntentPolicy {

    /** What the null-intent `START_STICKY` restart should do. */
    sealed class Restore {
        /** Nothing to restore, or somebody has already restored it. */
        data class Skip(val reason: String) : Restore()

        /** The record names something that is not a stream; remove it. */
        data class Discard(val rawStream: String) : Restore()

        /**
         * Take the station back, but do not make a sound: the listener had
         * stopped. This is what lets a later Play know which stream it means.
         */
        data class Adopt(val stream: String) : Restore()

        /** Take the station back and start it: the listener wanted audio. */
        data class Resume(val stream: String) : Restore()
    }

    /**
     * [alreadyRestored] and [playerHasMediaItem] are both "somebody got here
     * first". The system can deliver more than one start command to a restarted
     * service, and Media3 issues its own while playback runs; neither may be
     * allowed to prepare and play a second time on top of a player that is
     * already going.
     */
    fun onStickyRestart(
        stored: PlaybackIntentStore.Stored,
        alreadyRestored: Boolean,
        playerHasMediaItem: Boolean,
    ): Restore = when {
        alreadyRestored -> Restore.Skip("already_restored")
        playerHasMediaItem -> Restore.Skip("player_already_loaded")
        stored is PlaybackIntentStore.Stored.None -> Restore.Skip("no_durable_intent")
        stored is PlaybackIntentStore.Stored.Unusable -> Restore.Discard(stored.rawStream)
        stored is PlaybackIntentStore.Stored.Known && !stored.wantsPlayback ->
            Restore.Adopt(stored.stream)
        stored is PlaybackIntentStore.Stored.Known -> Restore.Resume(stored.stream)
        else -> Restore.Skip("unreachable")
    }

    /** A resolved stream, and which of the sources below answered. */
    data class Selection(val stream: String, val source: String)

    /**
     * Which station the **UI** should show, from the three things that can know.
     *
     * The order is the whole point, and it is why the app can no longer play GOLD
     * while drawing MYATA:
     *
     *  1. **[sessionMediaId]** - what the session is actually pointed at. The
     *     service labels each stream's `MediaItem` with the stream key, and the
     *     media id is the one field that survives the session boundary. This is
     *     the service's own selection, read from the service, and it outranks
     *     everything because it is the thing making the sound.
     *  2. **[uiStream]** - what this UI was already showing. A recreated UI (a
     *     theme change, a rotation, a restored task) knows its own state, and the
     *     session cannot contradict it here because it had nothing to say.
     *  3. **[storedStream]** - the durable record, i.e. what the service last
     *     settled on. This is the answer in the case the session cannot cover: a
     *     restart that restored a *paused* station holds the station in the
     *     service but has no media item to report it with.
     *  4. The default, when none of them is a stream.
     *
     * Nothing here owns state. The UI does not become the keeper of the durable
     * record - it reads it, once, when nothing better can answer.
     */
    fun uiStream(
        sessionMediaId: String?,
        uiStream: String?,
        storedStream: String?,
    ): Selection {
        Streams.normalise(sessionMediaId)?.let { return Selection(it, "session") }
        Streams.normalise(uiStream)?.let { return Selection(it, "ui_state") }
        Streams.normalise(storedStream)?.let { return Selection(it, "durable_intent") }
        return Selection(Streams.DEFAULT, "default")
    }

    /**
     * The in-memory selection wins when there is one: a station switched to after
     * the restart is a newer decision than anything on disk. Otherwise the record,
     * and failing that the default - because the listener is pressing Play and
     * has to hear something.
     */
    fun onEmptyTimelinePlay(
        stored: PlaybackIntentStore.Stored,
        inMemoryStream: String?,
    ): Selection {
        Streams.normalise(inMemoryStream)?.let { return Selection(it, "current_selection") }
        return when (stored) {
            is PlaybackIntentStore.Stored.Known -> Selection(stored.stream, "durable_intent")
            is PlaybackIntentStore.Stored.Unusable -> Selection(Streams.DEFAULT, "default_unusable_record")
            PlaybackIntentStore.Stored.None -> Selection(Streams.DEFAULT, "default_no_record")
        }
    }
}
